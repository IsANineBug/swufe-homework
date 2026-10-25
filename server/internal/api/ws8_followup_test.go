package api_test

// WS8 跟进（issue #12）：PATCH /sources/{id} 的 stated_ddl 显式 null 清空；MergeProposal.voted_by_me。需要 TEST_DATABASE_URL。

import (
	"testing"
	"time"
)

func TestPatchSourceStatedDDLNullVsAbsent(t *testing.T) {
	e := newHW(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	aid := e.post(a, cid, "第三章课后习题")
	d96 := ddlIn(96 * time.Hour)
	r := e.do("POST", "/assignments/"+i64(aid)+"/sources", b, map[string]any{"body": "老师说改到周五", "stated_ddl": d96})
	e.mustCode(r, 201)
	sup := int64(r.Body["id"].(float64))
	conflict := func() any { return e.do("GET", "/assignments/"+i64(aid), a, nil).Body["ddl_conflict"] }
	if conflict() != true {
		t.Fatal("want ddl_conflict after supplement")
	}
	stated := func() int64 {
		return e.scalar(`SELECT coalesce(extract(epoch FROM stated_ddl), 0)::bigint FROM assignment_sources WHERE id = $1`, sup)
	}
	want96, _ := time.Parse(time.RFC3339, d96)

	// 没给 stated_ddl：保持不变
	r = e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"body": "老师说改到周五（补）"})
	e.mustCode(r, 200)
	if r.Body["stated_ddl"] == nil || stated() != want96.Unix() || conflict() != true {
		t.Fatalf("absent field changed stated_ddl: %s", r.Raw)
	}

	// 给值：更新（改成作业 DDL 附近 → 不再标红）
	d72 := ddlIn(72 * time.Hour)
	want72, _ := time.Parse(time.RFC3339, d72)
	r = e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"stated_ddl": d72})
	e.mustCode(r, 200)
	if r.Body["stated_ddl"] == nil || stated() != want72.Unix() || r.Body["body"] != "老师说改到周五（补）" {
		t.Fatalf("value not applied: %s", r.Raw)
	}
	r = e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"stated_ddl": d96})
	e.mustCode(r, 200)
	if conflict() != true {
		t.Fatal("want ddl_conflict after setting 96h")
	}

	// 显式 null：清空旧值，并重算标红
	r = e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"stated_ddl": nil})
	e.mustCode(r, 200)
	if v, ok := r.Body["stated_ddl"]; !ok || v != nil {
		t.Fatalf("explicit null not cleared: %s", r.Raw)
	}
	if n := e.scalar(`SELECT count(*) FROM assignment_sources WHERE id = $1 AND stated_ddl IS NULL`, sup); n != 1 {
		t.Fatal("db stated_ddl not null")
	}
	if conflict() != false {
		t.Fatal("ddl_conflict should clear after stated_ddl null")
	}
	// 清空后再不给字段：仍为 null
	r = e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"body": "再改一次"})
	e.mustCode(r, 200)
	if r.Body["stated_ddl"] != nil {
		t.Fatalf("absent after null: %s", r.Raw)
	}
	// 非法时间 400
	e.errCode(e.do("PATCH", "/sources/"+i64(sup), b, map[string]any{"stated_ddl": "明天"}), 400, "bad_request")
}

func TestMergeProposalVotedByMe(t *testing.T) {
	e := newHW(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	c, _ := e.user("丙同学")
	d, _ := e.user("丁同学")
	f, _ := e.user("戊同学")
	cid := e.class(a, b, c, d, f)
	x := e.post(a, cid, "第三章习题")
	y := e.post(b, cid, "第三章 习题")

	// 提议人丙：创建返回 true
	r := e.do("POST", "/merge-proposals", c, map[string]any{"from_id": y, "to_id": x})
	e.mustCode(r, 201)
	pid := int64(r.Body["id"].(float64))
	if r.Body["voted_by_me"] != true {
		t.Fatalf("proposer voted_by_me = %s", r.Raw)
	}
	// 丁投票：返回 true
	r = e.do("POST", "/merge-proposals/"+i64(pid)+"/votes", d, nil)
	e.mustCode(r, 200)
	if r.Body["voted_by_me"] != true || r.Body["votes"] != float64(2) {
		t.Fatalf("voter voted_by_me = %s", r.Raw)
	}
	// 详情 pending_merges：按当前用户算
	pm := func(tok string, aid int64) map[string]any {
		t.Helper()
		det := e.do("GET", "/assignments/"+i64(aid), tok, nil)
		e.mustCode(det, 200)
		l := det.Body["pending_merges"].([]any)
		if len(l) != 1 {
			t.Fatalf("pending_merges = %s", det.Raw)
		}
		return l[0].(map[string]any)
	}
	for _, tc := range []struct {
		name string
		tok  string
		want bool
	}{{"提议人", c, true}, {"投过票", d, true}, {"未投（to 首发者）", a, false}, {"未投（from 首发者）", b, false}, {"未投（旁观）", f, false}} {
		for _, aid := range []int64{x, y} {
			if got := pm(tc.tok, aid)["voted_by_me"]; got != tc.want {
				t.Fatalf("%s 在作业 %d 看到 voted_by_me = %v，want %v", tc.name, aid, got, tc.want)
			}
		}
	}
	// 票数满后执行，执行结果对投票人也是 true
	r = e.do("POST", "/merge-proposals/"+i64(pid)+"/votes", f, nil)
	e.mustCode(r, 200)
	if r.Body["status"] != "applied" || r.Body["voted_by_me"] != true {
		t.Fatalf("applied = %s", r.Raw)
	}

	// accept 路径：首发者 accept 不是投票，返回 false；提议人仍为 true（重新提一条）
	z := e.post(a, cid, "第四章习题")
	w := e.post(b, cid, "第四章 习题")
	r = e.do("POST", "/merge-proposals", d, map[string]any{"from_id": w, "to_id": z})
	e.mustCode(r, 201)
	pid2 := int64(r.Body["id"].(float64))
	r = e.do("POST", "/merge-proposals/"+i64(pid2)+"/accept", b, nil)
	e.mustCode(r, 200)
	if r.Body["status"] != "applied" || r.Body["voted_by_me"] != false {
		t.Fatalf("accept = %s", r.Raw)
	}
}
