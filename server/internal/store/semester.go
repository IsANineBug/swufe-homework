package store

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// SemesterSpec 按日期推出的学期。
// 校历（需求定稿 §四）：秋季学期 9 月 1 日开始、次年 2 月 1 日截止；春季学期 3 月 1 日开始、8 月 1 日截止。
// 2 月、8 月的假期归入即将开始的学期，便于开学前导入课表。
type SemesterSpec struct {
	Name     string // 如 2026-2027-1（秋）、2026-2027-2（春）
	StartsOn time.Time
	EndsOn   time.Time
}

func date(y int, m time.Month, d int) time.Time { return time.Date(y, m, d, 0, 0, 0, 0, time.UTC) }

// SemesterFor 返回 day（业务时区下的日期）所属的学期。
func SemesterFor(day time.Time) SemesterSpec {
	y, m := day.Year(), day.Month()
	switch {
	case m >= time.August: // 8 月假期 + 秋季学期上半段
		return SemesterSpec{fmt.Sprintf("%d-%d-1", y, y+1), date(y, time.September, 1), date(y+1, time.February, 1)}
	case m == time.January: // 秋季学期下半段
		return SemesterSpec{fmt.Sprintf("%d-%d-1", y-1, y), date(y-1, time.September, 1), date(y, time.February, 1)}
	default: // 2 月假期 + 春季学期
		return SemesterSpec{fmt.Sprintf("%d-%d-2", y-1, y), date(y, time.March, 1), date(y, time.August, 1)}
	}
}

// EnsureCurrentSemester 保证 now 所属学期存在且为当前学期；切换时把旧的当前学期归档（只读）。
// 启动时和每小时调用一次，幂等。返回是否发生了切换。
func EnsureCurrentSemester(ctx context.Context, pool *pgxpool.Pool, now time.Time, loc *time.Location) (bool, error) {
	spec := SemesterFor(now.In(loc))
	tx, err := pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(424243)`); err != nil {
		return false, err
	}
	var id int32
	err = tx.QueryRow(ctx, `INSERT INTO semesters (name, starts_on, ends_on) VALUES ($1, $2, $3)
		ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id`,
		spec.Name, spec.StartsOn, spec.EndsOn).Scan(&id)
	if err != nil {
		return false, err
	}
	var curID *int32
	_ = tx.QueryRow(ctx, `SELECT id FROM semesters WHERE is_current`).Scan(&curID)
	if curID != nil && *curID == id {
		return false, nil
	}
	if _, err := tx.Exec(ctx, `UPDATE semesters SET is_current = false, archived = true WHERE is_current AND id <> $1`, id); err != nil {
		return false, err
	}
	if _, err := tx.Exec(ctx, `UPDATE semesters SET is_current = true, archived = false WHERE id = $1`, id); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}
