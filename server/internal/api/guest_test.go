package api_test

// 游客浏览 GET /guest/assignments：无需登录、匿名、过滤删除/隐藏/封号、限流、可关闭。需要 TEST_DATABASE_URL。

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/netx"
)

func TestGuestAssignments(t *testing.T) {
	e := newHW(t)
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
