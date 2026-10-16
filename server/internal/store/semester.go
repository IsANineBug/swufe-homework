package store

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// SemesterSpec 一个学期的名称与起止日期。
// 来源（issue #4 裁决 12）：设置了 SEMESTER_* 环境变量时以其为准；否则按校历推算（SemesterFor）。
// 校历（需求定稿 §四）：秋季学期 9 月 1 日开始、次年 2 月 1 日截止；春季学期 3 月 1 日开始、8 月 1 日截止。
// 2 月、8 月的假期归入即将开始的学期，便于开学前导入课表。
type SemesterSpec struct {
	Name     string // 如 2026-2027-1（秋）、2026-2027-2（春）
	StartsOn time.Time
	EndsOn   time.Time
	// Explicit 来自 SEMESTER_* 环境变量：同名学期已存在时以它的起止日期为准（覆盖按校历推算的日期）
	Explicit bool
}

func date(y int, m time.Month, d int) time.Time { return time.Date(y, m, d, 0, 0, 0, 0, time.UTC) }

// SemesterFor 返回 day（业务时区下的日期）所属的学期。
func SemesterFor(day time.Time) SemesterSpec {
	y, m := day.Year(), day.Month()
	switch {
	case m >= time.August: // 8 月假期 + 秋季学期上半段
		return SemesterSpec{fmt.Sprintf("%d-%d-1", y, y+1), date(y, time.September, 1), date(y+1, time.February, 1), false}
	case m == time.January: // 秋季学期下半段
		return SemesterSpec{fmt.Sprintf("%d-%d-1", y-1, y), date(y-1, time.September, 1), date(y, time.February, 1), false}
	default: // 2 月假期 + 春季学期
		return SemesterSpec{fmt.Sprintf("%d-%d-2", y-1, y), date(y, time.March, 1), date(y, time.August, 1), false}
	}
}

// EnsureCurrentSemester 保证 spec 学期存在且为当前学期；切换时把旧的当前学期归档（只读）。
// 启动时和每小时调用一次，幂等。返回是否发生了切换。
func EnsureCurrentSemester(ctx context.Context, pool *pgxpool.Pool, spec SemesterSpec) (bool, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(424243)`); err != nil {
		return false, err
	}
	var id int32
	err = tx.QueryRow(ctx, `INSERT INTO semesters (name, starts_on, ends_on) VALUES ($1, $2, $3)
		ON CONFLICT (name) DO UPDATE SET
		  starts_on = CASE WHEN $4 THEN EXCLUDED.starts_on ELSE semesters.starts_on END,
		  ends_on   = CASE WHEN $4 THEN EXCLUDED.ends_on ELSE semesters.ends_on END
		RETURNING id`,
		spec.Name, spec.StartsOn, spec.EndsOn, spec.Explicit).Scan(&id)
	if err != nil {
		return false, err
	}
	var curID *int32
	_ = tx.QueryRow(ctx, `SELECT id FROM semesters WHERE is_current`).Scan(&curID)
	if curID != nil && *curID == id {
		return false, tx.Commit(ctx) // 提交可能的日期更新（Explicit）
	}
	if _, err := tx.Exec(ctx, `UPDATE semesters SET is_current = false, archived = true WHERE is_current AND id <> $1`, id); err != nil {
		return false, err
	}
	if _, err := tx.Exec(ctx, `UPDATE semesters SET is_current = true, archived = false WHERE id = $1`, id); err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}
