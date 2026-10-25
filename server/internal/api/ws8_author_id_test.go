package api_test

// WS8（issue #12，契约 main 4f2657d）：Source / Message 带 author_id（作者用户 id）。需要 TEST_DATABASE_URL。

import (
	"testing"
	"time"
)

// authorID 取 JSON 对象里的 author_id；缺失或类型不对直接失败。
func authorID(t *testing.T, where string, m map[string]any) int64 {
	t.Helper()
	v, ok := m["author_id"].(float64)
	if !ok {
		t.Fatalf("%s: author_id missing or not a number: %v", where, m)
	}
	return int64(v)
}

func sourcesOf(t *testing.T, where string, body map[string]any) []map[string]any {
	t.Helper()
	raw, ok := body["sources"].([]any)
	if !ok {
		t.Fatalf("%s: no sources: %v", where, body)
	}
	out := make([]map[string]any, 0, len(raw))
	for _, x := range raw {
		out = append(out, x.(map[string]any))
	}
	return out
}

func TestSourceAuthorID(t *testing.T) {
	e := newHW(t)
	a, aID := e.user("甲同学")
	b, bID := e.user("乙同学")
	cid := e.class(a, b)

	// POST /classes/{id}/assignments → Assignment.sources
	r := e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "第三章", "ddl": ddlIn(72 * time.Hour), "body": "习题 1–10"})
	e.mustCode(r, 201)
	aid := int64(r.Body["id"].(float64))
	if s := sourcesOf(t, "create", r.Body); len(s) != 1 || authorID(t, "create", s[0]) != aID {
		t.Fatalf("create sources = %s", r.Raw)
	}

	// POST /assignments/{id}/sources → Source
	r = e.do("POST", "/assignments/"+i64(aid)+"/sources", b, map[string]any{"body": "补充"})
	e.mustCode(r, 201)
	sup := int64(r.Body["id"].(float64))
	if authorID(t, "post source", r.Body) != bID {
		t.Fatalf("post source = %s", r.Raw)
	}

	// PATCH /sources/{id} → Source
	r = e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"body": "补充（改）"})
	e.mustCode(r, 200)
	if authorID(t, "patch source", r.Body) != bID {
		t.Fatalf("patch source = %s", r.Raw)
	}

	// GET /assignments/{id}：作者 id 与查看者无关
	for _, tok := range []string{a, b} {
		r = e.do("GET", "/assignments/"+i64(aid), tok, nil)
		e.mustCode(r, 200)
		s := sourcesOf(t, "get", r.Body)
		if len(s) != 2 || authorID(t, "get first", s[0]) != aID || authorID(t, "get sup", s[1]) != bID {
			t.Fatalf("get sources = %s", r.Raw)
		}
	}

	// PATCH /assignments/{id} → Assignment.sources
	r = e.do("PATCH", "/assignments/"+i64(aid), a, map[string]any{"title": "第三章习题"})
	e.mustCode(r, 200)
	if s := sourcesOf(t, "patch assignment", r.Body); len(s) != 2 || authorID(t, "patch first", s[0]) != aID || authorID(t, "patch sup", s[1]) != bID {
		t.Fatalf("patch assignment sources = %s", r.Raw)
	}

	// 改昵称后 author_id 不变
	e.mustCode(e.do("PATCH", "/me", b, map[string]any{"nickname": "乙同学二号"}), 200)
	r = e.do("GET", "/assignments/"+i64(aid), a, nil)
	if s := sourcesOf(t, "after rename", r.Body); authorID(t, "after rename", s[1]) != bID || s[1]["author_nickname"] != "乙同学二号" {
		t.Fatalf("after rename = %s", r.Raw)
	}

	// GET /admin/deleted（Source 模型）
	e.mustCode(e.do("DELETE", "/admin/sources/"+i64(sup), e.admin, map[string]any{"reason": "测试"}), 204)
	del := arr(t, e.do("GET", "/admin/deleted", e.admin, nil))
	found := false
	for _, d := range del {
		if int64(d["id"].(float64)) == sup {
			found = true
			if authorID(t, "admin deleted", d) != bID {
				t.Fatalf("admin deleted = %v", d)
			}
		}
	}
	if !found {
		t.Fatalf("deleted source %d not listed: %v", sup, del)
	}
}

func TestMessageAuthorID(t *testing.T) {
	e, clk := newV2(t)
	a, aID := e.user("甲同学")
	b, bID := e.user("乙同学")
	cid := e.class(a, b)

	// POST /classes/{id}/messages → Message
	r := e.do("POST", msgPath(cid), a, map[string]any{"body": "甲的消息"})
	e.mustCode(r, 201)
	idA := int64(r.Body["id"].(float64))
	if authorID(t, "post a", r.Body) != aID {
		t.Fatalf("post a = %s", r.Raw)
	}
	clk.Add(11 * time.Second)
	r = e.do("POST", msgPath(cid), b, map[string]any{"body": "乙的消息"})
	e.mustCode(r, 201)
	idB := int64(r.Body["id"].(float64))
	if authorID(t, "post b", r.Body) != bID {
		t.Fatalf("post b = %s", r.Raw)
	}

	// GET /classes/{id}/messages：最新、before_id、after_id 三条分支都带
	for _, q := range []string{"", "?before_id=" + i64(idB+1), "?after_id=" + i64(idA-1)} {
		r = e.do("GET", msgPath(cid)+q, b, nil)
		e.mustCode(r, 200)
		items := r.Body["items"].([]any)
		if len(items) == 0 {
			t.Fatalf("list%s empty: %s", q, r.Raw)
		}
		for _, x := range items {
			m := x.(map[string]any)
			want := aID
			if m["body"] == "乙的消息" {
				want = bID
			}
			if authorID(t, "list"+q, m) != want {
				t.Fatalf("list%s = %s", q, r.Raw)
			}
		}
	}
}
