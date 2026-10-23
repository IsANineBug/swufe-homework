// Package points 贡献值规则（代码与表名沿用 points）（docs/points-rules.md）：流水写入、24h 结算、每日上限、删除收回与恢复返还。
// 所有会改变某人当日已得分的操作都先拿该用户的事务级 advisory 锁，避免并发越过每日上限。
package points

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	FirstPost       = 10 // 首发
	Supplement      = 3  // 补充
	Thanks          = 1  // 每次谢谢
	ThanksCap       = 10 // 每条来源谢谢分上限
	DailyCap        = 30 // 每人每天正分上限
	DeletePenalty   = 10 // 被投票删除额外扣分
	SettleDelay     = 24 * time.Hour
	lockNamespace   = 2 // pg_advisory_xact_lock(namespace, user_id)
	settleBatchSize = 500
)

// Shanghai 业务时区，local_day 按它取日期。
var Shanghai = func() *time.Location {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		panic(err)
	}
	return loc
}()

// LocalDay 返回 t 在 Asia/Shanghai 的日期（00:00，UTC 时区表示的纯日期）。
func LocalDay(t time.Time) time.Time {
	y, m, d := t.In(Shanghai).Date()
	return time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
}

// CapDaily 每日上限截断：今天已得 earned 分，再得 delta（正分）时实际记多少。负分不受上限影响。
func CapDaily(earned, delta int) int {
	if delta <= 0 {
		return delta
	}
	room := DailyCap - earned
	if room <= 0 {
		return 0
	}
	if delta > room {
		return room
	}
	return delta
}

// CapThanks 某条来源已累计 sum 分谢谢分时，再谢一次能记多少（0 或 1）。
func CapThanks(sum int) int {
	if sum >= ThanksCap {
		return 0
	}
	return Thanks
}

// Lock 拿 userID 的贡献值锁（事务结束自动释放）。
func Lock(ctx context.Context, tx pgx.Tx, userID int64) error {
	_, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1, $2::int)`, lockNamespace, userID)
	return err
}

// CapReasons 计入每日 30 分上限的流水（正分）。恢复返还的 10 分（restore_refund）和管理员调整（admin_adjust）
// 不计入（issue #8、v2）；/me/points 的 today_earned 用同一口径。
const CapReasons = `('first_post','supplement','thanks','merge_adjust')`

// earnedOn 某人某天已 settled、计入上限的正分。
func earnedOn(ctx context.Context, tx pgx.Tx, userID int64, day time.Time) (int, error) {
	var n int
	err := tx.QueryRow(ctx, `SELECT coalesce(sum(delta), 0) FROM points_ledger
		WHERE user_id = $1 AND local_day = $2 AND status = 'settled' AND delta > 0 AND reason IN `+CapReasons, userID, day).Scan(&n)
	return n, err
}

// AddPending 首发/补充：写 pending 流水，settle_at = 发布时间 + 24h。
// classID/semesterID 取作业所在班级。
func AddPending(ctx context.Context, tx pgx.Tx, userID, classID int64, sourceID int64, reason string, delta int, postedAt time.Time) error {
	_, err := tx.Exec(ctx, `INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day, created_at)
		SELECT $1, c.id, c.semester_id, $3, $4, $5, 'pending', $6, $7, $8 FROM classes c WHERE c.id = $2`,
		userID, classID, sourceID, reason, delta, postedAt.Add(SettleDelay), LocalDay(postedAt.Add(SettleDelay)), postedAt)
	return err
}

// AddThanks 给来源作者记一次谢谢分（立即 settled，计入每日上限，每条来源 ≤ 10）。返回实际记的分。
func AddThanks(ctx context.Context, tx pgx.Tx, authorID, classID, sourceID int64, now time.Time) (int, error) {
	if err := Lock(ctx, tx, authorID); err != nil {
		return 0, err
	}
	var sum int
	if err := tx.QueryRow(ctx, `SELECT coalesce(sum(delta), 0) FROM points_ledger
		WHERE source_id = $1 AND reason = 'thanks' AND status <> 'void'`, sourceID).Scan(&sum); err != nil {
		return 0, err
	}
	day := LocalDay(now)
	earned, err := earnedOn(ctx, tx, authorID, day)
	if err != nil {
		return 0, err
	}
	d := CapDaily(earned, CapThanks(sum))
	_, err = tx.Exec(ctx, `INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day, created_at)
		SELECT $1, c.id, c.semester_id, $3, 'thanks', $4, 'settled', $5, $6, $5 FROM classes c WHERE c.id = $2`,
		authorID, classID, sourceID, d, now, day)
	return d, err
}

// Settle 结算到期的 pending 流水：来源仍在则 settled（按结算日的每日上限截断），来源已删除则 void。
// 返回处理条数。每批一个事务，FOR UPDATE SKIP LOCKED 防多实例重复。
func Settle(ctx context.Context, pool *pgxpool.Pool, now time.Time) (int, error) {
	total := 0
	for {
		n, err := settleBatch(ctx, pool, now)
		total += n
		if err != nil || n < settleBatchSize {
			return total, err
		}
	}
}

func settleBatch(ctx context.Context, pool *pgxpool.Pool, now time.Time) (int, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	rows, err := tx.Query(ctx, `SELECT p.id, p.user_id, p.delta, p.settle_at, s.deleted_at IS NOT NULL
		FROM points_ledger p LEFT JOIN assignment_sources s ON s.id = p.source_id
		WHERE p.status = 'pending' AND p.settle_at <= $1
		ORDER BY p.user_id, p.settle_at, p.id
		LIMIT $2 FOR UPDATE OF p SKIP LOCKED`, now, settleBatchSize)
	if err != nil {
		return 0, err
	}
	type item struct {
		id, user int64
		delta    int
		at       time.Time
		gone     bool
	}
	var items []item
	for rows.Next() {
		var it item
		if err := rows.Scan(&it.id, &it.user, &it.delta, &it.at, &it.gone); err != nil {
			rows.Close()
			return 0, err
		}
		items = append(items, it)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	locked := map[int64]bool{}
	for _, it := range items {
		if it.gone {
			if _, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'void' WHERE id = $1`, it.id); err != nil {
				return 0, err
			}
			continue
		}
		if !locked[it.user] {
			if err := Lock(ctx, tx, it.user); err != nil {
				return 0, err
			}
			locked[it.user] = true
		}
		day := LocalDay(it.at)
		earned, err := earnedOn(ctx, tx, it.user, day)
		if err != nil {
			return 0, err
		}
		if _, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'settled', delta = $2, local_day = $3 WHERE id = $1`,
			it.id, CapDaily(earned, it.delta), day); err != nil {
			return 0, err
		}
	}
	return len(items), tx.Commit(ctx)
}

// VoidSource 作者自删：该来源全部正分流水作废，不扣分。
func VoidSource(ctx context.Context, tx pgx.Tx, sourceID int64) error {
	_, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'void'
		WHERE source_id = $1 AND status <> 'void' AND delta >= 0
		  AND reason IN ('first_post','supplement','thanks')`, sourceID)
	return err
}

// RevokeForDeletion 投票删除：正分流水作废，写 deleted_revoke（展示用，0 分）与 deleted_penalty（-10）。
func RevokeForDeletion(ctx context.Context, tx pgx.Tx, authorID, classID, sourceID int64, now time.Time) error {
	if err := VoidSource(ctx, tx, sourceID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day, created_at)
		SELECT $1, c.id, c.semester_id, $3, r.reason, r.delta, 'settled', $4, $5, $4
		FROM classes c, (VALUES ('deleted_revoke', 0), ('deleted_penalty', $6::int)) AS r(reason, delta)
		WHERE c.id = $2`, authorID, classID, sourceID, now, LocalDay(now), -DeletePenalty)
	return err
}

// RestoreForUndelete 管理员恢复：作废的正分流水恢复（未到结算时间的回到 pending），扣分写 restore_refund 返还。
// 已过结算时间的流水重新按其结算日的每日上限截断（issue #8：删除期间当天可能已被别的得分占满）。
// 同一来源多次删除/恢复时，restore_refund 条数不超过 deleted_penalty 条数。调用方须已拿 Lock(authorID)。
func RestoreForUndelete(ctx context.Context, tx pgx.Tx, authorID, classID, sourceID int64, now time.Time) error {
	if _, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'pending'
		WHERE source_id = $1 AND status = 'void' AND settle_at > $2 AND reason IN ('first_post','supplement','thanks')`, sourceID, now); err != nil {
		return err
	}
	rows, err := tx.Query(ctx, `SELECT id, delta, local_day FROM points_ledger
		WHERE source_id = $1 AND status = 'void' AND settle_at <= $2 AND reason IN ('first_post','supplement','thanks')
		ORDER BY settle_at, id`, sourceID, now)
	if err != nil {
		return err
	}
	type item struct {
		id    int64
		delta int
		day   time.Time
	}
	var items []item
	for rows.Next() {
		var it item
		if err := rows.Scan(&it.id, &it.delta, &it.day); err != nil {
			rows.Close()
			return err
		}
		items = append(items, it)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, it := range items {
		earned, err := earnedOn(ctx, tx, authorID, it.day)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'settled', delta = $2 WHERE id = $1`,
			it.id, CapDaily(earned, it.delta)); err != nil {
			return err
		}
	}
	var penalties, refunds int
	if err := tx.QueryRow(ctx, `SELECT count(*) FILTER (WHERE reason = 'deleted_penalty'), count(*) FILTER (WHERE reason = 'restore_refund')
		FROM points_ledger WHERE source_id = $1 AND status = 'settled'`, sourceID).Scan(&penalties, &refunds); err != nil {
		return err
	}
	if penalties <= refunds {
		return nil
	}
	_, err = tx.Exec(ctx, `INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day, created_at)
		SELECT $1, c.id, c.semester_id, $3, 'restore_refund', $4, 'settled', $5, $6, $5 FROM classes c WHERE c.id = $2`,
		authorID, classID, sourceID, DeletePenalty, now, LocalDay(now))
	return err
}

// AdminAdjust 管理员调整贡献值（v2）：reason=admin_adjust、立即 settled、note 记原因（本人在明细里可见），
// 不受每日上限影响，也不计入上限。
func AdminAdjust(ctx context.Context, tx pgx.Tx, userID, classID int64, delta int, note string, now time.Time) error {
	tag, err := tx.Exec(ctx, `INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day, created_at, note)
		SELECT $1, c.id, c.semester_id, NULL, 'admin_adjust', $3, 'settled', $4, $5, $4, $6 FROM classes c WHERE c.id = $2`,
		userID, classID, delta, now, LocalDay(now), note)
	if err == nil && tag.RowsAffected() == 0 {
		err = pgx.ErrNoRows
	}
	return err
}

// DeleteThreshold 删除投票门槛 = clamp(ceil(成员数 × 10%), 3, 6)。
func DeleteThreshold(members int) int {
	n := (members + 9) / 10
	if n < 3 {
		return 3
	}
	if n > 6 {
		return 6
	}
	return n
}
