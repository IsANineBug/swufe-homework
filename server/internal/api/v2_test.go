package api_test

// WS8（v2）集成测试：频道（限频边界、保留策略、可见性）、隐藏班级、管理员删除与恢复、处罚（禁言/禁发/封号）、
// 举报、贡献值调整、小字段、admin_actions 全覆盖、AdminUser/AdminReport JSON 快照。需要 TEST_DATABASE_URL。

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

var updateSnapshots = flag.Bool("update", false, "重写 testdata 下的 JSON 快照")

// clock 可调的测试时钟（频道限频按服务器时间计）。
type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) Add(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

// newV2 同 newHW，但服务器用可调时钟。
func newV2(t *testing.T) (*hwEnv, *clock) {
	e := newHW(t)
	c := &clock{t: time.Now()}
	srv := api.New(e.cfg, e.pool)
	srv.Now = c.Now
	e.h = srv.Router()
	return e, c
}

func (e *hwEnv) userWithRefresh(nick string) (access, refresh string, id int64) {
	e.t.Helper()
	r := e.do("POST", "/auth/register", "", reg(nick, nil))
	e.mustCode(r, 201)
	access, refresh = r.Body["access_token"].(string), r.Body["refresh_token"].(string)
	me := e.do("GET", "/me", access, nil)
	return access, refresh, int64(me.Body["id"].(float64))
}

func (e *hwEnv) actions(action string) int64 {
	e.t.Helper()
	return e.scalar(`SELECT count(*) FROM admin_actions WHERE action = $1`, action)
}

func msgPath(cid int64) string { return "/classes/" + i64(cid) + "/messages" }

func TestChatRateLimitBoundaries(t *testing.T) {
	e, clk := newV2(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	// 另一个班：限频是所有班合计
	r := e.do("POST", "/timetable/import", a, map[string]any{"slots": []map[string]any{{"name": "线性代数", "room": "经世楼G102", "weekday": 2, "start": 1, "end": 2}}})
	e.mustCode(r, 200)
	cid2 := int64(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))

	e.mustCode(e.do("POST", msgPath(cid), a, map[string]any{"body": "第 1 条"}), 201)
	// 第 2 条在 10 秒内被拒（换个班也一样），Retry-After 给出剩余秒数
	clk.Add(9 * time.Second)
	r = e.do("POST", msgPath(cid2), a, map[string]any{"body": "太快了"})
	e.errCode(r, 429, "chat_rate_limited")
	clk.Add(time.Second) // 正好 10 秒：放行
	e.mustCode(e.do("POST", msgPath(cid2), a, map[string]any{"body": "第 2 条"}), 201)
	for i := 3; i <= 30; i++ {
		clk.Add(10 * time.Second)
		e.mustCode(e.do("POST", msgPath(cid), a, map[string]any{"body": "第 " + strconv.Itoa(i) + " 条"}), 201)
	}
	// 第 31 条（一小时内）被拒；等到第 1 条满一小时后放行
	clk.Add(10 * time.Second)
	r = e.do("POST", msgPath(cid), a, map[string]any{"body": "第 31 条"})
	e.errCode(r, 429, "chat_rate_limited")
	// 第 1 条发于 T0，现在 T0+300s，第 1 条在 T0+3600s 滑出窗口
	if r.Code == 429 {
		if got := e.lastRetryAfter(msgPath(cid), a); got != 3300 {
			t.Fatalf("Retry-After = %d, want 3300", got)
		}
	}
	clk.Add(3300 * time.Second)
	e.mustCode(e.do("POST", msgPath(cid), a, map[string]any{"body": "第 31 条"}), 201)

	// 正文校验
	clk.Add(time.Minute)
	e.errCode(e.do("POST", msgPath(cid), b, map[string]any{"body": "   "}), 400, "bad_body")
	e.errCode(e.do("POST", msgPath(cid), b, map[string]any{"body": strings.Repeat("字", 301)}), 400, "bad_body")
	e.mustCode(e.do("POST", msgPath(cid), b, map[string]any{"body": strings.Repeat("字", 300)}), 201)
}

// lastRetryAfter 再发一次（预期 429）取 Retry-After 秒数。
func (e *hwEnv) lastRetryAfter(path, tok string) int {
	e.t.Helper()
	req := newReq("POST", "/api/v1"+path, tok, map[string]any{"body": "x"})
	w := serve(e.h, req)
	if w.Code != 429 {
		e.t.Fatalf("status %d, want 429", w.Code)
	}
	n, err := strconv.Atoi(w.Header().Get("Retry-After"))
	if err != nil {
		e.t.Fatalf("Retry-After = %q", w.Header().Get("Retry-After"))
	}
	return n
}

func TestChatVisibilityPagingDeleteMute(t *testing.T) {
	e, clk := newV2(t)
	a, _ := e.user("甲同学")
	b, bID := e.user("乙同学")
	out, _ := e.user("路人")
	cid := e.class(a, b)
	var ids []int64
	for i := 0; i < 5; i++ {
		clk.Add(11 * time.Second)
		r := e.do("POST", msgPath(cid), a, map[string]any{"body": "  消息 " + strconv.Itoa(i) + "  "})
		e.mustCode(r, 201)
		if r.Body["body"] != "消息 "+strconv.Itoa(i) || r.Body["is_mine"] != true || r.Body["author_nickname"] != "甲同学" {
			t.Fatalf("post = %s", r.Raw)
		}
		ids = append(ids, int64(r.Body["id"].(float64)))
	}
	// 非成员、管理员都看不到
	e.errCode(e.do("GET", msgPath(cid), out, nil), 403, "not_member")
	e.errCode(e.do("GET", msgPath(cid), e.admin, nil), 403, "not_member")
	e.errCode(e.do("POST", msgPath(cid), out, map[string]any{"body": "hi"}), 403, "not_member")
	e.errCode(e.do("POST", msgPath(cid), e.admin, map[string]any{"body": "hi"}), 403, "not_member")

	r := e.do("GET", msgPath(cid)+"?limit=2", b, nil)
	e.mustCode(r, 200)
	items := r.Body["items"].([]any)
	if len(items) != 2 || int64(items[0].(map[string]any)["id"].(float64)) != ids[3] || items[1].(map[string]any)["is_mine"] != false || r.Body["muted_until"] != nil {
		t.Fatalf("latest = %s", r.Raw)
	}
	r = e.do("GET", msgPath(cid)+"?before_id="+i64(ids[3])+"&limit=2", b, nil)
	if items := r.Body["items"].([]any); len(items) != 2 || int64(items[0].(map[string]any)["id"].(float64)) != ids[1] {
		t.Fatalf("before = %s", r.Raw)
	}
	r = e.do("GET", msgPath(cid)+"?after_id="+i64(ids[2]), b, nil)
	if items := r.Body["items"].([]any); len(items) != 2 || int64(items[0].(map[string]any)["id"].(float64)) != ids[3] {
		t.Fatalf("after = %s", r.Raw)
	}
	e.errCode(e.do("GET", msgPath(cid)+"?limit=51", b, nil), 400, "bad_request")

	// /me/classes 的 last_message_id
	mc := arr(t, e.do("GET", "/me/classes", b, nil))
	if len(mc) != 1 || int64(mc[0]["last_message_id"].(float64)) != ids[4] {
		t.Fatalf("me/classes = %v", mc)
	}
	// 作者删除（幂等），非作者 403；删除的不再返回，last_message_id 退回上一条
	e.errCode(e.do("DELETE", "/messages/"+i64(ids[4]), b, nil), 403, "forbidden")
	e.mustCode(e.do("DELETE", "/messages/"+i64(ids[4]), a, nil), 204)
	e.mustCode(e.do("DELETE", "/messages/"+i64(ids[4]), a, nil), 204)
	r = e.do("GET", msgPath(cid)+"?after_id="+i64(ids[3]), b, nil)
	if items := r.Body["items"].([]any); len(items) != 0 {
		t.Fatalf("deleted message returned: %s", r.Raw)
	}
	mc = arr(t, e.do("GET", "/me/classes", b, nil))
	if int64(mc[0]["last_message_id"].(float64)) != ids[3] {
		t.Fatalf("last_message_id after delete = %v", mc[0]["last_message_id"])
	}

	// 禁言：发消息 403 muted；GET 给出 muted_until；/me 的 restrictions
	r = e.do("POST", "/admin/users/"+i64(bID)+"/sanctions", e.admin, map[string]any{"kind": "mute", "days": 3, "reason": "刷屏"})
	e.mustCode(r, 201)
	if r.Body["kind"] != "mute" || r.Body["ends_at"] == nil || r.Body["lifted_at"] != nil {
		t.Fatalf("sanction = %s", r.Raw)
	}
	sid := int64(r.Body["id"].(float64))
	clk.Add(time.Minute)
	e.errCode(e.do("POST", msgPath(cid), b, map[string]any{"body": "我被禁言了吗"}), 403, "muted")
	r = e.do("GET", msgPath(cid), b, nil)
	if r.Body["muted_until"] == nil {
		t.Fatalf("muted_until missing: %s", r.Raw)
	}
	me := e.do("GET", "/me", b, nil)
	rs := me.Body["restrictions"].([]any)
	if len(rs) != 1 || rs[0].(map[string]any)["kind"] != "mute" || rs[0].(map[string]any)["reason"] != "刷屏" {
		t.Fatalf("restrictions = %s", me.Raw)
	}
	if e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'sanction'`, bID) != 1 {
		t.Fatal("sanction notice missing")
	}
	e.errCode(e.do("POST", "/admin/users/"+i64(bID)+"/sanctions", e.admin, map[string]any{"kind": "mute", "reason": "x"}), 400, "bad_request")
	e.mustCode(e.do("POST", "/admin/sanctions/"+i64(sid)+"/lift", e.admin, nil), 204)
	e.mustCode(e.do("POST", "/admin/sanctions/"+i64(sid)+"/lift", e.admin, nil), 204)
	e.mustCode(e.do("POST", msgPath(cid), b, map[string]any{"body": "解除了"}), 201)
	if rs := e.do("GET", "/me", b, nil).Body["restrictions"].([]any); len(rs) != 0 {
		t.Fatalf("restrictions after lift = %v", rs)
	}
}

func TestChatRetention(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	cid := e.class(a)
	ctx := context.Background()
	now := time.Now()
	// 1 条 31 天前 + 505 条近期
	if _, err := e.pool.Exec(ctx, `INSERT INTO class_messages (class_id, user_id, body, created_at) VALUES ($1, $2, '很久以前', $3)`,
		cid, aID, now.Add(-31*24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, err := e.pool.Exec(ctx, `INSERT INTO class_messages (class_id, user_id, body, created_at)
		SELECT $1, $2, '近期 ' || g, $3::timestamptz + g * interval '1 second' FROM generate_series(1, 505) g`, cid, aID, now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	n, err := store.PurgeMessages(ctx, e.pool, now)
	if err != nil {
		t.Fatal(err)
	}
	if n != 6 {
		t.Fatalf("purged = %d, want 6", n)
	}
	if got := e.scalar(`SELECT count(*) FROM class_messages WHERE class_id = $1`, cid); got != 500 {
		t.Fatalf("kept = %d", got)
	}
	if got := e.scalar(`SELECT count(*) FROM class_messages WHERE body = '很久以前' OR body IN ('近期 1','近期 5')`); got != 0 {
		t.Fatalf("old messages kept: %d", got)
	}
	if got := e.scalar(`SELECT count(*) FROM class_messages WHERE body = '近期 6'`); got != 1 {
		t.Fatal("newest 500 should be kept")
	}
}

func TestHiddenClassEverywhere(t *testing.T) {
	e, clk := newV2(t)
	a, aID := e.user("甲同学")
	b, _ := e.user("乙同学")
	c, _ := e.user("丙同学")
	cid := e.class(a, b)
	aid := e.post(a, cid, "第三章课后习题")
	since := url.QueryEscape(time.Now().Add(-time.Second).Format(time.RFC3339))
	time.Sleep(10 * time.Millisecond)

	e.errCode(e.do("POST", "/admin/classes/"+i64(cid)+"/hide", e.admin, map[string]any{"reason": ""}), 400, "bad_request")
	e.errCode(e.do("POST", "/admin/classes/"+i64(cid)+"/hide", a, map[string]any{"reason": "垃圾"}), 403, "forbidden")
	e.mustCode(e.do("POST", "/admin/classes/"+i64(cid)+"/hide", e.admin, map[string]any{"reason": "测试班"}), 204)
	e.mustCode(e.do("POST", "/admin/classes/"+i64(cid)+"/hide", e.admin, map[string]any{"reason": "测试班"}), 204) // 幂等
	if n := e.actions("class_hide"); n != 1 {
		t.Fatalf("class_hide actions = %d", n)
	}
	if n := e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'class_hidden' AND payload->>'class_name' = '概率论'`, aID); n != 1 {
		t.Fatalf("class_hidden notices = %d", n)
	}
	if mc := arr(t, e.do("GET", "/me/classes", a, nil)); len(mc) != 0 {
		t.Fatalf("me/classes = %v", mc)
	}
	if s := arr(t, e.do("GET", "/classes/search?q="+url.QueryEscape("概率"), c, nil)); len(s) != 0 {
		t.Fatalf("search = %v", s)
	}
	if f := arr(t, e.do("GET", "/feed", a, nil)); len(f) != 0 {
		t.Fatalf("feed = %v", f)
	}
	r := e.do("GET", "/sync?since="+since, a, nil)
	e.mustCode(r, 200)
	if rm := r.Body["removed_ids"].([]any); len(rm) != 1 || int64(rm[0].(float64)) != aid || len(r.Body["changed"].([]any)) != 0 {
		t.Fatalf("sync = %s", r.Raw)
	}
	if r := e.do("GET", "/sync", a, nil); len(r.Body["changed"].([]any)) != 0 {
		t.Fatalf("full sync = %s", r.Raw)
	}
	e.errCode(e.do("GET", "/assignments/"+i64(aid), a, nil), 404, "not_found")
	e.errCode(e.do("GET", "/classes/"+i64(cid), a, nil), 404, "not_found")
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/join", c, nil), 404, "not_found")
	e.mustCode(e.do("GET", "/assignments/"+i64(aid), e.admin, nil), 200) // 管理员仍可查看
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "x", "ddl": ddlIn(time.Hour)}), 409, "class_archived")
	clk.Add(time.Minute)
	e.errCode(e.do("POST", msgPath(cid), a, map[string]any{"body": "还在吗"}), 409, "class_archived")
	e.errCode(e.do("GET", msgPath(cid), a, nil), 409, "class_archived")
	// 导入归班不命中隐藏班：新建一个班，并接过这个时段
	r = e.do("POST", "/timetable/import", c, probSlot)
	e.mustCode(r, 200)
	if len(r.Body["created"].([]any)) != 1 || len(r.Body["joined"].([]any)) != 0 {
		t.Fatalf("import = %s", r.Raw)
	}
	newID := int64(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	if n := e.scalar(`SELECT count(*) FROM class_slots WHERE class_id = $1`, newID); n != 1 {
		t.Fatalf("new class slots = %d", n)
	}

	// 恢复：重新出现在 /me/classes 与 /sync
	since2 := url.QueryEscape(time.Now().Add(-time.Second).Format(time.RFC3339))
	time.Sleep(10 * time.Millisecond)
	e.mustCode(e.do("POST", "/admin/classes/"+i64(cid)+"/unhide", e.admin, nil), 204)
	if n := e.actions("class_unhide"); n != 1 {
		t.Fatalf("class_unhide actions = %d", n)
	}
	if mc := arr(t, e.do("GET", "/me/classes", b, nil)); len(mc) != 1 || int64(mc[0]["id"].(float64)) != cid {
		t.Fatalf("me/classes after unhide = %v", mc)
	}
	r = e.do("GET", "/sync?since="+since2, a, nil)
	if ch := r.Body["changed"].([]any); len(ch) != 1 || int64(ch[0].(map[string]any)["id"].(float64)) != aid {
		t.Fatalf("sync after unhide = %s", r.Raw)
	}
	if f := arr(t, e.do("GET", "/feed", a, nil)); len(f) != 1 {
		t.Fatalf("feed after unhide = %v", f)
	}
}

func TestAdminDeleteSourcePenalizeAndRestore(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	aid1 := e.post(a, cid, "作业一")
	aid2 := e.post(a, cid, "作业二")
	s1 := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid1)
	s2 := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid2)
	// 让首发分到账，便于比较
	e.exec(`UPDATE points_ledger SET status = 'settled', settle_at = now() - interval '1 hour' WHERE user_id = $1`, aID)
	total := func() int64 {
		return e.scalar(`SELECT coalesce(sum(delta), 0) FROM points_ledger WHERE user_id = $1 AND status = 'settled'`, aID)
	}
	if got := total(); got != 20 {
		t.Fatalf("start total = %d", got)
	}
	e.errCode(e.do("DELETE", "/admin/sources/"+i64(s1), e.admin, map[string]any{"reason": " "}), 400, "bad_request")
	e.errCode(e.do("DELETE", "/admin/sources/"+i64(s1), b, map[string]any{"reason": "x"}), 403, "forbidden")

	// penalize 默认 true：收回 +10 并 -10
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(s1), e.admin, map[string]any{"reason": "虚假作业"}), 204)
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(s1), e.admin, map[string]any{"reason": "虚假作业"}), 204) // 幂等
	if got := total(); got != 0 {
		t.Fatalf("after penalize delete total = %d, want 0", got)
	}
	if n := e.scalar(`SELECT count(*) FROM assignment_sources WHERE id = $1 AND deleted_reason = 'admin'`, s1); n != 1 {
		t.Fatal("deleted_reason should be admin")
	}
	if n := e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'content_deleted' AND payload->>'reason' = '虚假作业'`, aID); n != 1 {
		t.Fatalf("content_deleted notice = %d", n)
	}
	e.errCode(e.do("GET", "/assignments/"+i64(aid1), b, nil), 404, "not_found")

	// penalize=false：只收回不罚
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(s2), e.admin, map[string]any{"reason": "重复", "penalize": false}), 204)
	if got := total(); got != -10 {
		t.Fatalf("after non-penalize delete total = %d, want -10", got)
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE source_id = $1 AND reason = 'deleted_penalty'`, s2); n != 0 {
		t.Fatal("penalize=false must not penalize")
	}
	if n := e.actions("source_delete"); n != 2 {
		t.Fatalf("source_delete actions = %d", n)
	}
	// /admin/deleted 也列出管理员删除的
	del := arr(t, e.do("GET", "/admin/deleted", e.admin, nil))
	if len(del) != 2 || del[0]["deleted_reason"] != "admin" {
		t.Fatalf("admin deleted = %v", del)
	}

	// 恢复：penalize 的返还 10 分；不罚的不写 restore_refund
	e.mustCode(e.do("POST", "/admin/sources/"+i64(s1)+"/restore", e.admin, nil), 204)
	if got := total(); got != 10 {
		t.Fatalf("after restore s1 total = %d, want 10", got)
	}
	e.mustCode(e.do("POST", "/admin/sources/"+i64(s2)+"/restore", e.admin, nil), 204)
	if got := total(); got != 20 {
		t.Fatalf("after restore s2 total = %d, want 20", got)
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE user_id = $1 AND reason = 'restore_refund'`, aID); n != 1 {
		t.Fatalf("refunds = %d", n)
	}
	if n := e.actions("source_restore"); n != 2 {
		t.Fatalf("source_restore actions = %d", n)
	}
	e.mustCode(e.do("GET", "/assignments/"+i64(aid1), b, nil), 200)
}

// 管理员删除（penalize）计入 30 天禁发次数；penalize=false 不计。
func TestAdminDeleteCountsStrikes(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	cid := e.class(a)
	var srcs []int64
	for i := 0; i < 4; i++ {
		aid := e.post(a, cid, "作业"+string(rune('A'+i)))
		srcs = append(srcs, e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid))
	}
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(srcs[0]), e.admin, map[string]any{"reason": "x", "penalize": false}), 204)
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(srcs[1]), e.admin, map[string]any{"reason": "x"}), 204)
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(srcs[2]), e.admin, map[string]any{"reason": "x"}), 204)
	if n := e.scalar(`SELECT count(*) FROM post_bans WHERE user_id = $1`, aID); n != 0 {
		t.Fatal("non-penalized delete must not count as a strike")
	}
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(srcs[3]), e.admin, map[string]any{"reason": "x"}), 204)
	if n := e.scalar(`SELECT count(*) FROM post_bans WHERE user_id = $1 AND lifted_at IS NULL`, aID); n != 1 {
		t.Fatalf("bans = %d", n)
	}
}

func TestDisableBlocksTokens(t *testing.T) {
	e, _ := newV2(t)
	access, refresh, id := e.userWithRefresh("坏人")
	e.mustCode(e.do("GET", "/me", access, nil), 200)
	e.errCode(e.do("POST", "/admin/users/"+i64(id)+"/sanctions", e.admin, map[string]any{"kind": "disable", "days": 0, "reason": "x"}), 400, "bad_request")
	r := e.do("POST", "/admin/users/"+i64(id)+"/sanctions", e.admin, map[string]any{"kind": "disable", "reason": "广告"})
	e.mustCode(r, 201)
	if r.Body["ends_at"] != nil {
		t.Fatalf("permanent disable ends_at = %v", r.Body["ends_at"])
	}
	sid := int64(r.Body["id"].(float64))
	// 现有 access token 立即被拒；登录与续期 403 account_disabled
	e.errCode(e.do("GET", "/me", access, nil), 403, "account_disabled")
	e.errCode(e.do("POST", "/auth/login", "", map[string]any{"nickname": "坏人", "password": "password123"}), 403, "account_disabled")
	e.errCode(e.do("POST", "/auth/login", "", map[string]any{"nickname": "坏人", "password": "wrong-password"}), 401, "bad_credentials")
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]any{"refresh_token": refresh}), 403, "account_disabled")
	if n := e.scalar(`SELECT count(*) FROM auth_tokens WHERE user_id = $1 AND revoked_at IS NULL`, id); n != 0 {
		t.Fatalf("live refresh tokens = %d", n)
	}
	// 不能处罚管理员
	adminID := int64(e.do("GET", "/me", e.admin, nil).Body["id"].(float64))
	e.errCode(e.do("POST", "/admin/users/"+i64(adminID)+"/sanctions", e.admin, map[string]any{"kind": "disable", "reason": "x"}), 403, "forbidden")
	// 解除后可以重新登录
	e.mustCode(e.do("POST", "/admin/sanctions/"+i64(sid)+"/lift", e.admin, nil), 204)
	e.mustCode(e.do("POST", "/auth/login", "", map[string]any{"nickname": "坏人", "password": "password123"}), 200)

	// 有期限的封号到期后登录时自动解封
	r = e.do("POST", "/admin/users/"+i64(id)+"/sanctions", e.admin, map[string]any{"kind": "disable", "days": 1, "reason": "冷静一天"})
	e.mustCode(r, 201)
	e.errCode(e.do("POST", "/auth/login", "", map[string]any{"nickname": "坏人", "password": "password123"}), 403, "account_disabled")
	e.exec(`UPDATE user_sanctions SET starts_at = now() - interval '2 days', ends_at = now() - interval '1 day' WHERE user_id = $1 AND lifted_at IS NULL`, id)
	e.mustCode(e.do("POST", "/auth/login", "", map[string]any{"nickname": "坏人", "password": "password123"}), 200)
}

func TestGlobalPostBan(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	cid := e.class(a)
	aid := e.post(a, cid, "第一条")
	e.mustCode(e.do("POST", "/admin/users/"+i64(aID)+"/sanctions", e.admin, map[string]any{"kind": "post_ban", "days": 7, "reason": "乱发"}), 201)
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "新的", "ddl": ddlIn(time.Hour)}), 403, "post_banned")
	e.errCode(e.do("POST", "/assignments/"+i64(aid)+"/sources", a, map[string]any{"body": "补充"}), 403, "post_banned")
	me := e.do("GET", "/me", a, nil)
	if rs := me.Body["restrictions"].([]any); len(rs) != 1 || rs[0].(map[string]any)["kind"] != "post_ban" {
		t.Fatalf("restrictions = %s", me.Raw)
	}
}

func TestAdminAdjustPoints(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	cid := e.class(a)
	e.errCode(e.do("POST", "/admin/users/"+i64(aID)+"/points", e.admin, map[string]any{"class_id": cid, "delta": 0, "reason": "x"}), 400, "bad_request")
	e.errCode(e.do("POST", "/admin/users/"+i64(aID)+"/points", e.admin, map[string]any{"class_id": cid, "delta": 101, "reason": "x"}), 400, "bad_request")
	e.errCode(e.do("POST", "/admin/users/"+i64(aID)+"/points", e.admin, map[string]any{"class_id": cid, "delta": 5, "reason": ""}), 400, "bad_request")
	e.mustCode(e.do("POST", "/admin/users/"+i64(aID)+"/points", e.admin, map[string]any{"class_id": cid, "delta": 40, "reason": "整理全班课表"}), 201)
	r := e.do("GET", "/me/points", a, nil)
	e.mustCode(r, 200)
	items := r.Body["items"].([]any)
	it := items[0].(map[string]any)
	if it["reason"] != "admin_adjust" || it["note"] != "整理全班课表" || it["delta"] != float64(40) || it["status"] != "settled" {
		t.Fatalf("points = %s", r.Raw)
	}
	// 不计入每日 30 上限：today_earned 仍为 0，之后的谢谢分照常到账
	if r.Body["today_earned"] != float64(0) || r.Body["semester_total"] != float64(40) {
		t.Fatalf("summary = %s", r.Raw)
	}
	if n := e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'points_adjusted' AND (payload->>'delta')::int = 40`, aID); n != 1 {
		t.Fatal("points_adjusted notice missing")
	}
	if n := e.actions("points_adjust"); n != 1 {
		t.Fatalf("points_adjust actions = %d", n)
	}
}

func TestSmallFieldsIsMineFirstPoster(t *testing.T) {
	e, _ := newV2(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	aid := e.post(a, cid, "第三章")
	e.mustCode(e.do("POST", "/assignments/"+i64(aid)+"/sources", b, map[string]any{"body": "补充"}), 201)
	da := e.do("GET", "/assignments/"+i64(aid), a, nil)
	db := e.do("GET", "/assignments/"+i64(aid), b, nil)
	if da.Body["is_first_poster"] != true || db.Body["is_first_poster"] != false {
		t.Fatalf("is_first_poster a=%v b=%v", da.Body["is_first_poster"], db.Body["is_first_poster"])
	}
	srcA := da.Body["sources"].([]any)
	if srcA[0].(map[string]any)["is_mine"] != true || srcA[1].(map[string]any)["is_mine"] != false {
		t.Fatalf("is_mine (a) = %s", da.Raw)
	}
	// 改昵称后仍按 id 判断
	e.mustCode(e.do("PATCH", "/me", a, map[string]any{"nickname": "乙同学二号"}), 200)
	da = e.do("GET", "/assignments/"+i64(aid), a, nil)
	if da.Body["is_first_poster"] != true || da.Body["sources"].([]any)[0].(map[string]any)["is_mine"] != true {
		t.Fatalf("after rename = %s", da.Raw)
	}
}

func TestReportsFlow(t *testing.T) {
	e, clk := newV2(t)
	a, aID := e.user("甲同学")
	b, bID := e.user("乙同学")
	c, _ := e.user("丙同学")
	out, outID := e.user("路人")
	cid := e.class(a, b, c)
	aid := e.post(a, cid, "第三章")
	src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid)
	var msgs []int64
	for i := 0; i < 12; i++ {
		clk.Add(11 * time.Second)
		r := e.do("POST", msgPath(cid), a, map[string]any{"body": "消息" + strconv.Itoa(i)})
		e.mustCode(r, 201)
		msgs = append(msgs, int64(r.Body["id"].(float64)))
	}
	rep := func(tok, kind string, id int64, reason string) resp {
		return e.do("POST", "/reports", tok, map[string]any{"target_kind": kind, "target_id": id, "reason": reason})
	}
	e.mustCode(rep(b, "message", msgs[6], "骂人"), 201)
	e.errCode(rep(b, "message", msgs[6], "骂人"), 409, "already_reported")
	e.mustCode(rep(c, "message", msgs[6], "确实骂人"), 201)
	e.errCode(rep(out, "message", msgs[6], "看不到"), 403, "not_member")
	e.errCode(rep(out, "user", aID, "看不到"), 403, "forbidden")
	e.errCode(rep(b, "user", bID, "自己"), 400, "bad_request")
	e.errCode(rep(b, "source", src, ""), 400, "bad_request")
	e.errCode(rep(b, "source", src, strings.Repeat("长", 201)), 400, "bad_request")
	e.errCode(rep(b, "message", 999999, "x"), 404, "not_found")
	e.mustCode(rep(b, "source", src, "虚假作业"), 201)
	e.mustCode(rep(b, "class", cid, "垃圾班"), 201)
	e.mustCode(rep(b, "user", aID, "昵称违规"), 201)
	_ = outID

	// 每人每天 20 条（按 Asia/Shanghai 日期）：c 今天已有 1 条，再补 19 条
	e.exec(`INSERT INTO reports (reporter_id, target_kind, target_id, reason, created_at)
		SELECT (SELECT id FROM users WHERE nickname = '丙同学'), 'message', 900000 + g, '刷', $1 FROM generate_series(1, 19) g`, clk.Now())
	e.errCode(rep(c, "message", msgs[0], "第 21 条"), 429, "rate_limited")
	e.exec(`DELETE FROM reports WHERE target_id > 900000`)

	// 普通用户不能看举报
	e.errCode(e.do("GET", "/admin/reports", b, nil), 403, "forbidden")
	list := arr(t, e.do("GET", "/admin/reports", e.admin, nil))
	if len(list) != 4 {
		t.Fatalf("open reports = %v", list)
	}
	var msgRow map[string]any
	for _, x := range list {
		if x["target_kind"] == "message" {
			msgRow = x
		}
	}
	if msgRow == nil || len(msgRow["reporters"].([]any)) != 2 || msgRow["reporters"].([]any)[0].(map[string]any)["nickname"] != "乙同学" {
		t.Fatalf("message row = %v", msgRow)
	}
	tg := msgRow["target"].(map[string]any)
	ctxMsgs := tg["context"].([]any)
	if tg["body"] != "消息6" || tg["author_nickname"] != "甲同学" || tg["class_name"] != "概率论" || len(ctxMsgs) != 10 ||
		int64(ctxMsgs[0].(map[string]any)["id"].(float64)) != msgs[1] || int64(ctxMsgs[9].(map[string]any)["id"].(float64)) != msgs[11] {
		t.Fatalf("message target = %v", tg)
	}
	repID := int64(msgRow["id"].(float64))
	e.errCode(e.do("POST", "/admin/reports/"+i64(repID)+"/resolve", e.admin, map[string]any{"resolution": "nope"}), 400, "bad_request")
	e.mustCode(e.do("POST", "/admin/reports/"+i64(repID)+"/resolve", e.admin, map[string]any{"resolution": "removed"}), 204)
	e.errCode(e.do("POST", "/admin/reports/"+i64(repID)+"/resolve", e.admin, map[string]any{"resolution": "removed"}), 409, "report_closed")
	if n := e.scalar(`SELECT count(*) FROM reports WHERE target_kind = 'message' AND handled_at IS NULL`); n != 0 {
		t.Fatalf("open message reports after resolve = %d", n)
	}
	if l := arr(t, e.do("GET", "/admin/reports", e.admin, nil)); len(l) != 3 {
		t.Fatalf("open after resolve = %d", len(l))
	}
	h := arr(t, e.do("GET", "/admin/reports?status=handled", e.admin, nil))
	if len(h) != 1 || h[0]["resolution"] != "removed" || h[0]["handled_at"] == nil || len(h[0]["reporters"].([]any)) != 2 {
		t.Fatalf("handled = %v", h)
	}
	// 处理后同一人可以再次举报
	e.mustCode(rep(b, "message", msgs[6], "又骂人"), 201)
	if n := e.actions("report_resolve"); n != 1 {
		t.Fatalf("report_resolve actions = %d", n)
	}
	// 管理员删除被举报的消息
	e.mustCode(e.do("DELETE", "/admin/messages/"+i64(msgs[6]), e.admin, map[string]any{"reason": "辱骂"}), 204)
	e.mustCode(e.do("DELETE", "/admin/messages/"+i64(msgs[6]), e.admin, map[string]any{"reason": "辱骂"}), 204)
	if n := e.scalar(`SELECT count(*) FROM class_messages WHERE id = $1 AND deleted_by = 'admin'`, msgs[6]); n != 1 {
		t.Fatal("admin delete message")
	}
	if n := e.actions("message_delete"); n != 1 {
		t.Fatalf("message_delete actions = %d", n)
	}
}

// ---------- 每个管理员写操作都写 admin_actions ----------

func TestEveryAdminWriteLogsAction(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	// 第二个班（不同时段），用于手动合并
	r := e.do("POST", "/timetable/import", b, map[string]any{"slots": []map[string]any{{"name": "概率论", "room": "经世楼G201", "weekday": 3, "start": 1, "end": 2}}})
	e.mustCode(r, 200)
	cid2 := int64(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))

	// PATCH 班名/教师：/sync 下发新班名
	r = e.do("PATCH", "/admin/classes/"+i64(cid), e.admin, map[string]any{"name": "概率论与数理统计B", "teacher": "王老师", "reason": "补全课名"})
	e.mustCode(r, 200)
	if r.Body["name"] != "概率论与数理统计B" || r.Body["teacher"] != "王老师" || r.Body["member_count"] != float64(2) || r.Body["hidden"] != false {
		t.Fatalf("patch class = %s", r.Raw)
	}
	e.mustCode(e.do("PATCH", "/admin/classes/"+i64(cid), e.admin, map[string]any{"teacher": nil}), 200)
	if n := e.scalar(`SELECT count(*) FROM classes WHERE id = $1 AND teacher IS NULL`, cid); n != 1 {
		t.Fatal("teacher null not applied")
	}
	// 手动合并（与自动合并同一套），再用现有接口撤销
	e.errCode(e.do("POST", "/admin/classes/merge", e.admin, map[string]any{"from_class_id": cid2, "into_class_id": cid2}), 400, "bad_request")
	r = e.do("POST", "/admin/classes/merge", e.admin, map[string]any{"from_class_id": cid2, "into_class_id": cid, "reason": "同一个班"})
	e.mustCode(r, 201)
	mergeID := int64(r.Body["merge_id"].(float64))
	if n := e.scalar(`SELECT count(*) FROM classes WHERE id = $1 AND merged_into = $2`, cid2, cid); n != 1 {
		t.Fatal("manual merge not applied")
	}
	e.errCode(e.do("POST", "/admin/classes/merge", e.admin, map[string]any{"from_class_id": cid2, "into_class_id": cid}), 409, "merge_stale")
	e.mustCode(e.do("POST", "/admin/class-merges/"+i64(mergeID)+"/revert", e.admin, nil), 204)
	if n := e.scalar(`SELECT count(*) FROM classes WHERE id = $1 AND merged_into IS NULL`, cid2); n != 1 {
		t.Fatal("manual merge not reverted")
	}
	// 隐藏的班不能手动合并
	e.mustCode(e.do("POST", "/admin/classes/"+i64(cid2)+"/hide", e.admin, map[string]any{"reason": "x"}), 204)
	e.errCode(e.do("POST", "/admin/classes/merge", e.admin, map[string]any{"from_class_id": cid2, "into_class_id": cid}), 409, "merge_stale")
	e.mustCode(e.do("POST", "/admin/classes/"+i64(cid2)+"/unhide", e.admin, nil), 204)

	// 作业合并撤销
	f := e.post(a, cid, "作业甲")
	to := e.post(b, cid, "作业乙")
	r = e.do("POST", "/merge-proposals", a, map[string]any{"from_id": f, "to_id": to})
	e.mustCode(r, 201)
	e.mustCode(e.do("POST", "/admin/merge-proposals/"+i64(int64(r.Body["id"].(float64)))+"/revert", e.admin, nil), 204)

	// 来源删除/恢复、消息删除
	src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, to)
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(src), e.admin, map[string]any{"reason": "x"}), 204)
	e.mustCode(e.do("POST", "/admin/sources/"+i64(src)+"/restore", e.admin, nil), 204)
	r = e.do("POST", msgPath(cid), a, map[string]any{"body": "hello"})
	e.mustCode(r, 201)
	e.mustCode(e.do("DELETE", "/admin/messages/"+i64(int64(r.Body["id"].(float64))), e.admin, map[string]any{"reason": "x"}), 204)

	// 用户：改昵称、处罚与解除、贡献值
	e.errCode(e.do("PATCH", "/admin/users/"+i64(aID), e.admin, map[string]any{"nickname": "乙同学", "reason": "x"}), 409, "nickname_taken")
	e.errCode(e.do("PATCH", "/admin/users/"+i64(aID), e.admin, map[string]any{"nickname": "管理员", "reason": "x"}), 400, "nickname_reserved")
	r = e.do("PATCH", "/admin/users/"+i64(aID), e.admin, map[string]any{"nickname": "同学甲", "reason": "昵称不雅"})
	e.mustCode(r, 200)
	if r.Body["nickname"] != "同学甲" {
		t.Fatalf("patch user = %s", r.Raw)
	}
	if n := e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'nickname_reset' AND payload->>'old' = '甲同学' AND payload->>'new' = '同学甲'`, aID); n != 1 {
		t.Fatal("nickname_reset notice missing")
	}
	r = e.do("POST", "/admin/users/"+i64(aID)+"/sanctions", e.admin, map[string]any{"kind": "post_ban", "days": 1, "reason": "x"})
	e.mustCode(r, 201)
	e.mustCode(e.do("POST", "/admin/sanctions/"+i64(int64(r.Body["id"].(float64)))+"/lift", e.admin, nil), 204)
	e.mustCode(e.do("POST", "/admin/users/"+i64(aID)+"/points", e.admin, map[string]any{"class_id": cid, "delta": -3, "reason": "x"}), 201)

	// 举报处理
	e.mustCode(e.do("POST", "/reports", b, map[string]any{"target_kind": "class", "target_id": cid, "reason": "x"}), 201)
	repID := e.scalar(`SELECT id FROM reports LIMIT 1`)
	e.mustCode(e.do("POST", "/admin/reports/"+i64(repID)+"/resolve", e.admin, map[string]any{"resolution": "dismissed"}), 204)

	// 公告
	e.mustCode(e.do("POST", "/admin/announcements", e.admin, map[string]any{"title": "规则调整", "body": "贡献值规则调整",
		"effective_at": time.Now().Add(8 * 24 * time.Hour).Format(time.RFC3339)}), 201)

	for _, act := range []string{"class_update", "class_merge", "class_merge_revert", "class_hide", "class_unhide", "merge_revert",
		"source_delete", "source_restore", "message_delete", "user_nickname_reset", "sanction_add", "sanction_lift",
		"points_adjust", "report_resolve", "announcement_create"} {
		if e.actions(act) == 0 {
			t.Errorf("admin_actions 缺少 %s", act)
		}
	}
	// 日志接口：倒序，payload 不含秘密
	acts := arr(t, e.do("GET", "/admin/actions", e.admin, nil))
	if len(acts) < 15 || acts[0]["action"] != "announcement_create" {
		t.Fatalf("actions = %v", acts)
	}
	raw, _ := json.Marshal(acts)
	for _, bad := range []string{"password", "recovery", "token", "hash"} {
		if strings.Contains(strings.ToLower(string(raw)), bad) {
			t.Fatalf("admin_actions payload contains %q: %s", bad, raw)
		}
	}
	// 管理员班级列表：suspicious 过滤
	e.do("POST", "/timetable/import", b, map[string]any{"slots": []map[string]any{{"name": "测试😀", "room": "经世楼G301", "weekday": 4, "start": 1, "end": 2}}})
	sus := arr(t, e.do("GET", "/admin/classes?filter=suspicious", e.admin, nil))
	names := map[string]bool{}
	for _, c := range sus {
		names[c["name"].(string)] = true
	}
	if !names["测试😀"] || names["概率论与数理统计B"] {
		t.Fatalf("suspicious = %v", names)
	}
	all := arr(t, e.do("GET", "/admin/classes?q="+url.QueryEscape("概率"), e.admin, nil))
	if len(all) != 2 {
		t.Fatalf("admin classes q = %v", all)
	}
	e.errCode(e.do("GET", "/admin/classes", a, nil), 403, "forbidden")
}

// ---------- JSON 快照：AdminUser / AdminReport 只含 api.yaml 列出的字段 ----------

// shape 把 JSON 值变成只含键名与类型的结构（数组取第一个元素），用于和契约快照比较。
func shape(v any) any {
	switch x := v.(type) {
	case map[string]any:
		out := map[string]any{}
		for k, vv := range x {
			out[k] = shape(vv)
		}
		return out
	case []any:
		if len(x) == 0 {
			return []any{}
		}
		return []any{shape(x[0])}
	case string:
		return "string"
	case float64:
		return "number"
	case bool:
		return "bool"
	case nil:
		return "null"
	}
	return "?"
}

func checkSnapshot(t *testing.T, name string, v any) {
	t.Helper()
	got, _ := json.MarshalIndent(shape(v), "", "  ")
	path := filepath.Join("testdata", name+".json")
	if *updateSnapshots {
		_ = os.MkdirAll("testdata", 0o755)
		if err := os.WriteFile(path, append(got, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read snapshot %s: %v（用 -update 生成后人工核对 api.yaml）", path, err)
	}
	var w any
	_ = json.Unmarshal(want, &w)
	wantNorm, _ := json.MarshalIndent(w, "", "  ")
	if string(wantNorm) != string(got) {
		t.Fatalf("%s 与快照不一致\n got: %s\nwant: %s", name, got, wantNorm)
	}
}

func TestAdminJSONSnapshots(t *testing.T) {
	e, clk := newV2(t)
	inviter, _ := e.user("邀请人")
	code := e.do("POST", "/me/invites", inviter, nil).Body["code"].(string)
	r := e.do("POST", "/auth/register", "", reg("被邀请", code), "RemoteAddr", "198.51.100.7:1234")
	e.mustCode(r, 201)
	a := r.Body["access_token"].(string)
	aID := int64(e.do("GET", "/me", a, nil).Body["id"].(float64))
	b, _ := e.user("乙同学")
	e.class(inviter, a, b)
	cid := e.class(a)
	aid := e.post(a, cid, "第三章")
	src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid)
	var msgs []int64
	for i := 0; i < 3; i++ {
		clk.Add(11 * time.Second)
		r := e.do("POST", msgPath(cid), a, map[string]any{"body": "消息" + strconv.Itoa(i)})
		e.mustCode(r, 201)
		msgs = append(msgs, int64(r.Body["id"].(float64)))
	}
	e.mustCode(e.do("POST", "/admin/users/"+i64(aID)+"/sanctions", e.admin, map[string]any{"kind": "mute", "days": 1, "reason": "x"}), 201)

	u := e.do("GET", "/admin/users/"+i64(aID), e.admin, nil)
	e.mustCode(u, 200)
	if u.Body["inviter_nickname"] != "邀请人" || u.Body["registered_via"] != "invite" || len(u.Body["sanctions"].([]any)) != 1 {
		t.Fatalf("admin user = %s", u.Raw)
	}
	checkSnapshot(t, "admin_user", u.Body)
	list := arr(t, e.do("GET", "/admin/users?q="+url.QueryEscape("邀请"), e.admin, nil))
	if len(list) != 2 || list[0]["nickname"] != "邀请人" {
		t.Fatalf("admin users = %v", list)
	}
	checkSnapshot(t, "admin_user", list[1])
	// 没有任何隐私字段
	for _, bad := range []string{"password", "recovery", "token", "classes", "ip", "completions", "timetable"} {
		for k := range u.Body {
			if strings.Contains(k, bad) {
				t.Fatalf("AdminUser has %q", k)
			}
		}
	}

	for _, x := range []struct {
		kind string
		id   int64
	}{{"source", src}, {"message", msgs[1]}, {"class", cid}, {"user", aID}} {
		e.mustCode(e.do("POST", "/reports", b, map[string]any{"target_kind": x.kind, "target_id": x.id, "reason": "理由"}), 201)
	}
	reports := arr(t, e.do("GET", "/admin/reports", e.admin, nil))
	if len(reports) != 4 {
		t.Fatalf("reports = %v", reports)
	}
	sort.Slice(reports, func(i, j int) bool { return reports[i]["target_kind"].(string) < reports[j]["target_kind"].(string) })
	for _, x := range reports {
		checkSnapshot(t, "admin_report_"+x["target_kind"].(string), x)
	}
}

func newReq(method, path, token string, body any) *http.Request {
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return req
}

func serve(h http.Handler, req *http.Request) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func (e *hwEnv) exec(sql string, args ...any) {
	e.t.Helper()
	if _, err := e.pool.Exec(context.Background(), sql, args...); err != nil {
		e.t.Fatalf("%s: %v", sql, err)
	}
}

// ---------- issue #8 后端项回归 ----------

func TestIssue8SyncSincePlusSign(t *testing.T) {
	e, _ := newV2(t)
	a, _ := e.user("甲同学")
	cid := e.class(a)
	e.post(a, cid, "第三章")
	since := time.Now().Add(-time.Minute).In(time.FixedZone("CST", 8*3600)).Format(time.RFC3339)
	// 不做 URL 编码：+08:00 的加号被解成空格
	r := e.do("GET", "/sync?since="+since, a, nil)
	e.mustCode(r, 200)
	if len(r.Body["changed"].([]any)) != 1 {
		t.Fatalf("sync = %s", r.Raw)
	}
}

func TestIssue8TitleChangeClearsReminderText(t *testing.T) {
	e, _ := newV2(t)
	a, _ := e.user("甲同学")
	cid := e.class(a)
	aid := e.post(a, cid, "第三章")
	e.exec(`UPDATE assignments SET reminder_text = '{"2d":{"title":"第三章后天截止","body":"x"}}' WHERE id = $1`, aid)
	e.mustCode(e.do("PATCH", "/assignments/"+i64(aid), a, map[string]any{"title": "第四章"}), 200)
	if n := e.scalar(`SELECT count(*) FROM assignments WHERE id = $1 AND reminder_text IS NULL`, aid); n != 1 {
		t.Fatal("reminder_text should be cleared when only the title changes")
	}
}

// 恢复时已过结算时间的贡献值重新按当日上限截断；返还的 10 分不计入上限。
func TestIssue8RestoreRespectsDailyCap(t *testing.T) {
	e, _ := newV2(t)
	a, aID := e.user("甲同学")
	cid := e.class(a)
	aid := e.post(a, cid, "第三章")
	src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid)
	today := time.Now().In(time.FixedZone("CST", 8*3600)).Format("2006-01-02")
	e.exec(`UPDATE points_ledger SET status = 'settled', settle_at = now() - interval '1 minute', local_day = $2 WHERE source_id = $1`, src, today)
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(src), e.admin, map[string]any{"reason": "x"}), 204)
	// 删除期间当天已靠别的内容得满 30 分
	e.exec(`INSERT INTO points_ledger (user_id, class_id, semester_id, reason, delta, status, settle_at, local_day)
		SELECT $1, $2, semester_id, 'thanks', 30, 'settled', now(), $3 FROM classes WHERE id = $2`, aID, cid, today)
	e.mustCode(e.do("POST", "/admin/sources/"+i64(src)+"/restore", e.admin, nil), 204)
	if d := e.scalar(`SELECT delta FROM points_ledger WHERE source_id = $1 AND reason = 'first_post' AND status = 'settled'`, src); d != 0 {
		t.Fatalf("restored first_post delta = %d, want 0 (daily cap)", d)
	}
	r := e.do("GET", "/me/points", a, nil)
	if r.Body["today_earned"] != float64(30) {
		t.Fatalf("today_earned = %v（restore_refund 不计入）", r.Body["today_earned"])
	}
}
