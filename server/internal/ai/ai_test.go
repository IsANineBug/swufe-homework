package ai_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/auth"
	"github.com/IsANineBug/swufe-homework/server/internal/config"
	"github.com/IsANineBug/swufe-homework/server/internal/testdb"
)

const workerToken = "worker-token-0123456789abcdef"

type env struct {
	t    *testing.T
	pool *pgxpool.Pool
	srv  *api.Server
	h    http.Handler
	iss  *auth.Issuer
	now  time.Time
}

func newEnv(t *testing.T) *env {
	pool := testdb.New(t)
	cfg := &config.Config{
		JWTSecret:     []byte(strings.Repeat("s", 40)),
		AccessTTL:     15 * time.Minute,
		RefreshTTL:    30 * 24 * time.Hour,
		WorkerToken:   workerToken,
		FileURLSecret: []byte("file-secret"),
	}
	srv := api.New(cfg, pool)
	e := &env{t: t, pool: pool, srv: srv, h: srv.Router(), iss: auth.NewIssuer(cfg.JWTSecret, cfg.AccessTTL)}
	// 2026-10-16 周五 10:00 北京时间
	e.now = time.Date(2026, 10, 16, 2, 0, 0, 0, time.UTC)
	srv.AI.Now = func() time.Time { return e.now }
	srv.AI.PollEvery = 50 * time.Millisecond
	srv.AI.ClaimWait = 200 * time.Millisecond
	return e
}

type resp struct {
	Code int
	Body map[string]any
	Raw  []byte
}

func (e *env) do(method, path, token string, body any) resp {
	e.t.Helper()
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, "/api/v1"+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, req)
	r := resp{Code: w.Code, Raw: w.Body.Bytes()}
	_ = json.Unmarshal(r.Raw, &r.Body)
	return r
}

func (e *env) want(r resp, status int, code string) {
	e.t.Helper()
	if r.Code != status {
		e.t.Fatalf("status %d, want %d: %s", r.Code, status, r.Raw)
	}
	if code != "" && r.Body["code"] != code {
		e.t.Fatalf("code = %v, want %s: %s", r.Body["code"], code, r.Raw)
	}
}

func (e *env) user(nick string) (int64, string) {
	e.t.Helper()
	id := testdb.User(e.t, e.pool, nick)
	tok, err := e.iss.Sign(id, "user")
	if err != nil {
		e.t.Fatal(err)
	}
	return id, tok
}

func (e *env) exec(sql string, args ...any) {
	e.t.Helper()
	if _, err := e.pool.Exec(context.Background(), sql, args...); err != nil {
		e.t.Fatal(err)
	}
}

func (e *env) id(sql string, args ...any) int64 {
	e.t.Helper()
	var id int64
	if err := e.pool.QueryRow(context.Background(), sql, args...).Scan(&id); err != nil {
		e.t.Fatal(err)
	}
	return id
}

// class 建一个概率论班（周一 6–7、周五 5–6 经世楼G101），members 为在班成员。
func (e *env) class(members ...int64) int64 {
	cid := e.id(`INSERT INTO classes (semester_id, name, created_by) VALUES (1, '概率论与数理统计B', $1) RETURNING id`, members[0])
	e.exec(`INSERT INTO class_slots (class_id, semester_id, room, weekday, start_period, end_period)
		VALUES ($1, 1, '经世楼G101', 1, 6, 7), ($1, 1, '经世楼G101', 5, 5, 6)`, cid)
	for _, m := range members {
		e.exec(`INSERT INTO class_members (class_id, user_id) VALUES ($1, $2)`, cid, m)
	}
	return cid
}

func (e *env) heartbeat() {
	e.t.Helper()
	e.want(e.do("POST", "/worker/heartbeat", workerToken, map[string]any{"worker_id": "pc", "model": "deepseek"}), 204, "")
}

func (e *env) claim() resp {
	e.t.Helper()
	return e.do("POST", "/worker/jobs/claim", workerToken, nil)
}

func TestOfflineNoQueue(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	r := e.do("GET", "/ai/status", tok, nil)
	e.want(r, 200, "")
	if r.Body["online"] != false || r.Body["last_seen"] != nil {
		t.Fatalf("status = %s", r.Raw)
	}
	e.want(e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "第三章习题"}}), 409, "ai_offline")
	// 心跳 61 秒后算离线
	e.heartbeat()
	e.now = e.now.Add(61 * time.Second)
	if r := e.do("GET", "/ai/status", tok, nil); r.Body["online"] != false || r.Body["last_seen"] == nil {
		t.Fatalf("status = %s", r.Raw)
	}
	e.want(e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "x"}}), 409, "ai_offline")
	var n int
	_ = e.pool.QueryRow(context.Background(), `SELECT count(*) FROM ai_jobs`).Scan(&n)
	if n != 0 {
		t.Fatalf("offline jobs queued: %d", n)
	}
}

func TestWorkerAuth(t *testing.T) {
	e := newEnv(t)
	_, tok := e.user("小明")
	e.want(e.do("POST", "/worker/heartbeat", "", map[string]any{"worker_id": "pc"}), 401, "unauthorized")
	e.want(e.do("POST", "/worker/heartbeat", "wrong", map[string]any{"worker_id": "pc"}), 401, "unauthorized")
	e.want(e.do("POST", "/worker/jobs/claim", tok, nil), 401, "unauthorized") // 用户 token 不能当 worker
	e.want(e.do("GET", "/ai/status", "", nil), 401, "unauthorized")
	// 未配置 WORKER_TOKEN：一律 401
	e.srv.AI.WorkerToken = ""
	e.h = e.srv.Router()
	e.want(e.do("POST", "/worker/heartbeat", "", map[string]any{"worker_id": "pc"}), 401, "unauthorized")
}

func TestParseAssignmentFlow(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	_, other := e.user("小红")
	cid := e.class(uid)
	e.heartbeat()
	if r := e.do("GET", "/ai/status", tok, nil); r.Body["online"] != true {
		t.Fatalf("status = %s", r.Raw)
	}
	r := e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "parse_assignment", "class_id": cid,
		"input": map[string]any{"text": "第三章习题 3.1-3.8，下节课前交"}})
	e.want(r, 202, "")
	if r.Body["status"] != "queued" || r.Body["input"] != nil {
		t.Fatalf("create = %s", r.Raw)
	}
	jobID := int64(r.Body["id"].(float64))
	path := "/ai/jobs/" + itoa(jobID)
	// 别人看不到
	e.want(e.do("GET", path, other, nil), 404, "not_found")

	c := e.claim()
	e.want(c, 200, "")
	in := c.Body["input"].(map[string]any)
	ctx := in["context"].(map[string]any)
	if ctx["today"] != "2026-10-16" || ctx["weekday"] != float64(5) || ctx["timezone"] != "Asia/Shanghai" ||
		len(ctx["periods"].([]any)) != 12 || in["text"] != "第三章习题 3.1-3.8，下节课前交" || in["image_urls"] != nil {
		t.Fatalf("claim input = %s", c.Raw)
	}
	cls := ctx["class"].(map[string]any)
	if cls["name"] != "概率论与数理统计B" || len(cls["slots"].([]any)) != 2 {
		t.Fatalf("context.class = %v", cls)
	}
	if r := e.do("GET", path, tok, nil); r.Body["status"] != "running" {
		t.Fatalf("get = %s", r.Raw)
	}
	out := map[string]any{"title": "第三章习题 3.1–3.8", "requirements": nil, "ddl": "2026-10-19T13:55:00+08:00",
		"ddl_source": "下节课前", "submit_method": nil, "uncertain": []string{}}
	e.want(e.do("POST", "/worker/jobs/"+itoa(jobID)+"/result", workerToken, map[string]any{"status": "done", "output": out}), 204, "")
	g := e.do("GET", path, tok, nil)
	if g.Body["status"] != "done" || g.Body["output"].(map[string]any)["ddl"] != "2026-10-19T13:55:00+08:00" || g.Body["input"] != nil {
		t.Fatalf("get = %s", g.Raw)
	}
	// 重复回传
	e.want(e.do("POST", "/worker/jobs/"+itoa(jobID)+"/result", workerToken, map[string]any{"status": "done", "output": out}), 409, "job_not_running")
	// 无任务时 204
	if c := e.claim(); c.Code != 204 {
		t.Fatalf("claim = %d %s", c.Code, c.Raw)
	}
}

func TestCreateValidation(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	stranger, stok := e.user("路人")
	cid := e.class(uid)
	e.heartbeat()
	post := func(tok string, body map[string]any) resp { return e.do("POST", "/ai/jobs", tok, body) }
	// 不是班级成员
	e.want(post(stok, map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "x"}}), 403, "not_member")
	// 缺 class_id、空内容、只由服务器发起的种类
	e.want(post(tok, map[string]any{"kind": "parse_assignment", "input": map[string]any{"text": "x"}}), 400, "bad_input")
	e.want(post(tok, map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "  "}}), 400, "bad_input")
	e.want(post(tok, map[string]any{"kind": "reminder_text", "input": map[string]any{}}), 400, "bad_input")
	e.want(post(tok, map[string]any{"kind": "ddl_conflict", "input": map[string]any{}}), 400, "bad_input")
	// 客户端传 URL / context 一律拒绝（裁决 6）
	e.want(post(tok, map[string]any{"kind": "parse_timetable", "input": map[string]any{"image_urls": []string{"http://169.254.169.254/"}}}), 400, "bad_input")
	e.want(post(tok, map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "x", "context": map[string]any{"today": "2020-01-01"}}}), 400, "bad_input")
	// 别人的附件、PDF、不存在的附件
	mine := e.id(`INSERT INTO attachments (uploader_id, semester_id, kind, path, bytes) VALUES ($1, 1, 'image', '2026-2027-1/2026-10/a.jpg', 100) RETURNING id`, uid)
	theirs := e.id(`INSERT INTO attachments (uploader_id, semester_id, kind, path, bytes) VALUES ($1, 1, 'image', '2026-2027-1/2026-10/b.jpg', 100) RETURNING id`, stranger)
	pdf := e.id(`INSERT INTO attachments (uploader_id, semester_id, kind, path, bytes) VALUES ($1, 1, 'file', '2026-2027-1/2026-10/c.pdf', 100) RETURNING id`, uid)
	e.want(post(tok, map[string]any{"kind": "parse_timetable", "input": map[string]any{"attachment_ids": []int64{theirs}}}), 400, "bad_attachment")
	e.want(post(tok, map[string]any{"kind": "parse_timetable", "input": map[string]any{"attachment_ids": []int64{pdf}}}), 400, "bad_attachment")
	e.want(post(tok, map[string]any{"kind": "parse_timetable", "input": map[string]any{"attachment_ids": []int64{mine, mine}}}), 400, "bad_attachment")
	e.want(post(tok, map[string]any{"kind": "parse_timetable", "input": map[string]any{"attachment_ids": []int64{}}}), 400, "bad_input")
	// 正常：签名链接
	r := post(tok, map[string]any{"kind": "parse_timetable", "class_id": nil, "input": map[string]any{"attachment_ids": []int64{mine}}})
	e.want(r, 202, "")
	c := e.claim()
	in := c.Body["input"].(map[string]any)
	if in["context"].(map[string]any)["class"] != nil {
		t.Fatalf("parse_timetable context has class: %s", c.Raw)
	}
	urls := in["image_urls"].([]any)
	u, err := url.Parse(urls[0].(string))
	if err != nil || len(urls) != 1 || !strings.HasPrefix(u.Path, "/files/2026-2027-1/2026-10/a.jpg") {
		t.Fatalf("image_urls = %v", urls)
	}
	// 与 WS2 的 /files 处理器同一个签名器：能验签通过
	if !e.srv.Files.Verify(strings.TrimPrefix(u.Path, "/files/"), u.Query().Get("exp"), u.Query().Get("sig"), e.now) {
		t.Fatalf("WS2 Signer 验签失败: %s", u)
	}
}

func TestDedupeCandidatesFromServer(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	other := e.id(`INSERT INTO classes (semester_id, name, created_by) VALUES (1, '线性代数', $1) RETURNING id`, uid)
	ddl := time.Date(2026, 10, 23, 13, 55, 0, 0, time.FixedZone("CST", 8*3600))
	a1 := e.id(`INSERT INTO assignments (class_id, title, ddl, created_by) VALUES ($1, '第三章习题', $2, $3) RETURNING id`, cid, ddl, uid)
	e.exec(`INSERT INTO assignment_sources (assignment_id, original_assignment_id, author_id, kind, body) VALUES ($1, $1, $2, 'first', '3.1-3.8 手写')`, a1, uid)
	a2 := e.id(`INSERT INTO assignments (class_id, title, ddl, created_by) VALUES ($1, '别班作业', $2, $3) RETURNING id`, other, ddl, uid)
	e.heartbeat()
	draft := map[string]any{"title": "概率论第三章", "body": "习题", "ddl": "2026-10-23T13:55:00+08:00"}
	// 只有别班的候选 → 400
	e.want(e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "dedupe_review", "class_id": cid,
		"input": map[string]any{"draft": draft, "candidate_ids": []int64{a2}}}), 400, "bad_input")
	e.want(e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "dedupe_review", "class_id": cid,
		"input": map[string]any{"draft": draft, "candidate_ids": []int64{a1, a2}}}), 202, "")
	c := e.claim()
	cands := c.Body["input"].(map[string]any)["candidates"].([]any)
	if len(cands) != 1 {
		t.Fatalf("candidates = %v", cands)
	}
	c0 := cands[0].(map[string]any)
	if c0["id"] != float64(a1) || c0["body"] != "3.1-3.8 手写" || c0["ddl"] != "2026-10-23T13:55:00+08:00" {
		t.Fatalf("candidate = %v", c0)
	}
}

func TestExpiryAndLimits(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	e.heartbeat()
	body := map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "x"}}
	var ids []int64
	for i := 0; i < 3; i++ {
		r := e.do("POST", "/ai/jobs", tok, body)
		e.want(r, 202, "")
		ids = append(ids, int64(r.Body["id"].(float64)))
	}
	e.want(e.do("POST", "/ai/jobs", tok, body), 429, "ai_busy")
	c := e.claim()
	e.want(c, 200, "")
	// 2 分钟后过期：worker 迟到的结果被拒，App 看到 expired
	e.now = e.now.Add(2*time.Minute + time.Second)
	e.heartbeat()
	if r := e.do("GET", "/ai/jobs/"+itoa(ids[0]), tok, nil); r.Body["status"] != "expired" {
		t.Fatalf("get = %s", r.Raw)
	}
	e.want(e.do("POST", "/worker/jobs/"+itoa(ids[0])+"/result", workerToken, map[string]any{"status": "done", "output": map[string]any{}}), 409, "job_not_running")
	if c := e.claim(); c.Code != 204 {
		t.Fatalf("expired job claimed: %s", c.Raw)
	}
	// 过期后不再占进行中名额
	e.want(e.do("POST", "/ai/jobs", tok, body), 202, "")
	// 每小时上限
	e.exec(`INSERT INTO ai_jobs (kind, status, requested_by, input, created_at, expires_at)
		SELECT 'spam_check', 'done', $1, '{}', $2, $2 FROM generate_series(1, 60)`, uid, e.now.Add(-time.Minute))
	e.want(e.do("POST", "/ai/jobs", tok, body), 429, "ai_rate_limited")
}

func TestFailedResultAndBadOutput(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	e.heartbeat()
	r := e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "spam_check", "class_id": cid, "input": map[string]any{"title": "a", "body": "b"}})
	e.want(r, 202, "")
	id := itoa(int64(r.Body["id"].(float64)))
	e.want(e.claim(), 200, "")
	e.want(e.do("POST", "/worker/jobs/"+id+"/result", workerToken, map[string]any{"status": "done", "output": []int{1}}), 400, "bad_output")
	e.want(e.do("POST", "/worker/jobs/"+id+"/result", workerToken, map[string]any{"status": "failed", "error": "schema: missing suspicious"}), 204, "")
	g := e.do("GET", "/ai/jobs/"+id, tok, nil)
	if g.Body["status"] != "failed" || g.Body["output"] != nil || g.Body["error"] != "schema: missing suspicious" {
		t.Fatalf("get = %s", g.Raw)
	}
}

func TestReminderTextWriteback(t *testing.T) {
	e := newEnv(t)
	uid, _ := e.user("小明")
	cid := e.class(uid)
	ddl := time.Date(2026, 10, 23, 13, 55, 0, 0, time.FixedZone("CST", 8*3600))
	created := time.Now().Add(-time.Hour)
	aid := e.id(`INSERT INTO assignments (class_id, title, ddl, created_by, updated_at) VALUES ($1, '第三章习题', $2, $3, $4) RETURNING id`, cid, ddl, uid, created)
	ctx := context.Background()
	// 离线：什么也不做
	if err := e.srv.AI.RequestReminderTexts(ctx, aid); err != nil {
		t.Fatal(err)
	}
	e.heartbeat()
	if err := e.srv.AI.RequestReminderTexts(ctx, aid); err != nil {
		t.Fatal(err)
	}
	slots := map[string]string{}
	for i := 0; i < 3; i++ {
		c := e.claim()
		e.want(c, 200, "")
		in := c.Body["input"].(map[string]any)
		if in["assignment"].(map[string]any)["class_name"] != "概率论与数理统计B" || in["context"] != nil {
			t.Fatalf("reminder input = %s", c.Raw)
		}
		slots[in["slot"].(string)] = itoa(int64(c.Body["id"].(float64)))
	}
	if len(slots) != 3 {
		t.Fatalf("slots = %v", slots)
	}
	res := func(id string, out any) resp {
		return e.do("POST", "/worker/jobs/"+id+"/result", workerToken, map[string]any{"status": "done", "output": out})
	}
	e.want(res(slots["2d"], map[string]any{"title": "概率论作业后天截止", "body": "第三章习题，周五 13:55 前交"}), 204, "")
	// 超过 40 字：拒收并记 failed，不写回
	e.want(res(slots["1d"], map[string]any{"title": strings.Repeat("长", 41), "body": "x"}), 400, "bad_output")
	var raw []byte
	var updated time.Time
	_ = e.pool.QueryRow(ctx, `SELECT reminder_text, updated_at FROM assignments WHERE id = $1`, aid).Scan(&raw, &updated)
	var got map[string]map[string]string
	_ = json.Unmarshal(raw, &got)
	if len(got) != 1 || got["2d"]["title"] != "概率论作业后天截止" || !updated.After(created) {
		t.Fatalf("reminder_text = %s updated_at=%v（须刷新供 /sync 下发）", raw, updated)
	}
	var st string
	_ = e.pool.QueryRow(ctx, `SELECT status FROM ai_jobs WHERE id = $1::bigint`, slots["1d"]).Scan(&st)
	if st != "failed" {
		t.Fatalf("bad output job status = %s", st)
	}
	// DDL 变了：旧任务的结果不写回
	e.exec(`UPDATE assignments SET ddl = ddl + interval '1 day' WHERE id = $1`, aid)
	e.want(res(slots["12h"], map[string]any{"title": "概率论作业今晚截止", "body": "第三章习题"}), 204, "")
	_ = e.pool.QueryRow(ctx, `SELECT reminder_text FROM assignments WHERE id = $1`, aid).Scan(&raw)
	got = nil
	_ = json.Unmarshal(raw, &got)
	if _, ok := got["12h"]; ok {
		t.Fatalf("stale reminder written: %s", raw)
	}
}

func TestDDLConflictOnlyMarksRed(t *testing.T) {
	e := newEnv(t)
	uid, _ := e.user("小明")
	cid := e.class(uid)
	ddl := time.Date(2026, 10, 23, 13, 55, 0, 0, time.FixedZone("CST", 8*3600))
	created := time.Now().Add(-time.Hour)
	aid := e.id(`INSERT INTO assignments (class_id, title, ddl, created_by, updated_at) VALUES ($1, '第三章习题', $2, $3, $4) RETURNING id`, cid, ddl, uid, created)
	e.exec(`INSERT INTO assignment_sources (assignment_id, original_assignment_id, author_id, kind, body) VALUES ($1, $1, $2, 'first', '周五上课前交')`, aid, uid)
	ctx := context.Background()
	e.heartbeat()
	if err := e.srv.AI.RequestDDLConflict(ctx, aid); err != nil { // 只有一条来源：不发起
		t.Fatal(err)
	}
	if c := e.claim(); c.Code != 204 {
		t.Fatalf("single source job: %s", c.Raw)
	}
	e.exec(`INSERT INTO assignment_sources (assignment_id, original_assignment_id, author_id, kind, body) VALUES ($1, $1, $2, 'supplement', '老师说改到周六晚上')`, aid, uid)
	if err := e.srv.AI.RequestDDLConflict(ctx, aid); err != nil {
		t.Fatal(err)
	}
	c := e.claim()
	e.want(c, 200, "")
	if n := len(c.Body["input"].(map[string]any)["sources"].([]any)); n != 2 {
		t.Fatalf("sources = %d", n)
	}
	out := map[string]any{"conflict": true, "values": []string{"2026-10-23T13:55:00+08:00", "2026-10-24T23:59:00+08:00"}, "note": "补充里写的是周六晚上"}
	e.want(e.do("POST", "/worker/jobs/"+itoa(int64(c.Body["id"].(float64)))+"/result", workerToken, map[string]any{"status": "done", "output": out}), 204, "")
	var conflict bool
	var got, updated time.Time
	_ = e.pool.QueryRow(ctx, `SELECT ddl_conflict, ddl, updated_at FROM assignments WHERE id = $1`, aid).Scan(&conflict, &got, &updated)
	if !conflict || !got.Equal(ddl) || !updated.After(created) {
		t.Fatalf("conflict=%v ddl=%v updated_at=%v（DDL 不应被改，updated_at 须刷新）", conflict, got, updated)
	}
}

func TestClaimLongPollWakesOnNewJob(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	e.heartbeat()
	e.srv.AI.PollEvery = time.Hour // 只能靠唤醒
	e.srv.AI.ClaimWait = 10 * time.Second
	done := make(chan resp, 1)
	go func() { done <- e.claim() }()
	time.Sleep(100 * time.Millisecond)
	e.want(e.do("POST", "/ai/jobs", tok, map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "x"}}), 202, "")
	select {
	case c := <-done:
		if c.Code != 200 {
			t.Fatalf("claim = %d %s", c.Code, c.Raw)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("long poll not woken")
	}
}

func itoa(n int64) string { b, _ := json.Marshal(n); return string(b) }

// WS2 的写接口在事务提交后触发 AI：创建作业 → 3 条提醒文案；新增补充 → DDL 冲突检查。
func TestWS2WriteTriggersAI(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	ddl := "2026-12-23T13:55:00+08:00"
	// 离线：照常发布，不留任务
	r := e.do("POST", "/classes/"+itoa(cid)+"/assignments", tok, map[string]any{"title": "第三章习题", "body": "3.1-3.8", "ddl": ddl})
	e.want(r, 201, "")
	var n int
	_ = e.pool.QueryRow(context.Background(), `SELECT count(*) FROM ai_jobs`).Scan(&n)
	if n != 0 {
		t.Fatalf("offline jobs: %d", n)
	}
	e.heartbeat()
	r = e.do("POST", "/classes/"+itoa(cid)+"/assignments", tok, map[string]any{"title": "第四章习题", "body": "4.1-4.5", "ddl": ddl})
	e.want(r, 201, "")
	aid := int64(r.Body["id"].(float64))
	kinds := map[string]int{}
	for i := 0; i < 3; i++ {
		c := e.claim()
		e.want(c, 200, "")
		kinds[c.Body["kind"].(string)]++
	}
	if kinds["reminder_text"] != 3 {
		t.Fatalf("kinds = %v", kinds)
	}
	e.want(e.do("POST", "/assignments/"+itoa(aid)+"/sources", tok, map[string]any{"body": "老师说改到周六晚上"}), 201, "")
	c := e.claim()
	e.want(c, 200, "")
	if c.Body["kind"] != "ddl_conflict" {
		t.Fatalf("after supplement: %s", c.Raw)
	}
	// 改 DDL：重新生成提醒文案
	e.want(e.do("PATCH", "/assignments/"+itoa(aid), tok, map[string]any{"ddl": "2026-12-24T23:59:00+08:00"}), 200, "")
	for i := 0; i < 3; i++ {
		if c := e.claim(); c.Body["kind"] != "reminder_text" {
			t.Fatalf("after ddl change: %s", c.Raw)
		}
	}
}

// 同一人并发发起：advisory 锁保证进行中不超过 3 个。
func TestConcurrentCreateRespectsLimit(t *testing.T) {
	e := newEnv(t)
	uid, tok := e.user("小明")
	cid := e.class(uid)
	e.heartbeat()
	codes := make(chan int, 10)
	for i := 0; i < 10; i++ {
		go func() {
			var buf bytes.Buffer
			_ = json.NewEncoder(&buf).Encode(map[string]any{"kind": "parse_assignment", "class_id": cid, "input": map[string]any{"text": "x"}})
			req := httptest.NewRequest("POST", "/api/v1/ai/jobs", &buf)
			req.Header.Set("Authorization", "Bearer "+tok)
			w := httptest.NewRecorder()
			e.h.ServeHTTP(w, req)
			codes <- w.Code
		}()
	}
	ok := 0
	for i := 0; i < 10; i++ {
		if <-codes == 202 {
			ok++
		}
	}
	if ok != 3 {
		t.Fatalf("accepted %d, want 3", ok)
	}
}
