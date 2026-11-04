package api_test

// 游客浏览 GET /guest/assignments：无需登录、匿名、过滤删除/隐藏/封号、限流、可关闭。需要 TEST_DATABASE_URL。

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/netx"
)

// noCache 换上不缓存样例池的服务器（数据改了马上能看到）。
func noCache(e *hwEnv) *api.Server {
	srv := api.New(e.cfg, e.pool)
	srv.GuestPoolTTL = 0
	e.h = srv.Router()
	return srv
}

func TestGuestAssignments(t *testing.T) {
	e := newHW(t)
	noCache(e)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	keep := e.post(a, cid, "第三章课后习题")
	gone := e.post(b, cid, "小组展示 PPT")
	sup := e.do("POST", "/assignments/"+i64(keep)+"/sources", b, map[string]any{"body": "老师说第 7 题不用做"})
	e.mustCode(sup, 201)

	// 另一个班：之后隐藏，它的作业不能出现
	r := e.do("POST", "/timetable/import", a, map[string]any{"slots": []map[string]any{{"name": "微观经济学", "room": "经世楼G201", "weekday": 2, "start": 3, "end": 4}}})
	e.mustCode(r, 200)
	hiddenClass := int64(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	hiddenA := e.post(a, hiddenClass, "隐藏班级的作业")

	ctx := context.Background()
	if _, err := e.pool.Exec(ctx, `UPDATE assignments SET deleted_at = now() WHERE id = $1`, gone); err != nil {
		t.Fatal(err)
	}
	if _, err := e.pool.Exec(ctx, `UPDATE classes SET hidden_at = now() WHERE id = $1`, hiddenClass); err != nil {
		t.Fatal(err)
	}

	// 不带 token 也能访问
	res := e.do("GET", "/guest/assignments", "", nil)
	e.mustCode(res, 200)
	list := arr(t, res)
	if len(list) != 1 || int64(list[0]["id"].(float64)) != keep {
		t.Fatalf("guest list = %s (want only %d; deleted %d / hidden %d excluded)", res.Raw, keep, gone, hiddenA)
	}
	g := list[0]
	if g["title"] != "第三章课后习题" || g["class_name"] != "概率论" || g["source_count"] != float64(2) {
		t.Fatalf("guest item = %v", g)
	}
	srcs := g["sources"].([]any)
	if len(srcs) != 2 {
		t.Fatalf("sources = %v", srcs)
	}
	first, second := srcs[0].(map[string]any), srcs[1].(map[string]any)
	if first["kind"] != "first" || first["author"] != g["first_poster"] || !strings.HasPrefix(first["author"].(string), "同学") {
		t.Fatalf("first source = %v", first)
	}
	if second["author"] == first["author"] || second["body"] != "老师说第 7 题不用做" {
		t.Fatalf("second source = %v", second)
	}
	// 不泄露昵称、用户 id、个人状态
	raw := string(res.Raw)
	for _, bad := range []string{"甲同学", "乙同学", "author_id", "author_nickname", "is_mine", `"done"`, "thanked_by_me", "url"} {
		if strings.Contains(raw, bad) {
			t.Fatalf("guest response leaks %q: %s", bad, raw)
		}
	}
	if !strings.Contains(raw, `"attachment_count":0`) {
		t.Fatalf("attachment_count missing: %s", raw)
	}

	// 首发者被封号：整条不出现
	if _, err := e.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE nickname = '甲同学'`); err != nil {
		t.Fatal(err)
	}
	if l := arr(t, e.do("GET", "/guest/assignments", "", nil)); len(l) != 0 {
		t.Fatalf("disabled poster still listed: %v", l)
	}
}

func TestGuestAssignmentsLimitAndSwitch(t *testing.T) {
	e := newEnv(t, "")
	srv := api.New(e.cfg, e.pool)
	srv.GuestLimiter = netx.NewLimiter(2, time.Hour)
	e.h = srv.Router()
	e.mustCode(e.do("GET", "/guest/assignments", "", nil), 200)
	e.mustCode(e.do("GET", "/guest/assignments", "", nil), 200)
	e.errCode(e.do("GET", "/guest/assignments", "", nil), 429, "rate_limited")
	// 另一个 IP 不受影响
	e.mustCode(e.do("GET", "/guest/assignments", "", nil, "RemoteAddr", "198.51.100.7:1234"), 200)

	// 空库返回空数组而不是 null
	if r := e.do("GET", "/guest/assignments", "", nil, "RemoteAddr", "198.51.100.8:1234"); string(r.Raw) != "[]\n" && string(r.Raw) != "[]" {
		t.Fatalf("empty body = %q", r.Raw)
	}

	// GUEST_PREVIEW=off
	e.cfg.GuestPreviewOff = true
	e.h = api.New(e.cfg, e.pool).Router()
	e.errCode(e.do("GET", "/guest/assignments", "", nil, "RemoteAddr", "198.51.100.9:1234"), 404, "not_found")
}

// guestByID 取一次游客列表，按作业 id 索引。
func guestByID(t *testing.T, e *hwEnv, hdr ...string) map[int64]map[string]any {
	t.Helper()
	res := e.do("GET", "/guest/assignments", "", nil, hdr...)
	e.mustCode(res, 200)
	m := map[int64]map[string]any{}
	for _, g := range arr(t, res) {
		m[int64(g["id"].(float64))] = g
	}
	return m
}

func guestSrcs(g map[string]any) []map[string]any {
	var out []map[string]any
	for _, x := range g["sources"].([]any) {
		out = append(out, x.(map[string]any))
	}
	return out
}

// 封号作者的补充不出现且不计入 source_count；最多 3 条来源；600 字截断加「…」；代号按作业重新编号；
// 已合并的作业、带附件的来源、往期学期 / 已归档学期的作业都不出现。
func TestGuestAssignmentsFilters(t *testing.T) {
	e := newHW(t)
	noCache(e)
	ctx := context.Background()
	a, aid := e.user("甲")
	b, _ := e.user("乙")
	c, _ := e.user("丙")
	d, _ := e.user("丁")
	f, _ := e.user("戊")
	cid := e.class(a, b, c, d, f)
	sup := func(tok string, asg int64, body string) {
		t.Helper()
		e.mustCode(e.do("POST", "/assignments/"+i64(asg)+"/sources", tok, map[string]any{"body": body}), 201)
	}
	firstSrc := func(asg int64) int64 {
		return e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1 AND kind = 'first'`, asg)
	}
	attach := func(src int64) {
		t.Helper()
		if _, err := e.pool.Exec(ctx, `INSERT INTO attachments (source_id, uploader_id, semester_id, kind, path, bytes)
			SELECT $1, $2, id, 'image', 'x/y.jpg', 100 FROM semesters WHERE is_current`, src, aid); err != nil {
			t.Fatal(err)
		}
	}

	long := strings.Repeat("长", 700)
	one := e.post(a, cid, "第一章习题", "body", long)
	sup(b, one, "乙的补充")
	sup(c, one, "丙的补充（之后封号）")
	sup(d, one, "丁的补充")
	sup(f, one, "戊的补充（超过 3 条，被截掉）")
	two := e.post(b, cid, "第二章习题")
	sup(a, two, "甲在第二章的补充")
	merged := e.post(a, cid, "第二章习题（重复）")
	withAtt := e.post(d, cid, "第四章习题")
	sup(f, withAtt, "戊的纯文字补充")
	attach(firstSrc(withAtt))
	onlyAtt := e.post(f, cid, "第五章拍照作业")
	attach(firstSrc(onlyAtt))
	if _, err := e.pool.Exec(ctx, `UPDATE assignments SET merged_into = $1 WHERE id = $2`, two, merged); err != nil {
		t.Fatal(err)
	}
	if _, err := e.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE nickname = '丙'`); err != nil {
		t.Fatal(err)
	}

	// 往期学期的班：先在当前学期发，再把班挪到一个非当前学期
	r := e.do("POST", "/timetable/import", a, map[string]any{"slots": []map[string]any{{"name": "微观经济学", "room": "经世楼G201", "weekday": 2, "start": 3, "end": 4}}})
	e.mustCode(r, 200)
	oldClass := int64(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	oldAsg := e.post(a, oldClass, "上学期的作业")
	if _, err := e.pool.Exec(ctx, `INSERT INTO semesters (name, starts_on, ends_on, archived) VALUES ('2020-2021-1', '2020-09-01', '2021-02-01', true)`); err != nil {
		t.Fatal(err)
	}
	if _, err := e.pool.Exec(ctx, `UPDATE classes SET semester_id = (SELECT id FROM semesters WHERE name = '2020-2021-1') WHERE id = $1`, oldClass); err != nil {
		t.Fatal(err)
	}

	m := guestByID(t, e)
	if len(m) != 3 || m[one] == nil || m[two] == nil || m[withAtt] == nil {
		t.Fatalf("guest ids = %v (want %d %d %d; merged %d, attachment-only %d, old semester %d excluded)", m, one, two, withAtt, merged, onlyAtt, oldAsg)
	}

	g1 := m[one]
	s1 := guestSrcs(g1)
	if len(s1) != 3 {
		t.Fatalf("source cap: %v", s1)
	}
	// 甲(first)、乙、丁、戊 四条可见；丙已封号，既不展示也不计数
	if g1["source_count"] != float64(4) {
		t.Fatalf("source_count = %v, want 4 (banned author excluded)", g1["source_count"])
	}
	for _, x := range s1 {
		if strings.Contains(x["body"].(string), "丙") || strings.Contains(x["body"].(string), "超过 3 条") {
			t.Fatalf("unexpected source %v", x)
		}
	}
	if want := strings.Repeat("长", 600) + "…"; s1[0]["body"] != want {
		t.Fatalf("truncation: %d runes", len([]rune(s1[0]["body"].(string))))
	}
	if g1["first_poster"] != "同学A" || s1[0]["author"] != "同学A" || s1[1]["author"] != "同学B" || s1[2]["author"] != "同学C" {
		t.Fatalf("labels in #1: %v / %v", g1["first_poster"], s1)
	}

	// 第二条里乙是首发：代号从「同学A」重新开始，甲变成「同学B」，跨作业对不上同一个人
	g2 := m[two]
	s2 := guestSrcs(g2)
	if g2["first_poster"] != "同学A" || len(s2) != 2 || s2[0]["author"] != "同学A" || s2[1]["author"] != "同学B" {
		t.Fatalf("labels in #2: %v / %v", g2["first_poster"], s2)
	}

	// 带附件的首发不展示，只留纯文字补充
	s3 := guestSrcs(m[withAtt])
	if len(s3) != 1 || s3[0]["body"] != "戊的纯文字补充" || s3[0]["attachment_count"] != float64(0) {
		t.Fatalf("attachment source shown: %v", s3)
	}

	// 当前学期归档后整批都不出现
	if _, err := e.pool.Exec(ctx, `UPDATE semesters SET archived = true WHERE is_current`); err != nil {
		t.Fatal(err)
	}
	if m := guestByID(t, e); len(m) != 0 {
		t.Fatalf("archived semester still listed: %v", m)
	}
}

// 缓存池：TTL 内新作业不出现，过期后重抽；池比单次样例大，「换一批」能看到池里不同的作业；并发请求安全。
func TestGuestAssignmentsPoolCache(t *testing.T) {
	e := newHW(t)
	now := time.Now()
	var mu sync.Mutex
	srv := api.New(e.cfg, e.pool)
	srv.Now = func() time.Time { mu.Lock(); defer mu.Unlock(); return now }
	e.h = srv.Router()
	var toks []string
	for i := 0; i < 3; i++ {
		tok, _ := e.user(fmt.Sprintf("用户%d", i))
		toks = append(toks, tok)
	}
	cid := e.class(toks...)
	var posted []int64
	for i := 0; i < 14; i++ { // 每人每班每天最多 5 条
		posted = append(posted, e.post(toks[i%3], cid, fmt.Sprintf("作业 %d", i)))
	}

	seen := map[int64]bool{}
	for i := 0; i < 20; i++ {
		m := guestByID(t, e)
		if len(m) != api.GuestSampleSize {
			t.Fatalf("sample size %d", len(m))
		}
		for id := range m {
			seen[id] = true
		}
	}
	if len(seen) != len(posted) {
		t.Fatalf("pool should rotate through all %d, saw %d", len(posted), len(seen))
	}

	// 删掉一条：池还没过期，仍会出现
	gone := posted[0]
	if _, err := e.pool.Exec(context.Background(), `UPDATE assignments SET deleted_at = now() WHERE id = $1`, gone); err != nil {
		t.Fatal(err)
	}
	stillCached := false
	for i := 0; i < 20 && !stillCached; i++ {
		stillCached = guestByID(t, e)[gone] != nil
	}
	if !stillCached {
		t.Fatal("expected cached pool within TTL")
	}

	// 并发读同一个池
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if r := e.do("GET", "/guest/assignments", "", nil, "RemoteAddr", fmt.Sprintf("198.51.100.%d:1", i+1)); r.Code != 200 {
				t.Errorf("concurrent %d: %d", i, r.Code)
			}
		}(i)
	}
	wg.Wait()

	// TTL 过后重抽，删掉的不再出现
	mu.Lock()
	now = now.Add(api.DefaultGuestPoolTTL + time.Second)
	mu.Unlock()
	for i := 0; i < 10; i++ {
		if guestByID(t, e)[gone] != nil {
			t.Fatal("deleted assignment served after pool expired")
		}
	}
}

// IPv6 按 /64 计数；校园网出口用放宽的限额。
func TestGuestAssignmentsRateKey(t *testing.T) {
	e := newEnv(t, "203.0.113.0/24")
	srv := api.New(e.cfg, e.pool)
	srv.GuestLimiter = netx.NewLimiter(2, time.Hour)
	srv.GuestCampusLimiter = netx.NewLimiter(4, time.Hour)
	e.h = srv.Router()
	get := func(addr string) int { return e.do("GET", "/guest/assignments", "", nil, "RemoteAddr", addr).Code }

	// 同一个 /64 里换地址不能绕过限额
	if get("[2001:db8:1:2::1]:1") != 200 || get("[2001:db8:1:2:ffff::9]:1") != 200 {
		t.Fatal("first two in /64 should pass")
	}
	if c := get("[2001:db8:1:2:abcd::5]:1"); c != 429 {
		t.Fatalf("third in same /64 = %d, want 429", c)
	}
	if c := get("[2001:db8:1:3::1]:1"); c != 200 {
		t.Fatalf("other /64 = %d", c)
	}

	// 校园网出口：限额 4 而不是 2
	for i := 0; i < 4; i++ {
		if c := get("203.0.113.7:1"); c != 200 {
			t.Fatalf("campus request %d = %d", i+1, c)
		}
	}
	if c := get("203.0.113.7:1"); c != 429 {
		t.Fatalf("campus over limit = %d", c)
	}
}
