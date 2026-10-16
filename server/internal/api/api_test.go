package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/config"
	"github.com/IsANineBug/swufe-homework/server/internal/testdb"
)

type env struct {
	t    *testing.T
	pool *pgxpool.Pool
	cfg  *config.Config
	h    http.Handler
}

// newEnv：campus 为校园网网段；请求默认来自 RemoteAddr 192.0.2.1（httptest 默认）。
func newEnv(t *testing.T, campus string) *env {
	pool := testdb.New(t)
	cfg := &config.Config{
		JWTSecret:           []byte(strings.Repeat("s", 40)),
		BootstrapAdminToken: "boot-token",
		RegisterPerIPHour:   30,
		AccessTTL:           15 * 60e9,
		RefreshTTL:          30 * 24 * 3600e9,
	}
	cfg.CampusCIDRs, _ = config.ParseCIDRs(campus)
	cfg.TrustedProxies, _ = config.ParseCIDRs("172.16.0.0/12")
	return &env{t: t, pool: pool, cfg: cfg, h: api.New(cfg, pool).Router()}
}

type resp struct {
	Code int
	Body map[string]any
	Raw  []byte
}

func (e *env) do(method, path, token string, body any, hdr ...string) resp {
	e.t.Helper()
	var buf bytes.Buffer
	if body != nil {
		_ = json.NewEncoder(&buf).Encode(body)
	}
	req := httptest.NewRequest(method, "/api/v1"+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		if hdr[i] == "RemoteAddr" {
			req.RemoteAddr = hdr[i+1]
			continue
		}
		req.Header.Set(hdr[i], hdr[i+1])
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, req)
	r := resp{Code: w.Code, Raw: w.Body.Bytes()}
	_ = json.Unmarshal(r.Raw, &r.Body)
	return r
}

func (e *env) mustCode(r resp, code int) {
	e.t.Helper()
	if r.Code != code {
		e.t.Fatalf("status %d, want %d: %s", r.Code, code, r.Raw)
	}
}

func (e *env) errCode(r resp, status int, code string) {
	e.t.Helper()
	e.mustCode(r, status)
	if r.Body["code"] != code || r.Body["message"] == "" {
		e.t.Fatalf("error body = %s, want code %s", r.Raw, code)
	}
}

func reg(nick string, code any) map[string]any {
	return map[string]any{"nickname": nick, "password": "password123", "invite_code": code}
}

func TestRegisterCampusPath(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	r := e.do("GET", "/auth/campus-check", "", nil)
	e.mustCode(r, 200)
	if r.Body["campus"] != true {
		t.Fatal("should be campus")
	}
	r = e.do("POST", "/auth/register", "", reg("小明", nil))
	e.mustCode(r, 201)
	rc, _ := r.Body["recovery_code"].(string)
	if len(rc) != 19 || r.Body["access_token"] == "" || r.Body["refresh_token"] == "" || r.Body["expires_in"] != float64(900) {
		t.Fatalf("register body: %s", r.Raw)
	}
	var via string
	var inviter *int64
	if err := e.pool.QueryRow(context.Background(), `SELECT registered_via, inviter_id FROM users WHERE nickname = '小明'`).Scan(&via, &inviter); err != nil {
		t.Fatal(err)
	}
	if via != "campus" || inviter != nil {
		t.Fatalf("via=%s inviter=%v", via, inviter)
	}
	// 昵称重复
	e.errCode(e.do("POST", "/auth/register", "", reg("小明", nil)), 409, "nickname_taken")
}

func TestRegisterOffCampusNeedsInvite(t *testing.T) {
	e := newEnv(t, "") // CAMPUS_CIDRS 为空
	e.errCode(e.do("POST", "/auth/register", "", reg("路人", nil)), 403, "need_invite_or_campus")
	e.errCode(e.do("POST", "/auth/register", "", reg("路人", "ABCDEFGH")), 403, "invite_invalid")
}

func TestRegisterInvitePathAndQuota(t *testing.T) {
	e := newEnv(t, "")
	// 首个用户用 BOOTSTRAP_ADMIN_TOKEN 成为管理员
	r := e.do("POST", "/auth/register", "", reg("九虫", nil), api.BootstrapHeader, "boot-token")
	e.mustCode(r, 201)
	admin := r.Body["access_token"].(string)
	me := e.do("GET", "/me", admin, nil)
	if me.Body["role"] != "admin" || me.Body["registered_via"] != "bootstrap" || me.Body["invites_remaining"] != float64(5) {
		t.Fatalf("me = %s", me.Raw)
	}
	// 再用 bootstrap 头注册不再生效
	e.errCode(e.do("POST", "/auth/register", "", reg("第二人", nil), api.BootstrapHeader, "boot-token"), 403, "need_invite_or_campus")

	// 生成 5 个码后名额用完（未使用的码也占名额）
	var codes []string
	for i := 0; i < 5; i++ {
		r := e.do("POST", "/me/invites", admin, nil)
		e.mustCode(r, 201)
		codes = append(codes, r.Body["code"].(string))
	}
	e.errCode(e.do("POST", "/me/invites", admin, nil), 403, "invite_quota_exhausted")

	// 用邀请码注册（小写、带空格也认）
	r = e.do("POST", "/auth/register", "", reg("新同学", " "+strings.ToLower(codes[0])+" "))
	e.mustCode(r, 201)
	var inviter *int64
	var via string
	if err := e.pool.QueryRow(context.Background(), `SELECT registered_via, inviter_id FROM users WHERE nickname = '新同学'`).Scan(&via, &inviter); err != nil {
		t.Fatal(err)
	}
	if via != "invite" || inviter == nil || *inviter != 1 {
		t.Fatalf("via=%s inviter=%v", via, inviter)
	}
	// 同一个码不能再用
	e.errCode(e.do("POST", "/auth/register", "", reg("又一个", codes[0])), 403, "invite_invalid")

	inv := e.do("GET", "/me/invites", admin, nil)
	e.mustCode(inv, 200)
	if inv.Body["remaining"] != float64(0) || !strings.Contains(string(inv.Raw), "新同学") {
		t.Fatalf("invites = %s", inv.Raw)
	}
}

func TestCampusViaTrustedProxyOnly(t *testing.T) {
	e := newEnv(t, "10.0.0.0/8")
	// 来自可信代理（nginx）的 X-Real-IP 生效
	r := e.do("GET", "/auth/campus-check", "", nil, "RemoteAddr", "172.18.0.2:4000", "X-Real-IP", "10.1.2.3")
	if r.Body["campus"] != true {
		t.Fatalf("trusted proxy: %s", r.Raw)
	}
	// 直连伪造 X-Real-IP 无效
	r = e.do("GET", "/auth/campus-check", "", nil, "RemoteAddr", "8.8.8.8:4000", "X-Real-IP", "10.1.2.3")
	if r.Body["campus"] != false {
		t.Fatalf("spoofed: %s", r.Raw)
	}
}

func TestRegisterRateLimit(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	e.cfg.RegisterPerIPHour = 2
	e.h = api.New(e.cfg, e.pool).Router()
	e.mustCode(e.do("POST", "/auth/register", "", reg("a1", nil)), 201)
	e.mustCode(e.do("POST", "/auth/register", "", reg("a2", nil)), 201)
	e.errCode(e.do("POST", "/auth/register", "", reg("a3", nil)), 429, "rate_limited")
}

func TestLoginRefreshRecoverLogout(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	r := e.do("POST", "/auth/register", "", reg("小红", nil))
	e.mustCode(r, 201)
	recovery := r.Body["recovery_code"].(string)

	e.errCode(e.do("POST", "/auth/login", "", map[string]string{"nickname": "小红", "password": "nope-nope"}), 401, "bad_credentials")
	e.errCode(e.do("POST", "/auth/login", "", map[string]string{"nickname": "不存在", "password": "password123"}), 401, "bad_credentials")
	r = e.do("POST", "/auth/login", "", map[string]string{"nickname": "小红", "password": "password123"})
	e.mustCode(r, 200)
	refresh := r.Body["refresh_token"].(string)

	// refresh 轮换：旧 token 只能用一次
	r = e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": refresh})
	e.mustCode(r, 200)
	newRefresh := r.Body["refresh_token"].(string)
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": refresh}), 401, "bad_refresh_token")
	// 库里只存哈希
	var n int
	_ = e.pool.QueryRow(context.Background(), `SELECT count(*) FROM auth_tokens WHERE token_hash = $1`, newRefresh).Scan(&n)
	if n != 0 {
		t.Fatal("refresh token stored in plain text")
	}

	// 恢复码重设密码，换新恢复码，旧 refresh 全部失效
	e.errCode(e.do("POST", "/auth/recover", "", map[string]string{"nickname": "小红", "recovery_code": "AAAA-AAAA-AAAA-AAAA", "new_password": "newpassword1"}), 401, "bad_recovery_code")
	r = e.do("POST", "/auth/recover", "", map[string]string{"nickname": "小红", "recovery_code": strings.ToLower(recovery), "new_password": "newpassword1"})
	e.mustCode(r, 200)
	if nc, _ := r.Body["new_recovery_code"].(string); len(nc) != 19 || nc == recovery {
		t.Fatalf("new code: %s", r.Raw)
	}
	e.errCode(e.do("POST", "/auth/recover", "", map[string]string{"nickname": "小红", "recovery_code": recovery, "new_password": "again12345"}), 401, "bad_recovery_code")
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": newRefresh}), 401, "bad_refresh_token")
	e.errCode(e.do("POST", "/auth/login", "", map[string]string{"nickname": "小红", "password": "password123"}), 401, "bad_credentials")
	r = e.do("POST", "/auth/login", "", map[string]string{"nickname": "小红", "password": "newpassword1"})
	e.mustCode(r, 200)
	access, rt := r.Body["access_token"].(string), r.Body["refresh_token"].(string)

	e.mustCode(e.do("POST", "/auth/logout", access, map[string]string{"refresh_token": rt}), 204)
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": rt}), 401, "bad_refresh_token")
	e.errCode(e.do("GET", "/me", "", nil), 401, "unauthorized")
}

func TestMeMetaClassesEndpoints(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	tok := e.do("POST", "/auth/register", "", reg("阿甲", nil)).Body["access_token"].(string)
	tok2 := e.do("POST", "/auth/register", "", reg("阿乙", nil)).Body["access_token"].(string)

	m := e.do("GET", "/meta", "", nil)
	e.mustCode(m, 200)
	if !strings.Contains(string(m.Raw), `"start":"08:30"`) || !strings.Contains(string(m.Raw), "+08:00") || m.Body["semester"] == nil {
		t.Fatalf("meta = %s", m.Raw)
	}

	p := e.do("PATCH", "/me", tok, map[string]any{"nickname": "阿甲甲", "leaderboard_anonymous": true})
	e.mustCode(p, 200)
	if p.Body["nickname"] != "阿甲甲" || p.Body["leaderboard_anonymous"] != true {
		t.Fatalf("patch = %s", p.Raw)
	}
	e.errCode(e.do("PATCH", "/me", tok2, map[string]any{"nickname": "阿甲甲"}), 409, "nickname_taken")

	slots := map[string]any{"slots": []map[string]any{
		{"name": "概率论与数理统计", "room": "经世楼G101", "weekday": 1, "start": 1, "end": 2},
		{"name": "概率论与数理统计", "room": "经世楼G102", "weekday": 5, "start": 3, "end": 4},
	}}
	r := e.do("POST", "/timetable/import", tok, slots)
	e.mustCode(r, 200)
	created := r.Body["created"].([]any)
	if len(created) != 1 {
		t.Fatalf("import = %s", r.Raw)
	}
	cid := int(created[0].(map[string]any)["id"].(float64))
	e.errCode(e.do("POST", "/timetable/import", tok, map[string]any{"slots": []map[string]any{{"name": "x", "room": "A", "weekday": 7, "start": 1, "end": 1}}}), 400, "invalid_slot")

	s := e.do("GET", "/classes/search?q="+"概率论", tok2, nil)
	e.mustCode(s, 200)
	if !strings.Contains(string(s.Raw), "概率论与数理统计") {
		t.Fatalf("search = %s", s.Raw)
	}
	s = e.do("GET", "/classes/search?q=%25", tok2, nil)
	e.mustCode(s, 200)

	g := e.do("GET", "/classes/"+itoa(cid), tok2, nil)
	e.mustCode(g, 200)
	if g.Body["member_count"] != float64(1) {
		t.Fatalf("class = %s", g.Raw)
	}
	e.errCode(e.do("GET", "/classes/99999", tok2, nil), 404, "not_found")

	mc := e.do("GET", "/me/classes", tok, nil)
	if !strings.Contains(string(mc.Raw), "经世楼G102") {
		t.Fatalf("me/classes = %s", mc.Raw)
	}
	e.mustCode(e.do("DELETE", "/me/classes/"+itoa(cid), tok, nil), 204)
	mc = e.do("GET", "/me/classes", tok, nil)
	if string(bytes.TrimSpace(mc.Raw)) != "[]" {
		t.Fatalf("after leave = %s", mc.Raw)
	}

	n := e.do("GET", "/me/notices", tok, nil)
	e.mustCode(n, 200)
	if string(bytes.TrimSpace(n.Raw)) != "[]" {
		t.Fatalf("notices = %s", n.Raw)
	}
}

func TestNoSemester(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	_, _ = e.pool.Exec(context.Background(), `UPDATE semesters SET is_current = false`)
	tok := e.do("POST", "/auth/register", "", reg("阿丙", nil)).Body["access_token"].(string)
	e.errCode(e.do("POST", "/timetable/import", tok, map[string]any{"slots": []map[string]any{{"name": "x", "room": "A", "weekday": 1, "start": 1, "end": 1}}}), 409, "no_semester")
}

func itoa(i int) string { return jsonInt(i) }

func jsonInt(i int) string { b, _ := json.Marshal(i); return string(b) }

// 校园网 + 有效邀请码同时满足：按校园网注册，inviter_id = NULL，邀请码不消耗（issue #4 裁决 13）。
func TestRegisterCampusWinsOverInvite(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	tok := e.do("POST", "/auth/register", "", reg("邀请人", nil)).Body["access_token"].(string)
	code := e.do("POST", "/me/invites", tok, nil).Body["code"].(string)
	e.mustCode(e.do("POST", "/auth/register", "", reg("校园同学", code)), 201)
	var via string
	var inviter *int64
	if err := e.pool.QueryRow(context.Background(), `SELECT registered_via, inviter_id FROM users WHERE nickname = '校园同学'`).Scan(&via, &inviter); err != nil {
		t.Fatal(err)
	}
	if via != "campus" || inviter != nil {
		t.Fatalf("via=%s inviter=%v", via, inviter)
	}
	inv := e.do("GET", "/me/invites", tok, nil)
	if strings.Contains(string(inv.Raw), "校园同学") {
		t.Fatalf("invite code should stay unused: %s", inv.Raw)
	}
}

// 保留昵称不可注册或改用，规范化后比对（issue #4 裁决 14）。
func TestReservedNicknames(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	for _, n := range []string{"管理员", "管 理员", "Ａｄｍｉｎ", " ADMIN ", "官方", "系统"} {
		e.errCode(e.do("POST", "/auth/register", "", reg(n, nil)), 400, "nickname_reserved")
	}
	tok := e.do("POST", "/auth/register", "", reg("普通人", nil)).Body["access_token"].(string)
	e.errCode(e.do("PATCH", "/me", tok, map[string]any{"nickname": "admin"}), 400, "nickname_reserved")
	e.mustCode(e.do("PATCH", "/me", tok, map[string]any{"nickname": "管理员小助手"}), 200)
}

// 登录限流按「昵称+IP」只计失败：别人刷错密码不影响本人从另一个 IP 登录，成功登录不计数。
func TestLoginFailureLimitPerNicknameAndIP(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	e.mustCode(e.do("POST", "/auth/register", "", reg("小绿", nil)), 201)
	good := map[string]string{"nickname": "小绿", "password": "password123"}
	for i := 0; i < api.LoginFailuresPerHour+5; i++ { // 成功登录不计数
		e.mustCode(e.do("POST", "/auth/login", "", good), 200)
	}
	bad := map[string]string{"nickname": "小绿", "password": "wrong-password"}
	for i := 0; i < api.LoginFailuresPerHour; i++ {
		e.errCode(e.do("POST", "/auth/login", "", bad, "RemoteAddr", "203.0.113.9:1"), 401, "bad_credentials")
	}
	e.errCode(e.do("POST", "/auth/login", "", good, "RemoteAddr", "203.0.113.9:1"), 429, "rate_limited")
	e.mustCode(e.do("POST", "/auth/login", "", good), 200) // 本人另一个 IP 不受影响
}

// logout：refresh_token 只注销本机；all=true 注销全部；都不给 400（issue #4 裁决 5）。
func TestLogoutThisDeviceOrAll(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	e.mustCode(e.do("POST", "/auth/register", "", reg("小蓝", nil)), 201)
	login := func() (string, string) {
		r := e.do("POST", "/auth/login", "", map[string]string{"nickname": "小蓝", "password": "password123"})
		e.mustCode(r, 200)
		return r.Body["access_token"].(string), r.Body["refresh_token"].(string)
	}
	a1, r1 := login()
	_, r2 := login()
	e.errCode(e.do("POST", "/auth/logout", a1, nil), 400, "bad_request")
	e.mustCode(e.do("POST", "/auth/logout", a1, map[string]string{"refresh_token": r1}), 204)
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": r1}), 401, "bad_refresh_token")
	r := e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": r2}) // 另一台设备还在
	e.mustCode(r, 200)
	r3 := r.Body["refresh_token"].(string)
	_, r4 := login()
	e.mustCode(e.do("POST", "/auth/logout", a1, map[string]any{"all": true}), 204)
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": r3}), 401, "bad_refresh_token")
	e.errCode(e.do("POST", "/auth/refresh", "", map[string]string{"refresh_token": r4}), 401, "bad_refresh_token")
}

// 退班后导入不自动加回（skipped），可手动加入；已合并的班返回保留班并带 merged_from。
func TestLeaveSkipJoinAndMergedFrom(t *testing.T) {
	e := newEnv(t, "192.0.2.0/24")
	tok := e.do("POST", "/auth/register", "", reg("阿丁", nil)).Body["access_token"].(string)
	slots := map[string]any{"slots": []map[string]any{{"name": "会计学", "room": "通博楼B101", "weekday": 2, "start": 1, "end": 2}}}
	r := e.do("POST", "/timetable/import", tok, slots)
	cid := int(r.Body["created"].([]any)[0].(map[string]any)["id"].(float64))
	e.mustCode(e.do("DELETE", "/me/classes/"+itoa(cid), tok, nil), 204)

	r = e.do("POST", "/timetable/import", tok, slots)
	e.mustCode(r, 200)
	if len(r.Body["joined"].([]any)) != 0 || len(r.Body["skipped"].([]any)) != 1 {
		t.Fatalf("reimport = %s", r.Raw)
	}
	j := e.do("POST", "/classes/"+itoa(cid)+"/join", tok, nil)
	e.mustCode(j, 200)
	if j.Body["member_count"] != float64(1) {
		t.Fatalf("join = %s", j.Raw)
	}
	if mc := e.do("GET", "/me/classes", tok, nil); !strings.Contains(string(mc.Raw), "会计学") {
		t.Fatalf("me/classes = %s", mc.Raw)
	}
	e.errCode(e.do("POST", "/classes/99999/join", tok, nil), 404, "not_found")

	// 手工造一个已合并的班
	var old int64
	if err := e.pool.QueryRow(context.Background(), `INSERT INTO classes (semester_id, name, created_by, merged_into)
		SELECT semester_id, name, created_by, id FROM classes WHERE id = $1 RETURNING id`, cid).Scan(&old); err != nil {
		t.Fatal(err)
	}
	g := e.do("GET", "/classes/"+jsonInt(int(old)), tok, nil)
	e.mustCode(g, 200)
	if g.Body["id"] != float64(cid) || g.Body["merged_from"] != float64(old) {
		t.Fatalf("merged class = %s", g.Raw)
	}
	g = e.do("GET", "/classes/"+itoa(cid), tok, nil)
	if _, ok := g.Body["merged_from"]; !ok || g.Body["merged_from"] != nil {
		t.Fatalf("merged_from should be null: %s", g.Raw)
	}
}
