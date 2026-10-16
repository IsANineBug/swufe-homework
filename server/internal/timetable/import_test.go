package timetable_test

import (
	"context"
	"fmt"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/testdb"
	"github.com/IsANineBug/swufe-homework/server/internal/timetable"
)

func imp(t *testing.T, pool *pgxpool.Pool, uid int64, slots ...timetable.SlotInput) *timetable.Result {
	t.Helper()
	v, err := timetable.Validate(slots)
	if err != nil {
		t.Fatal(err)
	}
	res, err := timetable.Import(context.Background(), pool, uid, v)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func slot(name, room string, wd, s, e int) timetable.SlotInput {
	return timetable.SlotInput{Name: name, Room: room, Weekday: wd, Start: s, End: e}
}

func count(t *testing.T, pool *pgxpool.Pool, q string, args ...any) int {
	t.Helper()
	var n int
	if err := pool.QueryRow(context.Background(), q, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

const prob = "概率论与数理统计"

// 概率论周一+周五：同一人同名课合成一个班；另一人只有周一（截图裁掉周五）也加入同一班。
func TestImportMonFriAndCroppedScreenshot(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")

	r1 := imp(t, pool, a, slot(prob, "@经世楼G101", 1, 1, 2), slot(prob, "经世楼 G102", 5, 3, 4))
	if len(r1.Created) != 1 || len(r1.Joined) != 0 || len(r1.Created[0].Slots) != 2 {
		t.Fatalf("r1 = %+v", r1)
	}
	cid := r1.Created[0].ID

	// b 的截图裁掉了周五那段，课名也带全角空格
	r2 := imp(t, pool, b, slot("概率论与数理　统计", "经世楼Ｇ１０１", 1, 1, 2))
	if len(r2.Joined) != 1 || r2.Joined[0].ID != cid || len(r2.Created) != 0 {
		t.Fatalf("r2 = %+v", r2)
	}
	if r2.Joined[0].MemberCount != 2 || len(r2.Joined[0].Slots) != 2 {
		t.Fatalf("joined class = %+v", r2.Joined[0])
	}
	if n := count(t, pool, `SELECT count(*) FROM classes`); n != 1 {
		t.Fatalf("classes = %d", n)
	}
}

// 加入时补全该班缺的时段；课名被截断的也能命中，且完整课名会回填。
func TestImportFillsMissingSlotsAndTruncatedName(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")

	r1 := imp(t, pool, a, timetable.SlotInput{Name: "概率论与数…", Room: "经世楼G101", Weekday: 1, Start: 1, End: 2, Truncated: true})
	cid := r1.Created[0].ID
	r2 := imp(t, pool, b, slot(prob, "经世楼G101", 1, 1, 2), slot(prob, "经世楼G102", 5, 3, 4))
	if len(r2.Joined) != 1 || r2.Joined[0].ID != cid {
		t.Fatalf("r2 = %+v", r2)
	}
	c := r2.Joined[0]
	if c.Name != prob || len(c.Slots) != 2 {
		t.Fatalf("class = %+v", c)
	}
}

// 同教室、同课名，但在周二另一个时间：一段都对不上 → 另一个教学班。
func TestImportSameRoomOtherDayIsAnotherClass(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")
	r1 := imp(t, pool, a, slot(prob, "经世楼G101", 1, 1, 2), slot(prob, "经世楼G102", 5, 3, 4))
	r2 := imp(t, pool, b, slot(prob, "经世楼G101", 2, 1, 2))
	if len(r2.Created) != 1 || r2.Created[0].ID == r1.Created[0].ID {
		t.Fatalf("r2 = %+v", r2)
	}
	if n := count(t, pool, `SELECT count(*) FROM classes`); n != 2 {
		t.Fatalf("classes = %d", n)
	}
}

// 一个人的时段同时命中两个已有班 → 两班自动合并到较早的班，成员、时段、作业一起并过去。
func TestImportAutoMergesTwoClasses(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")
	c := testdb.User(t, pool, "c")

	r1 := imp(t, pool, a, slot("微观经济学", "通博楼B203", 3, 1, 2))
	r2 := imp(t, pool, b, slot("微观经济学", "通博楼B204", 4, 3, 4))
	older, newer := r1.Created[0].ID, r2.Created[0].ID
	if older == newer {
		t.Fatal("should be two classes before merge")
	}
	// newer 班里已经有一条作业
	var aid int64
	if err := pool.QueryRow(ctx, `INSERT INTO assignments (class_id, title, ddl, created_by)
		VALUES ($1, '习题一', now() + interval '3 days', $2) RETURNING id`, newer, b).Scan(&aid); err != nil {
		t.Fatal(err)
	}

	r3 := imp(t, pool, c, slot("微观经济学", "通博楼B203", 3, 1, 2), slot("微观经济学", "通博楼B204", 4, 3, 4))
	if len(r3.Merged) != 1 || r3.Merged[0].From != newer || r3.Merged[0].Into != older {
		t.Fatalf("merged = %+v", r3.Merged)
	}
	if len(r3.Joined) != 1 || r3.Joined[0].ID != older {
		t.Fatalf("joined = %+v", r3.Joined)
	}
	if got := r3.Joined[0]; got.MemberCount != 3 || len(got.Slots) != 2 {
		t.Fatalf("merged class = %+v", got)
	}
	var into *int64
	if err := pool.QueryRow(ctx, `SELECT merged_into FROM classes WHERE id = $1`, newer).Scan(&into); err != nil || into == nil || *into != older {
		t.Fatalf("merged_into = %v err %v", into, err)
	}
	if n := count(t, pool, `SELECT count(*) FROM assignments WHERE id = $1 AND class_id = $2`, aid, older); n != 1 {
		t.Fatal("assignment not moved")
	}
	if n := count(t, pool, `SELECT count(*) FROM class_members WHERE class_id = $1 AND left_at IS NULL`, newer); n != 0 {
		t.Fatal("old class still has active members")
	}
}

// 时段重叠但课名不同（多为识别错误）→ conflicts，该组不入库；其他组照常。
func TestImportConflictDifferentName(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")
	r1 := imp(t, pool, a, slot(prob, "经世楼G101", 1, 1, 2))
	r2 := imp(t, pool, b, slot("高等数学", "经世楼G101", 1, 2, 3), slot("高等数学", "经世楼G301", 3, 1, 2), slot("会计学", "通博楼B101", 2, 1, 2))
	if len(r2.Conflicts) != 1 || r2.Conflicts[0].ExistingClass.ID != r1.Created[0].ID {
		t.Fatalf("conflicts = %+v", r2.Conflicts)
	}
	if len(r2.Created) != 1 || r2.Created[0].Name != "会计学" {
		t.Fatalf("created = %+v", r2.Created)
	}
	if n := count(t, pool, `SELECT count(*) FROM classes WHERE name = '高等数学'`); n != 0 {
		t.Fatal("conflicting group should not be stored")
	}
}

// 重复导入幂等。
func TestImportIdempotent(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	imp(t, pool, a, slot(prob, "经世楼G101", 1, 1, 2))
	r := imp(t, pool, a, slot(prob, "经世楼G101", 1, 1, 2))
	if len(r.Joined) != 1 || r.Joined[0].MemberCount != 1 || len(r.Joined[0].Slots) != 1 {
		t.Fatalf("r = %+v", r)
	}
}

// 并发导入同一门新课：依赖 EXCLUDE 约束 + 重试，最终只有一个班。
func TestImportConcurrent(t *testing.T) {
	pool := testdb.New(t)
	const n = 8
	ids := make([]int64, n)
	for i := range ids {
		ids[i] = testdb.User(t, pool, fmt.Sprintf("u%d", i))
	}
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for _, uid := range ids {
		wg.Add(1)
		go func(uid int64) {
			defer wg.Done()
			v, _ := timetable.Validate([]timetable.SlotInput{slot("线性代数", "经世楼G201", 2, 3, 4), slot("线性代数", "经世楼G202", 4, 3, 4)})
			if _, err := timetable.Import(context.Background(), pool, uid, v); err != nil {
				errs <- err
			}
		}(uid)
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	if c := count(t, pool, `SELECT count(*) FROM classes WHERE merged_into IS NULL`); c != 1 {
		t.Fatalf("active classes = %d", c)
	}
	if m := count(t, pool, `SELECT count(*) FROM class_members m JOIN classes c ON c.id = m.class_id
		WHERE c.merged_into IS NULL AND m.left_at IS NULL`); m != n {
		t.Fatalf("members = %d", m)
	}
}

// 阻塞项回归：截断课名的一组同时命中两个不同全名的班 → 不合并，按冲突返回，该组不入库。
func TestImportTruncatedHitsTwoDifferentClassesNoMerge(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")
	c := testdb.User(t, pool, "c")
	eng := imp(t, pool, a, slot("大学英语", "经世楼A101", 1, 1, 2)).Created[0].ID
	phy := imp(t, pool, b, slot("大学物理", "经世楼A102", 2, 3, 4)).Created[0].ID

	tr := func(room string, wd, s, e int) timetable.SlotInput {
		return timetable.SlotInput{Name: "大学…", Room: room, Weekday: wd, Start: s, End: e, Truncated: true}
	}
	r := imp(t, pool, c, tr("经世楼A101", 1, 1, 2), tr("经世楼A102", 2, 3, 4), slot("会计学", "通博楼B101", 3, 1, 2))
	if len(r.Merged) != 0 {
		t.Fatalf("must not merge: %+v", r.Merged)
	}
	if len(r.Conflicts) != 2 {
		t.Fatalf("conflicts = %+v", r.Conflicts)
	}
	got := map[int64]bool{}
	for _, cf := range r.Conflicts {
		got[cf.ExistingClass.ID] = true
	}
	if !got[eng] || !got[phy] {
		t.Fatalf("conflicts should name both classes: %+v", r.Conflicts)
	}
	if len(r.Created) != 1 || r.Created[0].Name != "会计学" || len(r.Joined) != 0 {
		t.Fatalf("r = %+v", r)
	}
	if n := count(t, pool, `SELECT count(*) FROM classes WHERE merged_into IS NOT NULL`); n != 0 {
		t.Fatal("no class should be merged")
	}
	if n := count(t, pool, `SELECT count(*) FROM class_members WHERE user_id = $1 AND class_id IN ($2, $3)`, c, eng, phy); n != 0 {
		t.Fatal("ambiguous group should not join any class")
	}
}

// 截断课名只命中唯一一个同名班 → 照常加入。
func TestImportTruncatedJoinsSingleClass(t *testing.T) {
	pool := testdb.New(t)
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")
	eng := imp(t, pool, a, slot("大学英语", "经世楼A101", 1, 1, 2)).Created[0].ID
	r := imp(t, pool, b, timetable.SlotInput{Name: "大学…", Room: "经世楼A101", Weekday: 1, Start: 1, End: 2, Truncated: true})
	if len(r.Joined) != 1 || r.Joined[0].ID != eng || len(r.Conflicts) != 0 {
		t.Fatalf("r = %+v", r)
	}
}

// 「不是我的班」退出后，再次导入不自动加回（issue #4 裁决 11），返回在 skipped 里。
func TestImportDoesNotRejoinLeftClass(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	a := testdb.User(t, pool, "a")
	cid := imp(t, pool, a, slot(prob, "经世楼G101", 1, 1, 2)).Created[0].ID
	if _, err := pool.Exec(ctx, `UPDATE class_members SET left_at = now() WHERE class_id = $1 AND user_id = $2`, cid, a); err != nil {
		t.Fatal(err)
	}
	r := imp(t, pool, a, slot(prob, "经世楼G101", 1, 1, 2))
	if len(r.Joined) != 0 || len(r.Skipped) != 1 || r.Skipped[0].ID != cid {
		t.Fatalf("r = %+v", r)
	}
	if n := count(t, pool, `SELECT count(*) FROM class_members WHERE class_id = $1 AND user_id = $2 AND left_at IS NULL`, cid, a); n != 0 {
		t.Fatal("should stay left")
	}
}

// 自动合并在 class_merges 留下撤销数据。
func TestImportMergeRecordsUndoSnapshot(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	a := testdb.User(t, pool, "a")
	b := testdb.User(t, pool, "b")
	c := testdb.User(t, pool, "c")
	older := imp(t, pool, a, slot("微观经济学", "通博楼B203", 3, 1, 2)).Created[0].ID
	newer := imp(t, pool, b, slot("微观经济学", "通博楼B204", 4, 3, 4)).Created[0].ID
	imp(t, pool, c, slot("微观经济学", "通博楼B203", 3, 1, 2), slot("微观经济学", "通博楼B204", 4, 3, 4))

	var from, into, by int64
	var slots, members, intoMembers int
	var intoName string
	err := pool.QueryRow(ctx, `SELECT from_class_id, into_class_id, triggered_by,
		jsonb_array_length(snapshot->'slot_ids'), jsonb_array_length(snapshot->'from_members'),
		jsonb_array_length(snapshot->'into_members'), snapshot->>'into_name'
		FROM class_merges`).Scan(&from, &into, &by, &slots, &members, &intoMembers, &intoName)
	if err != nil {
		t.Fatal(err)
	}
	if from != newer || into != older || by != c || slots != 1 || members != 1 || intoMembers != 1 || intoName != "微观经济学" {
		t.Fatalf("snapshot from=%d into=%d by=%d slots=%d members=%d into_members=%d name=%s", from, into, by, slots, members, intoMembers, intoName)
	}
}
