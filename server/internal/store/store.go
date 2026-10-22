// Package store 公共的数据库访问：班级装载、当前学期等。SQL 全部参数化。
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Q 是 *pgxpool.Pool 与 pgx.Tx 的公共子集。
type Q interface {
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

type Semester struct {
	ID       int32  `json:"id"`
	Name     string `json:"name"`
	StartsOn string `json:"starts_on"`
	EndsOn   string `json:"ends_on"`
	Archived bool   `json:"archived"`
}

var ErrNoSemester = errors.New("no current semester")

func CurrentSemester(ctx context.Context, q Q) (*Semester, error) {
	var s Semester
	var start, end time.Time
	err := q.QueryRow(ctx, `SELECT id, name, starts_on, ends_on, archived FROM semesters WHERE is_current`).
		Scan(&s.ID, &s.Name, &start, &end, &s.Archived)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNoSemester
	}
	if err != nil {
		return nil, err
	}
	s.StartsOn, s.EndsOn = start.Format("2006-01-02"), end.Format("2006-01-02")
	return &s, nil
}

type Slot struct {
	Room    string `json:"room"`
	Weekday int    `json:"weekday"`
	Start   int    `json:"start"`
	End     int    `json:"end"`
}

type Class struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	Teacher     *string `json:"teacher"`
	Slots       []Slot  `json:"slots"`
	MemberCount int     `json:"member_count"`
}

// LoadClasses 按 ids 顺序装载班级（含时段与在班人数），固定 3 条查询，无 N+1。
func LoadClasses(ctx context.Context, q Q, ids []int64) ([]Class, error) {
	if len(ids) == 0 {
		return []Class{}, nil
	}
	byID := make(map[int64]*Class, len(ids))
	rows, err := q.Query(ctx, `SELECT id, name, teacher FROM classes WHERE id = ANY($1)`, ids)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		c := &Class{Slots: []Slot{}}
		if err := rows.Scan(&c.ID, &c.Name, &c.Teacher); err != nil {
			rows.Close()
			return nil, err
		}
		byID[c.ID] = c
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows, err = q.Query(ctx, `SELECT class_id, room, weekday, start_period, end_period FROM class_slots
		WHERE class_id = ANY($1) ORDER BY weekday, start_period, room`, ids)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id int64
		var s Slot
		if err := rows.Scan(&id, &s.Room, &s.Weekday, &s.Start, &s.End); err != nil {
			rows.Close()
			return nil, err
		}
		if c := byID[id]; c != nil {
			c.Slots = append(c.Slots, s)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows, err = q.Query(ctx, `SELECT class_id, count(*) FROM class_members
		WHERE class_id = ANY($1) AND left_at IS NULL GROUP BY class_id`, ids)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			rows.Close()
			return nil, err
		}
		if c := byID[id]; c != nil {
			c.MemberCount = n
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]Class, 0, len(ids))
	for _, id := range ids {
		if c := byID[id]; c != nil {
			out = append(out, *c)
		}
	}
	return out, nil
}

// IsMember 是否为班级在班成员（供 WS2 等写接口复用）。
func IsMember(ctx context.Context, q Q, classID, userID int64) (bool, error) {
	var ok bool
	err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM class_members
		WHERE class_id = $1 AND user_id = $2 AND left_at IS NULL)`, classID, userID).Scan(&ok)
	return ok, err
}

// ResolveClass 沿 merged_into 找到最终保留的班级 id。
func ResolveClass(ctx context.Context, q Q, id int64) (int64, error) {
	for i := 0; i < 16; i++ {
		var next *int64
		err := q.QueryRow(ctx, `SELECT merged_into FROM classes WHERE id = $1`, id).Scan(&next)
		if err != nil {
			return 0, err
		}
		if next == nil {
			return id, nil
		}
		id = *next
	}
	return id, nil
}

// PgCode 取 PostgreSQL 错误码。
func PgCode(err error) string {
	var pe *pgconn.PgError
	if errors.As(err, &pe) {
		return pe.Code
	}
	return ""
}

// ChatRetention 班级频道只保留最近 30 天；ChatKeepPerClass 每班最多保留最近 500 条（v2）。
const (
	ChatRetention    = 30 * 24 * time.Hour
	ChatKeepPerClass = 500
)

// PurgeMessages 频道保留策略（每天 04:00 由 jobs 调用）：硬删 30 天前的消息，以及每班超出最近 500 条的部分。
func PurgeMessages(ctx context.Context, q Q, now time.Time) (int64, error) {
	a, err := q.Exec(ctx, `DELETE FROM class_messages WHERE created_at < $1`, now.Add(-ChatRetention))
	if err != nil {
		return 0, err
	}
	b, err := q.Exec(ctx, `DELETE FROM class_messages m USING (
			SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY class_id ORDER BY id DESC) AS rn
			                FROM class_messages) x WHERE x.rn > $1) d
		WHERE m.id = d.id`, ChatKeepPerClass)
	if err != nil {
		return 0, err
	}
	return a.RowsAffected() + b.RowsAffected(), nil
}

// ExpireDisables 有期限的封号到期后清空 users.disabled_at（userID = 0 表示全部用户）。
// 只处理有过 disable 处罚、且当前没有生效中的 disable 处罚的账号。
func ExpireDisables(ctx context.Context, q Q, userID int64, now time.Time) error {
	_, err := q.Exec(ctx, `UPDATE users u SET disabled_at = NULL
		WHERE u.disabled_at IS NOT NULL AND ($1 = 0 OR u.id = $1)
		  AND EXISTS (SELECT 1 FROM user_sanctions s WHERE s.user_id = u.id AND s.kind = 'disable')
		  AND NOT EXISTS (SELECT 1 FROM user_sanctions s WHERE s.user_id = u.id AND s.kind = 'disable'
		                  AND s.lifted_at IS NULL AND s.starts_at <= $2 AND (s.ends_at IS NULL OR s.ends_at > $2))`, userID, now)
	return err
}
