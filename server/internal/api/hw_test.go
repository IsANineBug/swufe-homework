package api_test

// WS2 集成测试：作业、补充、完成状态、查重、同步、积分结算与上限、谢谢、删除投票/禁发/恢复、合并/撤销、
// 上传与 /files 签名、榜单、班级合并撤销。需要 TEST_DATABASE_URL。

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/points"
)

type hwEnv struct {
	*env
	admin string
}

func newHW(t *testing.T) *hwEnv {
	e := newEnv(t, "192.0.2.0/24")
	e.cfg.UploadDir = t.TempDir()
	e.h = api.New(e.cfg, e.pool).Router()
	r := e.do("POST", "/auth/register", "", reg("站长", nil), "X-Bootstrap-Token", "boot-token")
	e.mustCode(r, 201)
	return &hwEnv{env: e, admin: r.Body["access_token"].(string)}
}

// user 校园网注册一个用户，返回 token 与 id。
func (e *hwEnv) user(nick string) (string, int64) {
	e.t.Helper()
	r := e.do("POST", "/auth/register", "", reg(nick, nil))
	e.mustCode(r, 201)
	tok := r.Body["access_token"].(string)
	me := e.do("GET", "/me", tok, nil)
	return tok, int64(me.Body["id"].(float64))
}

var probSlot = map[string]any{"slots": []map[string]any{{"name": "概率论", "room": "经世楼G101", "weekday": 1, "start": 1, "end": 2}}}

// class 让这些用户导入同一门课，返回班级 id。
func (e *hwEnv) class(toks ...string) int64 {
	e.t.Helper()
	var cid int64
	for _, tok := range toks {
		r := e.do("POST", "/timetable/import", tok, probSlot)
		e.mustCode(r, 200)
		for _, k := range []string{"created", "joined"} {
			if l, _ := r.Body[k].([]any); len(l) > 0 {
				cid = int64(l[0].(map[string]any)["id"].(float64))
			}
		}
	}
	return cid
}

func ddlIn(d time.Duration) string {
	return time.Now().Add(d).Truncate(time.Minute).Format(time.RFC3339)
}

func (e *hwEnv) post(tok string, cid int64, title string, extra ...any) int64 {
	e.t.Helper()
	body := map[string]any{"title": title, "ddl": ddlIn(72 * time.Hour), "body": "第 3 章习题 1–10"}
	for i := 0; i+1 < len(extra); i += 2 {
		body[extra[i].(string)] = extra[i+1]
	}
	r := e.do("POST", "/classes/"+i64(cid)+"/assignments", tok, body)
	e.mustCode(r, 201)
	return int64(r.Body["id"].(float64))
}

func i64(n int64) string { b, _ := json.Marshal(n); return string(b) }

func arr(t *testing.T, r resp) []map[string]any {
	t.Helper()
	var out []map[string]any
	if err := json.Unmarshal(r.Raw, &out); err != nil {
		t.Fatalf("not an array: %s", r.Raw)
	}
	return out
}

func (e *hwEnv) scalar(sql string, args ...any) int64 {
	e.t.Helper()
	var n int64
	if err := e.pool.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		e.t.Fatalf("%s: %v", sql, err)
	}
	return n
}

func TestAssignmentLifecycle(t *testing.T) {
	e := newHW(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	out, _ := e.user("路人")
	cid := e.class(a, b)

	// 非成员、缺 DDL、标题过长
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", out, map[string]any{"title": "x", "ddl": ddlIn(time.Hour)}), 403, "not_member")
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "x"}), 400, "ddl_required")
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": strings.Repeat("长", 81), "ddl": ddlIn(time.Hour)}), 400, "bad_request")

	aid := e.post(a, cid, "第三章课后习题")
	// 查重：相似标题命中，不相干的不命中
	c := arr(t, e.do("POST", "/classes/"+i64(cid)+"/assignments/candidates", b, map[string]any{"title": "第三章 课后习题！", "ddl": ddlIn(80 * time.Hour)}))
	if len(c) != 1 || int64(c[0]["id"].(float64)) != aid {
		t.Fatalf("candidates = %v", c)
	}
	if c := arr(t, e.do("POST", "/classes/"+i64(cid)+"/assignments/candidates", b, map[string]any{"title": "期中论文"})); len(c) != 0 {
		t.Fatalf("unrelated candidates = %v", c)
	}
	if c := arr(t, e.do("POST", "/classes/"+i64(cid)+"/assignments/candidates", b, map[string]any{"title": "第三章课后习题", "ddl": ddlIn(10 * 24 * time.Hour)})); len(c) != 0 {
		t.Fatalf("ddl window ignored: %v", c)
	}

	// 补充：DDL 不一致标红；空补充 400
	e.errCode(e.do("POST", "/assignments/"+i64(aid)+"/sources", b, map[string]any{"body": "  "}), 400, "bad_request")
	r := e.do("POST", "/assignments/"+i64(aid)+"/sources", b, map[string]any{"body": "老师说改到周五", "stated_ddl": ddlIn(96 * time.Hour)})
	e.mustCode(r, 201)
	sup := int64(r.Body["id"].(float64))
	d := e.do("GET", "/assignments/"+i64(aid), b, nil)
	e.mustCode(d, 200)
	if d.Body["ddl_conflict"] != true || d.Body["source_count"] != float64(2) || d.Body["first_poster_nickname"] != "甲同学" {
		t.Fatalf("detail = %s", d.Raw)
	}
	e.errCode(e.do("GET", "/assignments/"+i64(aid), out, nil), 403, "not_member")

	// 只有首发者能改；改成补充里的 DDL 后不再标红
	e.errCode(e.do("PATCH", "/assignments/"+i64(aid), b, map[string]any{"title": "改"}), 403, "forbidden")
	p := e.do("PATCH", "/assignments/"+i64(aid), a, map[string]any{"ddl": ddlIn(96 * time.Hour)})
	e.mustCode(p, 200)
	if p.Body["ddl_conflict"] != false {
		t.Fatalf("conflict after patch = %s", p.Raw)
	}

	// 完成状态只影响本人
	e.mustCode(e.do("PUT", "/assignments/"+i64(aid)+"/done", a, nil), 204)
	if f := arr(t, e.do("GET", "/feed", a, nil)); len(f) != 0 {
		t.Fatalf("done item still in feed: %v", f)
	}
	if f := arr(t, e.do("GET", "/feed?include_done=true", a, nil)); len(f) != 1 || f[0]["done"] != true {
		t.Fatalf("include_done = %v", f)
	}
	if f := arr(t, e.do("GET", "/feed", b, nil)); len(f) != 1 || f[0]["done"] != false {
		t.Fatalf("b's feed = %v", f)
	}
	if l := arr(t, e.do("GET", "/classes/"+i64(cid)+"/assignments", b, nil)); len(l) != 1 {
		t.Fatalf("class list = %v", l)
	}

	// 增量同步
	since := time.Now().Add(-time.Minute).Format(time.RFC3339)
	s := e.do("GET", "/sync?since="+url.QueryEscape(since), b, nil)
	e.mustCode(s, 200)
	if len(s.Body["changed"].([]any)) != 1 {
		t.Fatalf("sync = %s", s.Raw)
	}

	// 作者删除：补充只能作者删；全部来源删除后作业消失并进 removed_ids
	e.errCode(e.do("DELETE", "/sources/"+i64(sup), a, nil), 403, "forbidden")
	e.mustCode(e.do("DELETE", "/sources/"+i64(sup), b, nil), 204)
	first := int64(e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1 AND kind = 'first'`, aid))
	e.mustCode(e.do("DELETE", "/sources/"+i64(first), a, nil), 204)
	e.errCode(e.do("GET", "/assignments/"+i64(aid), a, nil), 404, "not_found")
	s = e.do("GET", "/sync?since="+url.QueryEscape(since), b, nil)
	if ids := s.Body["removed_ids"].([]any); len(ids) != 1 || int64(ids[0].(float64)) != aid {
		t.Fatalf("removed = %s", s.Raw)
	}
	// 作者自删：积分作废，不扣分
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE status <> 'void'`); n != 0 {
		t.Fatalf("points not voided: %d", n)
	}
}

func TestDailyLimitAndPointsCap(t *testing.T) {
	e := newHW(t)
	a, aID := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)
	var ids []int64
	for i := 0; i < 5; i++ {
		ids = append(ids, e.post(a, cid, "作业"+string(rune('A'+i))))
	}
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "第六条", "ddl": ddlIn(time.Hour)}), 429, "daily_limit")
	// 删除的不计入
	first := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, ids[4])
	e.mustCode(e.do("DELETE", "/sources/"+i64(first), a, nil), 204)
	ids[4] = e.post(a, cid, "第六条")

	// 未到 24h 不结算
	ctx := context.Background()
	if _, err := points.Settle(ctx, e.pool, time.Now()); err != nil {
		t.Fatal(err)
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE status = 'settled'`); n != 0 {
		t.Fatalf("settled early: %d", n)
	}
	// 24h 后结算：5 条首发 50 分，每日上限 30
	if _, err := points.Settle(ctx, e.pool, time.Now().Add(25*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if got := e.scalar(`SELECT sum(delta) FROM points_ledger WHERE user_id = $1 AND status = 'settled'`, aID); got != 30 {
		t.Fatalf("settled total = %d, want 30", got)
	}

	// 谢谢：不能谢自己、不能重复；+1 立即到账
	src := e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, ids[0])
	e.errCode(e.do("POST", "/sources/"+i64(src)+"/thanks", a, nil), 409, "self_thanks")
	e.mustCode(e.do("POST", "/sources/"+i64(src)+"/thanks", b, nil), 204)
	e.errCode(e.do("POST", "/sources/"+i64(src)+"/thanks", b, nil), 409, "already_thanked")
	if got := e.scalar(`SELECT delta FROM points_ledger WHERE reason = 'thanks'`); got != 1 {
		t.Fatalf("thanks delta = %d", got)
	}
	mp := e.do("GET", "/me/points", a, nil)
	e.mustCode(mp, 200)
	if mp.Body["semester_total"] != float64(31) || len(mp.Body["items"].([]any)) != 7 {
		t.Fatalf("me/points = %s", mp.Raw)
	}

	// 榜单：匿名只改显示名；课代表为第一
	e.do("PATCH", "/me", a, map[string]any{"leaderboard_anonymous": true})
	lb := e.do("GET", "/classes/"+i64(cid)+"/leaderboard?scope=semester", b, nil)
	e.mustCode(lb, 200)
	rows := lb.Body["rows"].([]any)
	if len(rows) != 1 || rows[0].(map[string]any)["display_name"] != "匿名同学" || rows[0].(map[string]any)["points"] != float64(31) {
		t.Fatalf("leaderboard = %s", lb.Raw)
	}
	if lb.Body["rep"].(map[string]any)["display_name"] != "匿名同学" {
		t.Fatalf("rep = %s", lb.Raw)
	}
	e.errCode(e.do("GET", "/classes/"+i64(cid)+"/leaderboard?scope=year", b, nil), 400, "bad_request")
}

func TestDeleteVotesBanAndRestore(t *testing.T) {
	e := newHW(t)
	inviter, inviterID := e.user("邀请人")
	code := e.do("POST", "/me/invites", inviter, nil).Body["code"].(string)
	// 校园网外用邀请码注册
	r := e.do("POST", "/auth/register", "", reg("被邀请", code), "RemoteAddr", "198.51.100.7:1234")
	e.mustCode(r, 201)
	author := r.Body["access_token"].(string)
	v1, _ := e.user("投票一")
	v2, _ := e.user("投票二")
	v3, _ := e.user("投票三")
	cid := e.class(author, v1, v2, v3, inviter)

	var srcs []int64
	for i := 0; i < 3; i++ {
		aid := e.post(author, cid, "错误作业"+string(rune('A'+i)))
		srcs = append(srcs, e.scalar(`SELECT id FROM assignment_sources WHERE assignment_id = $1`, aid))
	}
	e.errCode(e.do("POST", "/sources/"+i64(srcs[0])+"/delete-votes", author, nil), 409, "self_vote")
	for i, s := range srcs {
		r1 := e.do("POST", "/sources/"+i64(s)+"/delete-votes", v1, nil)
		e.mustCode(r1, 200)
		if r1.Body["threshold"] != float64(3) || r1.Body["deleted"] != false {
			t.Fatalf("vote = %s", r1.Raw)
		}
		e.errCode(e.do("POST", "/sources/"+i64(s)+"/delete-votes", v1, nil), 409, "already_voted")
		e.do("POST", "/sources/"+i64(s)+"/delete-votes", v2, nil)
		r3 := e.do("POST", "/sources/"+i64(s)+"/delete-votes", v3, nil)
		e.mustCode(r3, 200)
		if r3.Body["deleted"] != true {
			t.Fatalf("source %d not deleted: %s", i, r3.Raw)
		}
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE reason = 'deleted_penalty' AND delta = -10`); n != 3 {
		t.Fatalf("penalties = %d", n)
	}
	if n := e.scalar(`SELECT count(*) FROM post_bans WHERE lifted_at IS NULL`); n != 1 {
		t.Fatalf("bans = %d", n)
	}
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", author, map[string]any{"title": "新的", "ddl": ddlIn(time.Hour)}), 403, "post_banned")
	if n := e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'invitee_violation'`, inviterID); n != 3 {
		t.Fatalf("inviter notices = %d", n)
	}
	// 普通用户不能用管理员接口
	e.errCode(e.do("GET", "/admin/deleted", v1, nil), 403, "forbidden")
	del := arr(t, e.do("GET", "/admin/deleted", e.admin, nil))
	if len(del) != 3 || del[0]["deleted_reason"] != "vote" {
		t.Fatalf("admin deleted = %v", del)
	}
	// 恢复：返还扣分、解除禁发、作业重新出现
	e.mustCode(e.do("POST", "/admin/sources/"+i64(srcs[0])+"/restore", e.admin, map[string]any{}), 204)
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE reason = 'restore_refund' AND delta = 10`); n != 1 {
		t.Fatalf("refunds = %d", n)
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE source_id = $1 AND reason = 'first_post' AND status = 'pending'`, srcs[0]); n != 1 {
		t.Fatalf("first_post not restored")
	}
	e.post(author, cid, "解禁后再发")
	// 再次恢复幂等，不重复返还
	e.mustCode(e.do("POST", "/admin/sources/"+i64(srcs[0])+"/restore", e.admin, nil), 204)
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE reason = 'restore_refund'`); n != 1 {
		t.Fatalf("double refund = %d", n)
	}
}

func TestMergeVotesAcceptAndRevert(t *testing.T) {
	e := newHW(t)
	a, _ := e.user("甲同学")
	b, bID := e.user("乙同学")
	c, _ := e.user("丙同学")
	d, _ := e.user("丁同学")
	cid := e.class(a, b, c, d)
	x := e.post(a, cid, "第三章习题")
	y := e.post(b, cid, "第三章 习题", "ddl", ddlIn(100*time.Hour))
	e.mustCode(e.do("PUT", "/assignments/"+i64(y)+"/done", b, nil), 204)

	// 丙提议（算 1 票），乙收到通知；丁赞同（2 票）；甲赞同（3 票）→ 执行
	r := e.do("POST", "/merge-proposals", c, map[string]any{"from_id": y, "to_id": x})
	e.mustCode(r, 201)
	pid := int64(r.Body["id"].(float64))
	if r.Body["votes"] != float64(1) || r.Body["status"] != "pending" {
		t.Fatalf("proposal = %s", r.Raw)
	}
	e.errCode(e.do("POST", "/merge-proposals", d, map[string]any{"from_id": y, "to_id": x}), 409, "already_proposed")
	if n := e.scalar(`SELECT count(*) FROM notices WHERE user_id = $1 AND kind = 'merge_request'`, bID); n != 1 {
		t.Fatalf("merge_request notice = %d", n)
	}
	e.mustCode(e.do("POST", "/merge-proposals/"+i64(pid)+"/votes", d, nil), 200)
	e.errCode(e.do("POST", "/merge-proposals/"+i64(pid)+"/votes", d, nil), 409, "already_voted")
	det := e.do("GET", "/assignments/"+i64(x), a, nil)
	if len(det.Body["pending_merges"].([]any)) != 1 {
		t.Fatalf("pending merges = %s", det.Raw)
	}
	r = e.do("POST", "/merge-proposals/"+i64(pid)+"/votes", a, nil)
	e.mustCode(r, 200)
	if r.Body["status"] != "applied" {
		t.Fatalf("not applied: %s", r.Raw)
	}
	// 读被合并的 y → 返回 x，来源 2 条，DDL 不一致标红，乙的完成状态带过去
	g := e.do("GET", "/assignments/"+i64(y), b, nil)
	e.mustCode(g, 200)
	if int64(g.Body["id"].(float64)) != x || len(g.Body["sources"].([]any)) != 2 || g.Body["ddl_conflict"] != true || g.Body["done"] != true {
		t.Fatalf("merged view = %s", g.Raw)
	}
	if mf := g.Body["merged_from"].([]any); len(mf) != 1 || int64(mf[0].(float64)) != y {
		t.Fatalf("merged_from = %s", g.Raw)
	}
	// 乙的首发分作废，改记补充 +3
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE user_id = $1 AND reason = 'first_post' AND status = 'void'`, bID); n != 1 {
		t.Fatal("first_post not voided")
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE user_id = $1 AND reason = 'supplement' AND delta = 3 AND status = 'pending'`, bID); n != 1 {
		t.Fatal("supplement not added")
	}
	s := e.do("GET", "/sync?since="+url.QueryEscape(time.Now().Add(-time.Minute).Format(time.RFC3339)), a, nil)
	if ids := s.Body["removed_ids"].([]any); len(ids) != 1 || int64(ids[0].(float64)) != y {
		t.Fatalf("sync removed = %s", s.Raw)
	}

	// 管理员撤销：来源搬回、首发与积分还原、合并补上的完成状态去掉
	e.mustCode(e.do("POST", "/admin/merge-proposals/"+i64(pid)+"/revert", e.admin, nil), 204)
	g = e.do("GET", "/assignments/"+i64(y), b, nil)
	if int64(g.Body["id"].(float64)) != y || len(g.Body["sources"].([]any)) != 1 || g.Body["sources"].([]any)[0].(map[string]any)["kind"] != "first" {
		t.Fatalf("after revert y = %s", g.Raw)
	}
	g = e.do("GET", "/assignments/"+i64(x), b, nil)
	if len(g.Body["sources"].([]any)) != 1 || g.Body["ddl_conflict"] != false || g.Body["done"] != false {
		t.Fatalf("after revert x = %s", g.Raw)
	}
	if n := e.scalar(`SELECT count(*) FROM points_ledger WHERE user_id = $1 AND status <> 'void'`, bID); n != 1 {
		t.Fatalf("b ledger after revert = %d", n)
	}
	e.errCode(e.do("POST", "/admin/merge-proposals/"+i64(pid)+"/revert", e.admin, nil), 409, "merge_closed")

	// 首发者同意：乙接受后立即执行
	r = e.do("POST", "/merge-proposals", c, map[string]any{"from_id": y, "to_id": x})
	pid2 := int64(r.Body["id"].(float64))
	e.errCode(e.do("POST", "/merge-proposals/"+i64(pid2)+"/accept", c, nil), 403, "forbidden")
	r = e.do("POST", "/merge-proposals/"+i64(pid2)+"/accept", b, nil)
	e.mustCode(r, 200)
	if r.Body["status"] != "applied" {
		t.Fatalf("accept = %s", r.Raw)
	}
}

func pngBytes(t *testing.T, w, h int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for x := 0; x < w; x++ {
		img.Set(x, x%h, color.RGBA{255, 0, 0, 255})
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func (e *hwEnv) upload(tok string, data []byte) resp {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	fw, _ := mw.CreateFormFile("file", "a.bin")
	_, _ = fw.Write(data)
	mw.Close()
	req := httptest.NewRequest("POST", "/api/v1/uploads", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, req)
	r := resp{Code: w.Code, Raw: w.Body.Bytes()}
	_ = json.Unmarshal(r.Raw, &r.Body)
	return r
}

func TestUploadsAndSignedFiles(t *testing.T) {
	e := newHW(t)
	a, _ := e.user("甲同学")
	b, _ := e.user("乙同学")
	cid := e.class(a, b)

	r := e.upload(a, pngBytes(t, 1200, 300))
	e.mustCode(r, 201)
	att := int64(r.Body["id"].(float64))
	u, _ := url.Parse(r.Body["url"].(string))
	thumb, _ := r.Body["thumb_url"].(string)
	if r.Body["kind"] != "image" || thumb == "" || !strings.HasPrefix(u.Path, "/files/2026-2027-1/") {
		t.Fatalf("upload = %s", r.Raw)
	}
	// 权限：目录 0755、文件 0644（nginx uid 101 要能读）
	rel := strings.TrimPrefix(u.Path, "/files/")
	full := filepath.Join(e.cfg.UploadDir, filepath.FromSlash(rel))
	fi, err := os.Stat(full)
	if err != nil || fi.Mode().Perm() != 0o644 {
		t.Fatalf("file perm = %v %v", fi.Mode(), err)
	}
	di, _ := os.Stat(filepath.Dir(full))
	if di.Mode().Perm() != 0o755 {
		t.Fatalf("dir perm = %v", di.Mode())
	}
	tu, _ := url.Parse(thumb)
	tf, _ := os.Open(filepath.Join(e.cfg.UploadDir, filepath.FromSlash(strings.TrimPrefix(tu.Path, "/files/"))))
	cfg, _, err := image.DecodeConfig(tf)
	tf.Close()
	if err != nil || cfg.Width != 400 || cfg.Height != 100 {
		t.Fatalf("thumb = %+v %v", cfg, err)
	}

	// /files 验签
	get := func(path string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		e.h.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
		return w
	}
	w := get(r.Body["url"].(string))
	if w.Code != 200 || w.Header().Get("X-Accel-Redirect") != "/_protected/"+rel || w.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("files = %d %v", w.Code, w.Header())
	}
	if w := get(strings.Replace(r.Body["url"].(string), "sig=", "sig=x", 1)); w.Code != 403 {
		t.Fatalf("tampered sig = %d", w.Code)
	}
	if w := get("/files/../../etc/passwd?exp=9999999999&sig=x"); w.Code == 200 {
		t.Fatal("path traversal served")
	}

	// 类型校验
	e.errCode(e.upload(a, []byte("hello, not an image")), 415, "bad_file_type")
	e.errCode(e.upload(a, bytes.Repeat([]byte{0xFF, 0xD8, 0xFF}, 4<<20)), 413, "too_large")

	// 发布时挂附件；别人的、已用过的附件不能用
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", b, map[string]any{"title": "借图", "ddl": ddlIn(time.Hour), "attachment_ids": []int64{att}}), 400, "bad_attachment")
	aid := e.post(a, cid, "看图", "attachment_ids", []int64{att})
	e.errCode(e.do("POST", "/classes/"+i64(cid)+"/assignments", a, map[string]any{"title": "复用", "ddl": ddlIn(time.Hour), "attachment_ids": []int64{att}}), 400, "bad_attachment")
	d := e.do("GET", "/assignments/"+i64(aid), b, nil)
	atts := d.Body["sources"].([]any)[0].(map[string]any)["attachments"].([]any)
	if len(atts) != 1 || atts[0].(map[string]any)["thumb_url"] == nil {
		t.Fatalf("attachments = %s", d.Raw)
	}
	_ = http.StatusOK
}

func TestAdminRevertClassMerge(t *testing.T) {
	e := newHW(t)
	u1, _ := e.user("一号")
	u2, u2ID := e.user("二号")
	u3, _ := e.user("三号")
	mon := map[string]any{"slots": []map[string]any{{"name": "概率论", "room": "经世楼G101", "weekday": 1, "start": 1, "end": 2}}}
	fri := map[string]any{"slots": []map[string]any{{"name": "概率论", "room": "通博楼B202", "weekday": 5, "start": 3, "end": 4}}}
	both := map[string]any{"slots": append(mon["slots"].([]map[string]any), fri["slots"].([]map[string]any)...)}
	c1 := int64(e.do("POST", "/timetable/import", u1, mon).Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	c2 := int64(e.do("POST", "/timetable/import", u2, fri).Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	aid := e.post(u2, c2, "周五的作业")
	r := e.do("POST", "/timetable/import", u3, both)
	e.mustCode(r, 200)
	if m := r.Body["merged"].([]any); len(m) != 1 {
		t.Fatalf("expected auto merge: %s", r.Raw)
	}
	if got := e.scalar(`SELECT class_id FROM assignments WHERE id = $1`, aid); got != c1 {
		t.Fatalf("assignment class = %d", got)
	}
	ms := arr(t, e.do("GET", "/admin/class-merges", e.admin, nil))
	if len(ms) != 1 {
		t.Fatalf("class merges = %v", ms)
	}
	mid := int64(ms[0]["id"].(float64))
	e.mustCode(e.do("POST", "/admin/class-merges/"+i64(mid)+"/revert", e.admin, nil), 204)
	if got := e.scalar(`SELECT class_id FROM assignments WHERE id = $1`, aid); got != c2 {
		t.Fatalf("assignment class after revert = %d", got)
	}
	if n := e.scalar(`SELECT count(*) FROM classes WHERE id = $1 AND merged_into IS NULL`, c2); n != 1 {
		t.Fatal("c2 still merged")
	}
	if n := e.scalar(`SELECT count(*) FROM class_members WHERE class_id = $1 AND user_id = $2 AND left_at IS NULL`, c2, u2ID); n != 1 {
		t.Fatal("u2 not back in c2")
	}
	if n := e.scalar(`SELECT count(*) FROM class_members WHERE class_id = $1 AND user_id = $2`, c1, u2ID); n != 0 {
		t.Fatal("u2 still in c1")
	}
	if n := e.scalar(`SELECT count(*) FROM class_slots WHERE class_id = $1`, c2); n != 1 {
		t.Fatalf("c2 slots = %d", n)
	}
	e.errCode(e.do("POST", "/admin/class-merges/"+i64(mid)+"/revert", e.admin, nil), 409, "merge_closed")
	// 作业仍可在 c2 访问
	e.mustCode(e.do("GET", "/assignments/"+i64(aid), u2, nil), 200)
}

func TestAnnouncementsNeedSevenDays(t *testing.T) {
	e := newHW(t)
	u, _ := e.user("甲同学")
	e.errCode(e.do("POST", "/admin/announcements", e.admin, map[string]any{"title": "规则", "body": "调整", "effective_at": ddlIn(24 * time.Hour)}), 400, "effective_too_soon")
	e.mustCode(e.do("POST", "/admin/announcements", e.admin, map[string]any{"title": "规则", "body": "调整", "effective_at": ddlIn(8 * 24 * time.Hour)}), 201)
	n := arr(t, e.do("GET", "/me/notices", u, nil))
	if len(n) != 1 || n[0]["kind"] != "announcement" {
		t.Fatalf("notices = %v", n)
	}
}
