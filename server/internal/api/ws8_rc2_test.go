package api_test

// WS8 rc2 修复（issue #12，作者裁决 2026-10-25）：合班/撤销搬频道消息；禁发期间不能修改已发内容。需要 TEST_DATABASE_URL。

import (
	"testing"
	"time"
)

// msgBodies 以 tok 身份列出班级频道的消息正文。
func (e *hwEnv) msgBodies(tok string, cid int64) map[string]bool {
	e.t.Helper()
	r := e.do("GET", msgPath(cid), tok, nil)
	e.mustCode(r, 200)
	out := map[string]bool{}
	for _, x := range r.Body["items"].([]any) {
		out[x.(map[string]any)["body"].(string)] = true
	}
	return out
}

func errMessage(r resp) string { m, _ := r.Body["message"].(string); return m }

func TestMergeMovesClassMessagesAndRevertMovesBack(t *testing.T) {
	e, clk := newV2(t)
	u1, _ := e.user("一号")
	u2, _ := e.user("二号")
	u3, _ := e.user("三号")
	mon := []map[string]any{{"name": "概率论", "room": "经世楼G101", "weekday": 1, "start": 1, "end": 2}}
	fri := []map[string]any{{"name": "概率论", "room": "通博楼B202", "weekday": 5, "start": 3, "end": 4}}
	c1 := int64(e.do("POST", "/timetable/import", u1, map[string]any{"slots": mon}).Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	c2 := int64(e.do("POST", "/timetable/import", u2, map[string]any{"slots": fri}).Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	e.mustCode(e.do("POST", msgPath(c1), u1, map[string]any{"body": "保留班原消息"}), 201)
	clk.Add(11 * time.Second)
	r := e.do("POST", msgPath(c2), u2, map[string]any{"body": "被并班的消息"})
	e.mustCode(r, 201)
	m2 := int64(r.Body["id"].(float64))

	// u3 同时导入两个时段 → 自动合并，c2 并入 c1
	r = e.do("POST", "/timetable/import", u3, map[string]any{"slots": append(append([]map[string]any{}, mon...), fri...)})
	e.mustCode(r, 200)
	if m := r.Body["merged"].([]any); len(m) != 1 {
		t.Fatalf("expected auto merge: %s", r.Raw)
	}
	if got := e.scalar(`SELECT class_id FROM class_messages WHERE id = $1`, m2); got != c1 {
		t.Fatalf("message class after merge = %d, want %d", got, c1)
	}
	if n := e.scalar(`SELECT jsonb_array_length(snapshot->'message_ids') FROM class_merges`); n != 1 {
		t.Fatalf("snapshot message_ids len = %d", n)
	}
	// 保留班里两边成员都能看到原消息
	for _, tok := range []string{u1, u2, u3} {
		if b := e.msgBodies(tok, c1); !b["被并班的消息"] || !b["保留班原消息"] {
			t.Fatalf("after merge c1 messages = %v", b)
		}
	}
	// 合并后在保留班新发的消息
	clk.Add(11 * time.Second)
	r = e.do("POST", msgPath(c1), u3, map[string]any{"body": "合并后新发"})
	e.mustCode(r, 201)
	mNew := int64(r.Body["id"].(float64))

	mid := e.scalar(`SELECT id FROM class_merges`)
	e.mustCode(e.do("POST", "/admin/class-merges/"+i64(mid)+"/revert", e.admin, nil), 204)
	if got := e.scalar(`SELECT class_id FROM class_messages WHERE id = $1`, m2); got != c2 {
		t.Fatalf("message class after revert = %d, want %d", got, c2)
	}
	if got := e.scalar(`SELECT class_id FROM class_messages WHERE id = $1`, mNew); got != c1 {
		t.Fatalf("post-merge message moved to %d, want stay in %d", got, c1)
	}
	if b := e.msgBodies(u2, c2); !b["被并班的消息"] || b["保留班原消息"] || b["合并后新发"] {
		t.Fatalf("after revert c2 messages = %v", b)
	}
	if b := e.msgBodies(u1, c1); b["被并班的消息"] || !b["保留班原消息"] || !b["合并后新发"] {
		t.Fatalf("after revert c1 messages = %v", b)
	}
}

// 旧快照（rc2 之前写的）没有 message_ids 键：撤销照常成功，消息当作没有记录、留在保留班。
func TestRevertLegacySnapshotWithoutMessageIDs(t *testing.T) {
	e, _ := newV2(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a)
	r := e.do("POST", "/timetable/import", b, map[string]any{"slots": []map[string]any{{"name": "概率论", "room": "经世楼G201", "weekday": 3, "start": 1, "end": 2}}})
	e.mustCode(r, 200)
	cid2 := int64(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	r = e.do("POST", msgPath(cid2), b, map[string]any{"body": "旧消息"})
	e.mustCode(r, 201)
	msg := int64(r.Body["id"].(float64))
	r = e.do("POST", "/admin/classes/merge", e.admin, map[string]any{"from_class_id": cid2, "into_class_id": cid, "reason": "同一个班"})
	e.mustCode(r, 201)
	mergeID := int64(r.Body["merge_id"].(float64))
	e.exec(`UPDATE class_merges SET snapshot = snapshot - 'message_ids' WHERE id = $1`, mergeID)
	e.mustCode(e.do("POST", "/admin/class-merges/"+i64(mergeID)+"/revert", e.admin, nil), 204)
	if n := e.scalar(`SELECT count(*) FROM classes WHERE id = $1 AND merged_into IS NULL`, cid2); n != 1 {
		t.Fatal("legacy merge not reverted")
	}
	if got := e.scalar(`SELECT class_id FROM class_messages WHERE id = $1`, msg); got != cid {
		t.Fatalf("legacy snapshot message class = %d, want %d", got, cid)
	}
}

func TestPostBanBlocksEditingPublished(t *testing.T) {
	type banCase struct {
		name string
		ban  func(e *hwEnv, uid, cid int64)
		msg  string
	}
	cases := []banCase{
		{"global", func(e *hwEnv, uid, _ int64) {
			e.mustCode(e.do("POST", "/admin/users/"+i64(uid)+"/sanctions", e.admin, map[string]any{"kind": "post_ban", "days": 7, "reason": "乱发"}), 201)
		}, "你已被管理员暂时禁止发布作业，可在「我的」查看原因和到期时间"},
		{"class", func(e *hwEnv, uid, cid int64) {
			e.exec(`INSERT INTO post_bans (user_id, class_id, starts_at, ends_at) VALUES ($1, $2, now() - interval '1 minute', now() + interval '3 days')`, uid, cid)
		}, "你在这个班暂时被禁止发布"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e, _ := newV2(t)
			a, aID := e.user("甲同学")
			b, _ := e.user("乙同学")
			cid := e.class(a, b)
			aid := e.post(a, cid, "第三章")
			src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1 AND kind = 'first'`, aid)
			c.ban(e, aID, cid)

			r := e.do("PATCH", "/assignments/"+i64(aid), a, map[string]any{"title": "第三章（改）", "ddl": ddlIn(96 * time.Hour)})
			e.errCode(r, 403, "post_banned")
			if got := errMessage(r); got != c.msg {
				t.Fatalf("patch assignment message = %q", got)
			}
			r = e.do("PATCH", "/sources/"+i64(src), a, map[string]any{"body": "改正文"})
			e.errCode(r, 403, "post_banned")
			if got := errMessage(r); got != c.msg {
				t.Fatalf("patch source message = %q", got)
			}
			// 内容没被改
			if n := e.scalar(`SELECT count(*) FROM assignments WHERE id = $1 AND title = '第三章'`, aid); n != 1 {
				t.Fatal("assignment changed while banned")
			}
			if n := e.scalar(`SELECT count(*) FROM assignment_sources WHERE id = $1 AND body = '第 3 章习题 1–10' AND edited_at IS NULL`, src); n != 1 {
				t.Fatal("source changed while banned")
			}
			// 只拦被禁发的人：同班的乙照常编辑自己的补充
			r = e.do("POST", "/assignments/"+i64(aid)+"/sources", b, map[string]any{"body": "补充"})
			e.mustCode(r, 201)
			e.mustCode(e.do("PATCH", "/sources/"+i64(int64(r.Body["id"].(float64))), b, map[string]any{"body": "补充（改）"}), 200)
		})
	}
}

// 修改已发内容不计入「每日 5 条」：当天发满 5 条后仍可修改。
func TestEditPublishedNotCountedInDailyLimit(t *testing.T) {
	e, _ := newV2(t)
	a, _ := e.user("甲同学")
	cid := e.class(a)
	var ids []int64
	for _, title := range []string{"一", "二", "三", "四", "五"} {
		ids = append(ids, e.post(a, cid, "作业"+title))
	}
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "第六条", "ddl": ddlIn(time.Hour)}), 429, "daily_limit")
	e.mustCode(e.do("PATCH", "/assignments/"+i64(ids[0]), a, map[string]any{"title": "作业一（改）"}), 200)
	src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, ids[1])
	e.mustCode(e.do("PATCH", "/sources/"+i64(src), a, map[string]any{"body": "改过的正文"}), 200)
}
