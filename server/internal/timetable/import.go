package timetable

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"sort"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

// SlotInput 与 api.yaml components.schemas.SlotInput 一致。
type SlotInput struct {
	Name      string  `json:"name"`
	Room      string  `json:"room"`
	Weekday   int     `json:"weekday"`
	Start     int     `json:"start"`
	End       int     `json:"end"`
	Truncated bool    `json:"truncated"`
	Teacher   *string `json:"teacher"`
}

type Merged struct {
	From int64 `json:"from"`
	Into int64 `json:"into"`
}

type Conflict struct {
	Slot          SlotInput   `json:"slot"`
	ExistingClass store.Class `json:"existing_class"`
}

type Result struct {
	Joined    []store.Class `json:"joined"`
	Created   []store.Class `json:"created"`
	Merged    []Merged      `json:"merged"`
	Conflicts []Conflict    `json:"conflicts"`
	// Skipped 之前点过「不是我的班」的班：本次不自动加回（issue #4 裁决 11），可在搜索里手动加入
	Skipped []store.Class `json:"skipped"`
}

// ValidationError 输入不合法。
type ValidationError struct{ Msg string }

func (e *ValidationError) Error() string { return e.Msg }

const MaxSlots = 60

// Validate 校验并规范化输入（课名、教室）。
func Validate(in []SlotInput) ([]SlotInput, error) {
	if len(in) == 0 {
		return nil, &ValidationError{"课表为空"}
	}
	if len(in) > MaxSlots {
		return nil, &ValidationError{fmt.Sprintf("一次最多导入 %d 个时段", MaxSlots)}
	}
	out := make([]SlotInput, 0, len(in))
	for i, s := range in {
		s.Name = CleanName(s.Name)
		if s.Truncated {
			s.Name = StripEllipsis(s.Name)
		}
		s.Room = NormalizeRoom(s.Room)
		if s.Teacher != nil {
			t := CleanName(*s.Teacher)
			if t == "" {
				s.Teacher = nil
			} else {
				s.Teacher = &t
			}
		}
		switch {
		case s.Name == "" || utf8.RuneCountInString(s.Name) > 60:
			return nil, &ValidationError{fmt.Sprintf("第 %d 条课名为空或过长", i+1)}
		case s.Room == "" || utf8.RuneCountInString(s.Room) > 40:
			return nil, &ValidationError{fmt.Sprintf("第 %d 条教室为空或过长", i+1)}
		case s.Weekday < 1 || s.Weekday > 5:
			return nil, &ValidationError{fmt.Sprintf("第 %d 条星期须为 1–5", i+1)}
		case s.Start < 1 || s.End > 12 || s.Start > s.End:
			return nil, &ValidationError{fmt.Sprintf("第 %d 条节次须在 1–12 且起节不晚于止节", i+1)}
		}
		out = append(out, s)
	}
	return out, nil
}

// group 是本次提交中同名的一组时段（规则 1）。
type group struct {
	name      string
	truncated bool // 组内所有课名都被截断
	teacher   *string
	slots     []SlotInput
}

// Group 按课名分组（规则 1），结果与输入顺序无关。
//   - 完整课名按 NameKey 分组；
//   - 截断课名：与某完整课名 NameKey 相同，或恰好是唯一一个完整课名的前缀 → 并入该组；
//     能匹配多个不同的完整课名时有歧义，不自动归组，按截断名单独成组（issue #4 裁决 10）；
//   - 其余截断课名按 NameKey 相同归组。
func Group(slots []SlotInput) []*group {
	var groups []*group
	full := map[string]*group{}
	var fullKeys []string
	for _, s := range slots {
		if s.Truncated {
			continue
		}
		k := NameKey(s.Name)
		if full[k] == nil {
			full[k] = &group{name: s.Name}
			fullKeys = append(fullKeys, k)
			groups = append(groups, full[k])
		}
	}
	trunc := map[string]*group{}
	pick := func(s SlotInput) *group {
		if !s.Truncated {
			return full[NameKey(s.Name)]
		}
		k := NameKey(s.Name)
		if g := full[k]; g != nil {
			return g
		}
		var match *group
		n := 0
		for _, fk := range fullKeys {
			if SameName(s.Name, true, full[fk].name, false) {
				match = full[fk]
				n++
			}
		}
		if n == 1 {
			return match
		}
		if trunc[k] == nil {
			trunc[k] = &group{name: s.Name, truncated: true}
			groups = append(groups, trunc[k])
		}
		return trunc[k]
	}
	for _, s := range slots {
		g := pick(s)
		if g.teacher == nil && s.Teacher != nil {
			g.teacher = s.Teacher
		}
		g.slots = append(g.slots, s)
	}
	for _, g := range groups {
		sort.SliceStable(g.slots, func(i, j int) bool {
			a, b := g.slots[i], g.slots[j]
			if a.Weekday != b.Weekday {
				return a.Weekday < b.Weekday
			}
			if a.Start != b.Start {
				return a.Start < b.Start
			}
			return a.Room < b.Room
		})
	}
	return groups
}

// ambiguousHits 命中多个班时，判断能否安全地自动合并（issue #4 裁决 10）：
//   - 本组课名是截断的：只能用于加入唯一一个同名班，命中的班课名（NameKey）必须完全相同；
//   - 本组课名完整：命中的班必须两两同名。
//
// 不满足即有歧义，按 conflicts 返回，不合并、不入库。
func ambiguousHits(g *group, hits map[int64]existing) bool {
	if len(hits) < 2 {
		return false
	}
	hs := make([]existing, 0, len(hits))
	for _, e := range hits {
		hs = append(hs, e)
	}
	for i := range hs {
		for j := i + 1; j < len(hs); j++ {
			if g.truncated {
				if NameKey(hs[i].name) != NameKey(hs[j].name) {
					return true
				}
			} else if !SameName(hs[i].name, hs[i].truncated, hs[j].name, hs[j].truncated) {
				return true
			}
		}
	}
	return false
}

type existing struct {
	classID   int64
	name      string
	truncated bool
}

var ErrNoSemester = errors.New("当前没有可导入的学期")

// errRetry 表示读到了并发事务刚提交的数据，需要整体重试。
var errRetry = errors.New("timetable import: retry")

// Import 在一个事务里完成归班。EXCLUDE 约束冲突（并发导入同一时段）或死锁时整体重试。
func Import(ctx context.Context, pool *pgxpool.Pool, userID int64, slots []SlotInput) (*Result, error) {
	var res *Result
	var err error
	for attempt := 0; attempt < 8; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Duration(attempt*10+rand.IntN(20)) * time.Millisecond):
			}
		}
		res, err = importOnce(ctx, pool, userID, slots)
		if err == nil {
			return res, nil
		}
		if errors.Is(err, errRetry) {
			continue
		}
		switch store.PgCode(err) {
		case "23P01", "40P01", "40001", "23505": // exclusion / deadlock / serialization / unique
			continue
		}
		return nil, err
	}
	return nil, err
}

func importOnce(ctx context.Context, pool *pgxpool.Pool, userID int64, slots []SlotInput) (*Result, error) {
	tx, err := pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	sem, err := store.CurrentSemester(ctx, tx)
	if errors.Is(err, store.ErrNoSemester) {
		return nil, ErrNoSemester
	}
	if err != nil {
		return nil, err
	}
	if sem.Archived {
		return nil, ErrNoSemester
	}

	var joinedIDs, createdIDs []int64
	res := &Result{Merged: []Merged{}, Conflicts: []Conflict{}}
	var skippedIDs []int64
	type pendingConflict struct {
		slot    SlotInput
		classID int64
	}
	var pcs []pendingConflict

	for _, g := range Group(slots) {
		hits := map[int64]existing{}
		var gConf, gHits []pendingConflict
		for _, s := range g.slots {
			ov, err := overlapping(ctx, tx, sem.ID, s)
			if err != nil {
				return nil, err
			}
			for _, e := range ov {
				if SameName(g.name, g.truncated, e.name, e.truncated) {
					hits[e.classID] = e
					gHits = append(gHits, pendingConflict{s, e.classID})
				} else {
					gConf = append(gConf, pendingConflict{s, e.classID})
				}
			}
		}
		if len(gConf) > 0 { // 时段重叠但课名不同：该组不入库
			pcs = append(pcs, gConf...)
			continue
		}
		if ambiguousHits(g, hits) { // 截断课名命中多个不同名的班：不自动合并，该组不入库
			pcs = append(pcs, gHits...)
			continue
		}
		// 本人点过「不是我的班」的班（issue #4 裁决 11）：这一组完全不动——不合并、不补时段、
		// 不改课名和教师，只把这些班放进 skipped。hits 来自 merged_into IS NULL 的现存班。
		if len(hits) > 0 {
			ids := make([]int64, 0, len(hits))
			for id := range hits {
				ids = append(ids, id)
			}
			rows, err := tx.Query(ctx, `SELECT class_id FROM class_members
				WHERE user_id = $1 AND class_id = ANY($2) AND left_at IS NOT NULL ORDER BY class_id`, userID, ids)
			if err != nil {
				return nil, err
			}
			left, err := pgx.CollectRows(rows, pgx.RowTo[int64])
			if err != nil {
				return nil, err
			}
			if len(left) > 0 {
				skippedIDs = append(skippedIDs, left...)
				continue
			}
		}

		var target int64
		switch len(hits) {
		case 0: // 规则 3：新建
			err = tx.QueryRow(ctx, `INSERT INTO classes (semester_id, name, teacher, name_truncated, created_by)
				VALUES ($1, $2, $3, $4, $5) RETURNING id`, sem.ID, g.name, g.teacher, g.truncated, userID).Scan(&target)
			if err != nil {
				return nil, err
			}
			createdIDs = append(createdIDs, target)
		default:
			ids := make([]int64, 0, len(hits))
			for id := range hits {
				ids = append(ids, id)
			}
			// 规则 2/4：命中一个直接加入；命中多个则并到最早创建的班
			target, err = mergeClasses(ctx, tx, ids, userID, res)
			if err != nil {
				return nil, err
			}
			joinedIDs = append(joinedIDs, target)
		}

		// 补全该班缺的时段：与库里任何时段都不重叠的才插入
		for _, s := range g.slots {
			ov, err := overlapping(ctx, tx, sem.ID, s)
			if err != nil {
				return nil, err
			}
			if len(ov) > 0 {
				for _, e := range ov {
					if e.classID != target {
						// 并发：别人刚提交了重叠时段，整个事务重来
						return nil, errRetry
					}
				}
				continue
			}
			if _, err := tx.Exec(ctx, `INSERT INTO class_slots (class_id, semester_id, room, weekday, start_period, end_period)
				VALUES ($1, $2, $3, $4, $5, $6)`, target, sem.ID, s.Room, s.Weekday, s.Start, s.End); err != nil {
				return nil, err
			}
		}
		// 课名补全、教师补填
		if !g.truncated {
			if _, err := tx.Exec(ctx, `UPDATE classes SET name = $2, name_truncated = false
				WHERE id = $1 AND name_truncated`, target, g.name); err != nil {
				return nil, err
			}
		}
		if g.teacher != nil {
			if _, err := tx.Exec(ctx, `UPDATE classes SET teacher = $2 WHERE id = $1 AND teacher IS NULL`, target, *g.teacher); err != nil {
				return nil, err
			}
		}
		// 加入。合并后保留班里若有本人的退班记录（来自被并入的班），同样不自动加回
		var left bool
		err = tx.QueryRow(ctx, `WITH ins AS (
				INSERT INTO class_members (class_id, user_id) VALUES ($1, $2)
				ON CONFLICT (class_id, user_id) DO NOTHING RETURNING 1)
			SELECT EXISTS (SELECT 1 FROM class_members WHERE class_id = $1 AND user_id = $2 AND left_at IS NOT NULL)`,
			target, userID).Scan(&left)
		if err != nil {
			return nil, err
		}
		if left {
			skippedIDs = append(skippedIDs, target)
		}
	}

	// 装载返回值。冲突里的班级可能在本次后续步骤中被合并，按最终班级装载。
	all := append(append(append([]int64{}, joinedIDs...), createdIDs...), skippedIDs...)
	for i := range pcs {
		id, err := store.ResolveClass(ctx, tx, pcs[i].classID)
		if err != nil {
			return nil, err
		}
		pcs[i].classID = id
		all = append(all, id)
	}
	classes, err := store.LoadClasses(ctx, tx, uniq(all))
	if err != nil {
		return nil, err
	}
	byID := map[int64]store.Class{}
	for _, c := range classes {
		byID[c.ID] = c
	}
	pick := func(ids []int64) []store.Class {
		out := []store.Class{}
		for _, id := range uniq(ids) {
			if c, ok := byID[id]; ok {
				out = append(out, c)
			}
		}
		return out
	}
	// 被合并掉的班不会出现在 joined；created 若随后被合并也改按最终班返回
	for i, id := range joinedIDs {
		if r, err := store.ResolveClass(ctx, tx, id); err == nil {
			joinedIDs[i] = r
		}
	}
	skipped := map[int64]bool{}
	for i, id := range skippedIDs {
		if r, err := store.ResolveClass(ctx, tx, id); err == nil {
			skippedIDs[i] = r
		}
		skipped[skippedIDs[i]] = true
	}
	var joinedKeep []int64
	for _, id := range joinedIDs {
		if !skipped[id] {
			joinedKeep = append(joinedKeep, id)
		}
	}
	res.Joined = pick(joinedKeep)
	res.Skipped = pick(skippedIDs)
	res.Created = pick(createdIDs)
	for _, pc := range pcs {
		res.Conflicts = append(res.Conflicts, Conflict{Slot: pc.slot, ExistingClass: byID[pc.classID]})
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return res, nil
}

// overlapping 返回与该时段（教室+星期+节次）重叠的现存班级。
func overlapping(ctx context.Context, tx pgx.Tx, semID int32, s SlotInput) ([]existing, error) {
	rows, err := tx.Query(ctx, `SELECT DISTINCT c.id, c.name, c.name_truncated
		FROM class_slots cs JOIN classes c ON c.id = cs.class_id
		WHERE cs.semester_id = $1 AND cs.room = $2 AND cs.weekday = $3
		  AND int4range(cs.start_period, cs.end_period, '[]') && int4range($4, $5, '[]')
		  AND c.merged_into IS NULL
		ORDER BY c.id`, semID, s.Room, s.Weekday, s.Start, s.End)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []existing
	for rows.Next() {
		var e existing
		if err := rows.Scan(&e.classID, &e.name, &e.truncated); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// mergeClasses 把 ids 中的班并到最早创建的那个（成员、时段、作业、积分流水、禁发记录），
// 旧班 merged_into 指向保留班。只有一个 id 时直接返回。
// 每次合并先在 class_merges 记下搬动的行和合并前状态，供管理员撤销（issue #4 裁决 10）。
func mergeClasses(ctx context.Context, tx pgx.Tx, ids []int64, userID int64, res *Result) (int64, error) {
	rows, err := tx.Query(ctx, `SELECT id, name, name_truncated FROM classes WHERE id = ANY($1)
		ORDER BY created_at, id FOR UPDATE`, ids)
	if err != nil {
		return 0, err
	}
	type cls struct {
		id    int64
		name  string
		trunc bool
	}
	var cs []cls
	for rows.Next() {
		var c cls
		if err := rows.Scan(&c.id, &c.name, &c.trunc); err != nil {
			rows.Close()
			return 0, err
		}
		cs = append(cs, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	if len(cs) == 0 {
		return 0, errors.New("merge: classes not found")
	}
	keep := cs[0]
	for _, from := range cs[1:] {
		if _, err := tx.Exec(ctx, mergeSnapshotSQL, from.id, keep.id, userID); err != nil {
			return 0, fmt.Errorf("merge snapshot %d into %d: %w", from.id, keep.id, err)
		}
		stmts := []string{
			`UPDATE class_slots SET class_id = $2 WHERE class_id = $1`,
			// 成员：任一班在班即在班
			`INSERT INTO class_members (class_id, user_id, joined_at, left_at)
			 SELECT $2, user_id, joined_at, left_at FROM class_members WHERE class_id = $1
			 ON CONFLICT (class_id, user_id) DO UPDATE SET
			   joined_at = LEAST(class_members.joined_at, EXCLUDED.joined_at),
			   left_at = CASE WHEN class_members.left_at IS NULL OR EXCLUDED.left_at IS NULL
			                  THEN NULL ELSE GREATEST(class_members.left_at, EXCLUDED.left_at) END`,
			`UPDATE class_members SET left_at = now() WHERE class_id = $1 AND left_at IS NULL AND $2::bigint IS NOT NULL`,
			`UPDATE assignments SET class_id = $2 WHERE class_id = $1`,
			`UPDATE points_ledger SET class_id = $2 WHERE class_id = $1`,
			`UPDATE post_bans SET class_id = $2 WHERE class_id = $1`,
			`UPDATE classes SET merged_into = $2 WHERE merged_into = $1`,
			`UPDATE classes SET merged_into = $2 WHERE id = $1`,
		}
		for _, q := range stmts {
			if _, err := tx.Exec(ctx, q, from.id, keep.id); err != nil {
				return 0, fmt.Errorf("merge %d into %d: %w", from.id, keep.id, err)
			}
		}
		if keep.trunc && !from.trunc {
			if _, err := tx.Exec(ctx, `UPDATE classes SET name = $2, name_truncated = false WHERE id = $1`, keep.id, from.name); err != nil {
				return 0, err
			}
			keep.name, keep.trunc = from.name, false
		}
		if _, err := tx.Exec(ctx, `UPDATE classes SET teacher = (SELECT teacher FROM classes WHERE id = $1)
			WHERE id = $2 AND teacher IS NULL`, from.id, keep.id); err != nil {
			return 0, err
		}
		res.Merged = append(res.Merged, Merged{From: from.id, Into: keep.id})
	}
	return keep.id, nil
}

func uniq(ids []int64) []int64 {
	seen := map[int64]bool{}
	out := make([]int64, 0, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// mergeSnapshotSQL 记录一次班级合并的撤销数据；字段说明见 db/schema.sql 的 class_merges。
const mergeSnapshotSQL = `INSERT INTO class_merges (from_class_id, into_class_id, triggered_by, snapshot)
SELECT $1, $2, $3, jsonb_build_object(
  'slot_ids', coalesce((SELECT jsonb_agg(id ORDER BY id) FROM class_slots WHERE class_id = $1), '[]'),
  'assignment_ids', coalesce((SELECT jsonb_agg(id ORDER BY id) FROM assignments WHERE class_id = $1), '[]'),
  'points_ledger_ids', coalesce((SELECT jsonb_agg(id ORDER BY id) FROM points_ledger WHERE class_id = $1), '[]'),
  'post_ban_ids', coalesce((SELECT jsonb_agg(id ORDER BY id) FROM post_bans WHERE class_id = $1), '[]'),
  'child_class_ids', coalesce((SELECT jsonb_agg(id ORDER BY id) FROM classes WHERE merged_into = $1), '[]'),
  'from_members', coalesce((SELECT jsonb_agg(jsonb_build_object('user_id', user_id, 'joined_at', joined_at, 'left_at', left_at)
                   ORDER BY user_id) FROM class_members WHERE class_id = $1), '[]'),
  'into_members', coalesce((SELECT jsonb_agg(jsonb_build_object('user_id', user_id, 'joined_at', joined_at, 'left_at', left_at)
                   ORDER BY user_id) FROM class_members WHERE class_id = $2), '[]'),
  'into_name', c.name, 'into_name_truncated', c.name_truncated, 'into_teacher', c.teacher)
FROM classes c WHERE c.id = $2`
