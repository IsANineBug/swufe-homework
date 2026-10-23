// Package jobs 同进程定时任务（WS2）：
//   - 每 10 分钟：贡献值结算（pending 到期 → settled / void）
//   - 每小时：合并提议 7 天未成 → expired；清理过期或已撤销 1 天以上的 refresh token（issue #4 裁决 15）
//   - 每 6 小时：图片清理（只保留当前学期和上一学期，标记 purged_at 并删文件）；上传后 24h 未发布的附件删除
//   - 每分钟（WS8，v2）：有期限的封号到期后解封（清空 users.disabled_at）
//   - 每天 04:00 Asia/Shanghai（WS8，v2）：班级频道保留策略——硬删 30 天前的消息与每班超出最近 500 条的部分
package jobs

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/files"
	"github.com/IsANineBug/swufe-homework/server/internal/points"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

type Runner struct {
	DB        *pgxpool.Pool
	UploadDir string
	Now       func() time.Time
}

// Start 启动后台循环，ctx 取消即退出。启动时各跑一次。
func (j *Runner) Start(ctx context.Context) {
	if j.Now == nil {
		j.Now = time.Now
	}
	go j.loop(ctx, "settle_points", 10*time.Minute, j.SettlePoints)
	go j.loop(ctx, "hourly", time.Hour, func(ctx context.Context) error {
		return errors.Join(j.ExpireMergeProposals(ctx), j.CleanAuthTokens(ctx))
	})
	go j.loop(ctx, "purge_files", 6*time.Hour, func(ctx context.Context) error {
		return errors.Join(j.PurgeOldSemesters(ctx), j.PurgeUnboundUploads(ctx))
	})
	go j.loop(ctx, "expire_disables", time.Minute, j.ExpireDisables)
	go j.daily(ctx, "purge_messages", 4, j.PurgeMessages)
}

// NextDailyRun now 之后下一个 Asia/Shanghai hour:00。
func NextDailyRun(now time.Time, hour int) time.Time {
	t := now.In(points.Shanghai)
	next := time.Date(t.Year(), t.Month(), t.Day(), hour, 0, 0, 0, points.Shanghai)
	if !next.After(t) {
		next = next.AddDate(0, 0, 1)
	}
	return next
}

// daily 每天 hour:00（Asia/Shanghai）跑一次；启动时不立即跑。
func (j *Runner) daily(ctx context.Context, name string, hour int, fn func(context.Context) error) {
	for {
		wait := NextDailyRun(j.Now(), hour).Sub(j.Now())
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-t.C:
		}
		c, cancel := context.WithTimeout(ctx, time.Hour)
		if err := fn(c); err != nil && ctx.Err() == nil {
			slog.Error("job failed", "job", name, "err", err)
		}
		cancel()
	}
}

// ExpireDisables 有期限的封号到期后解封。
func (j *Runner) ExpireDisables(ctx context.Context) error {
	return store.ExpireDisables(ctx, j.DB, 0, j.Now())
}

// PurgeMessages 频道保留策略：30 天、每班最近 500 条。
func (j *Runner) PurgeMessages(ctx context.Context) error {
	n, err := store.PurgeMessages(ctx, j.DB, j.Now())
	if n > 0 {
		slog.Info("class messages purged", "rows", n)
	}
	return err
}

func (j *Runner) loop(ctx context.Context, name string, every time.Duration, fn func(context.Context) error) {
	run := func() {
		c, cancel := context.WithTimeout(ctx, every)
		defer cancel()
		if err := fn(c); err != nil && ctx.Err() == nil {
			slog.Error("job failed", "job", name, "err", err)
		}
	}
	run()
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			run()
		}
	}
}

func (j *Runner) SettlePoints(ctx context.Context) error {
	n, err := points.Settle(ctx, j.DB, j.Now())
	if n > 0 {
		slog.Info("points settled", "rows", n)
	}
	return err
}

// ExpireMergeProposals 提议 7 天未执行 → expired。
func (j *Runner) ExpireMergeProposals(ctx context.Context) error {
	_, err := j.DB.Exec(ctx, `UPDATE merge_proposals SET status = 'expired'
		WHERE status = 'pending' AND created_at < $1`, j.Now().Add(-7*24*time.Hour))
	return err
}

// CleanAuthTokens 删除已过期、或撤销超过 1 天的 refresh token。
func (j *Runner) CleanAuthTokens(ctx context.Context) error {
	now := j.Now()
	_, err := j.DB.Exec(ctx, `DELETE FROM auth_tokens WHERE expires_at < $1 OR revoked_at < $2`, now, now.Add(-24*time.Hour))
	return err
}

// PurgeOldSemesters 早于「上一学期」的附件：先标记 purged_at（作业页不再显示），再删文件，行保留。
func (j *Runner) PurgeOldSemesters(ctx context.Context) error {
	rows, err := j.DB.Query(ctx, `WITH cur AS (SELECT starts_on FROM semesters WHERE is_current),
		keep AS (SELECT id FROM semesters WHERE starts_on >= (SELECT starts_on FROM cur)
		         UNION SELECT id FROM (SELECT id FROM semesters WHERE starts_on < (SELECT starts_on FROM cur)
		                               ORDER BY starts_on DESC LIMIT 1) prev),
		victims AS (SELECT id FROM attachments
		         WHERE purged_at IS NULL AND EXISTS (SELECT 1 FROM cur) AND semester_id NOT IN (SELECT id FROM keep)
		         ORDER BY id LIMIT 5000)
		UPDATE attachments a SET purged_at = now() FROM victims v WHERE a.id = v.id RETURNING a.path, a.thumb_path`)
	if err != nil {
		return err
	}
	return j.removeFiles(rows)
}

// PurgeUnboundUploads 上传后 24 小时仍未挂到任何来源的附件（放弃发布、编辑时被替换）：删行再删文件。
func (j *Runner) PurgeUnboundUploads(ctx context.Context) error {
	rows, err := j.DB.Query(ctx, `DELETE FROM attachments WHERE id IN (SELECT id FROM attachments
		WHERE source_id IS NULL AND created_at < $1 ORDER BY id LIMIT 5000) AND source_id IS NULL
		RETURNING path, thumb_path`, j.Now().Add(-24*time.Hour))
	if err != nil {
		return err
	}
	return j.removeFiles(rows)
}

func (j *Runner) removeFiles(rows pgx.Rows) error {
	var paths []string
	for rows.Next() {
		var p string
		var thumb *string
		if err := rows.Scan(&p, &thumb); err != nil {
			rows.Close()
			return err
		}
		paths = append(paths, p)
		if thumb != nil {
			paths = append(paths, *thumb)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, p := range paths {
		if !files.ValidRel(p) {
			continue
		}
		if err := os.Remove(filepath.Join(j.UploadDir, filepath.FromSlash(p))); err != nil && !errors.Is(err, fs.ErrNotExist) {
			slog.Warn("remove upload", "err", err)
		}
	}
	if len(paths) > 0 {
		slog.Info("upload files removed", "count", len(paths))
	}
	return nil
}
