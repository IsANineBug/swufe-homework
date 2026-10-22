package jobs_test

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/jobs"
	"github.com/IsANineBug/swufe-homework/server/internal/testdb"
)

func TestJobs(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	dir := t.TempDir()
	now := time.Now()
	j := &jobs.Runner{DB: pool, UploadDir: dir, Now: func() time.Time { return now }}
	uid := testdb.User(t, pool, "甲同学")
	exec := func(sql string, args ...any) {
		t.Helper()
		if _, err := pool.Exec(ctx, sql, args...); err != nil {
			t.Fatalf("%s: %v", sql, err)
		}
	}
	count := func(sql string, args ...any) int {
		t.Helper()
		var n int
		if err := pool.QueryRow(ctx, sql, args...).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}

	// 学期：当前(1) 之前有 prev(2) 与 old(3)
	exec(`INSERT INTO semesters (name, starts_on, ends_on) VALUES ('2025-2026-2', '2026-03-01', '2026-08-01'), ('2025-2026-1', '2025-09-01', '2026-02-01')`)
	exec(`INSERT INTO classes (semester_id, name, created_by) VALUES (1, '概率论', $1)`, uid)
	exec(`INSERT INTO assignments (class_id, title, ddl, created_by) VALUES (1, 'a', now(), $1), (1, 'b', now(), $1)`, uid)
	exec(`INSERT INTO assignment_sources (assignment_id, original_assignment_id, author_id, kind) VALUES (1, 1, $1, 'first'), (2, 2, $1, 'first')`, uid)
	exec(`UPDATE assignment_sources SET deleted_at = now(), deleted_reason = 'author' WHERE id = 2`)

	// 贡献值：到期的在世来源 → settled；来源已删 → void；未到期保持 pending
	exec(`INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day) VALUES
		($1, 1, 1, 1, 'first_post', 10, 'pending', $2, current_date),
		($1, 1, 1, 2, 'first_post', 10, 'pending', $2, current_date),
		($1, 1, 1, 1, 'supplement', 3, 'pending', $3, current_date)`, uid, now.Add(-time.Minute), now.Add(time.Hour))
	if err := j.SettlePoints(ctx); err != nil {
		t.Fatal(err)
	}
	if count(`SELECT count(*) FROM points_ledger WHERE status = 'settled' AND source_id = 1`) != 1 ||
		count(`SELECT count(*) FROM points_ledger WHERE status = 'void' AND source_id = 2`) != 1 ||
		count(`SELECT count(*) FROM points_ledger WHERE status = 'pending'`) != 1 {
		t.Fatal("settle result wrong")
	}

	// 合并提议 7 天过期
	exec(`INSERT INTO merge_proposals (from_id, to_id, proposer_id, created_at) VALUES (2, 1, $1, $2), (1, 2, $1, $3)`, uid, now.Add(-8*24*time.Hour), now)
	if err := j.ExpireMergeProposals(ctx); err != nil {
		t.Fatal(err)
	}
	if count(`SELECT count(*) FROM merge_proposals WHERE status = 'expired'`) != 1 {
		t.Fatal("expire wrong")
	}

	// token：过期、撤销超 1 天的删除；有效的、刚撤销的保留
	exec(`INSERT INTO auth_tokens (token_hash, user_id, expires_at, revoked_at) VALUES
		('a', $1, $2, NULL), ('b', $1, $3, NULL), ('c', $1, $3, $4), ('d', $1, $3, $5)`,
		uid, now.Add(-time.Hour), now.Add(time.Hour), now.Add(-48*time.Hour), now.Add(-time.Hour))
	if err := j.CleanAuthTokens(ctx); err != nil {
		t.Fatal(err)
	}
	if count(`SELECT count(*) FROM auth_tokens`) != 2 {
		t.Fatal("token cleanup wrong")
	}

	// 文件：上一学期(2)保留，更早(3)清理；未发布超 24h 的删除
	touch := func(rel string) {
		p := filepath.Join(dir, filepath.FromSlash(rel))
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for _, f := range []string{"cur/a.jpg", "prev/b.jpg", "old/c.jpg", "old/c_thumb.jpg", "cur/orphan.jpg"} {
		touch(f)
	}
	exec(`INSERT INTO attachments (source_id, uploader_id, semester_id, kind, path, thumb_path, bytes, created_at) VALUES
		(1, $1, 1, 'image', 'cur/a.jpg', NULL, 1, now()),
		(1, $1, 2, 'image', 'prev/b.jpg', NULL, 1, now()),
		(1, $1, 3, 'image', 'old/c.jpg', 'old/c_thumb.jpg', 1, now()),
		(NULL, $1, 1, 'image', 'cur/orphan.jpg', NULL, 1, $2)`, uid, now.Add(-25*time.Hour))
	if err := j.PurgeOldSemesters(ctx); err != nil {
		t.Fatal(err)
	}
	if err := j.PurgeUnboundUploads(ctx); err != nil {
		t.Fatal(err)
	}
	if count(`SELECT count(*) FROM attachments WHERE purged_at IS NOT NULL`) != 1 || count(`SELECT count(*) FROM attachments`) != 3 {
		t.Fatal("purge rows wrong")
	}
	for f, want := range map[string]bool{"cur/a.jpg": true, "prev/b.jpg": true, "old/c.jpg": false, "old/c_thumb.jpg": false, "cur/orphan.jpg": false} {
		_, err := os.Stat(filepath.Join(dir, f))
		if (err == nil) != want {
			t.Errorf("%s exists=%v want %v", f, err == nil, want)
		}
	}
}
