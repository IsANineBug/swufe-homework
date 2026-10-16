package api

import (
	"context"
	"crypto/subtle"
	"errors"
	"net/http"
	"net/netip"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/auth"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/netx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
	"github.com/IsANineBug/swufe-homework/server/internal/timetable"
)

// BootstrapHeader 首个用户注册为管理员时携带的请求头，值为 BOOTSTRAP_ADMIN_TOKEN。
const BootstrapHeader = "X-Bootstrap-Token"

var (
	errNeedInviteOrCampus = httpx.E(http.StatusForbidden, "need_invite_or_campus", "需要邀请码，或在西财校园网内注册")
	errInviteInvalid      = httpx.E(http.StatusForbidden, "invite_invalid", "邀请码无效或已被使用")
	errNicknameTaken      = httpx.E(http.StatusConflict, "nickname_taken", "昵称已被占用")
	errBadCredentials     = httpx.E(http.StatusUnauthorized, "bad_credentials", "昵称或密码错误")
	errBadRefresh         = httpx.E(http.StatusUnauthorized, "bad_refresh_token", "登录已失效，请重新登录")
	errBadRecovery        = httpx.E(http.StatusUnauthorized, "bad_recovery_code", "昵称或恢复码错误")
	errRateLimited        = httpx.E(http.StatusTooManyRequests, "rate_limited", "操作太频繁，请稍后再试")
)

type tokens struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
}

func (s *Server) issue(ctx context.Context, q store.Q, userID int64, role string) (*tokens, error) {
	access, err := s.Tokens.Sign(userID, role)
	if err != nil {
		return nil, err
	}
	plain, hash := auth.NewRefreshToken()
	if _, err := q.Exec(ctx, `INSERT INTO auth_tokens (token_hash, user_id, expires_at) VALUES ($1, $2, $3)`,
		hash, userID, s.Now().Add(s.Cfg.RefreshTTL)); err != nil {
		return nil, err
	}
	return &tokens{AccessToken: access, RefreshToken: plain, ExpiresIn: int(s.Tokens.TTL().Seconds())}, nil
}

func (s *Server) isCampus(r *http.Request) bool {
	a, err := netip.ParseAddr(clientIP(r))
	return err == nil && netx.Contains(s.Cfg.CampusCIDRs, a)
}

func (s *Server) campusCheck(w http.ResponseWriter, r *http.Request) {
	httpx.JSON(w, http.StatusOK, map[string]bool{"campus": s.isCampus(r)})
}

// LoginFailuresPerHour 同一「昵称+IP」每小时允许的登录 / 恢复码失败次数。
const LoginFailuresPerHour = 10

// reservedNicknames 不可注册或改用的昵称（issue #4 裁决 14），按 NameKey 规范化后比对
// （忽略大小写、全半角、空格）。
var reservedNicknames = map[string]bool{
	"管理员": true, "admin": true, "administrator": true, "官方": true, "系统": true,
	"system": true, "root": true, "超级管理员": true,
}

func IsReservedNickname(n string) bool { return reservedNicknames[timetable.NameKey(n)] }

func cleanNickname(n string) (string, error) {
	n = timetable.CleanName(n)
	if l := utf8.RuneCountInString(n); l < 1 || l > 20 {
		return "", httpx.E(http.StatusBadRequest, "bad_nickname", "昵称须为 1–20 个字")
	}
	if IsReservedNickname(n) {
		return "", httpx.E(http.StatusBadRequest, "nickname_reserved", "该昵称为保留昵称，请换一个")
	}
	return n, nil
}

func failKey(kind, nick string, r *http.Request) string {
	return kind + ":" + timetable.NameKey(nick) + "|" + clientIP(r)
}

func checkPassword(pw string) error {
	if l := utf8.RuneCountInString(pw); l < 8 || l > 64 {
		return httpx.E(http.StatusBadRequest, "bad_password", "密码须为 8–64 位")
	}
	return nil
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Nickname   string  `json:"nickname"`
		Password   string  `json:"password"`
		InviteCode *string `json:"invite_code"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	nick, err := cleanNickname(in.Nickname)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := checkPassword(in.Password); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	// 宽松限流：校园网共用 NAT 出口，按 IP 每小时 REGISTER_PER_IP_HOUR 次（默认 30）
	if !s.RegLimiter.Allow("reg:" + clientIP(r)) {
		httpx.Fail(w, r, errRateLimited)
		return
	}
	code := ""
	if in.InviteCode != nil {
		code = auth.NormalizeInviteCode(*in.InviteCode)
	}
	campus := s.isCampus(r)
	bootstrap := false
	if bt := r.Header.Get(BootstrapHeader); bt != "" && s.Cfg.BootstrapAdminToken != "" &&
		subtle.ConstantTimeCompare([]byte(bt), []byte(s.Cfg.BootstrapAdminToken)) == 1 {
		bootstrap = true
	}
	if code == "" && !campus && !bootstrap {
		httpx.Fail(w, r, errNeedInviteOrCampus)
		return
	}

	// 先算哈希（较慢），再开事务
	pwHash, err := auth.HashPassword(in.Password)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	recovery := auth.NewRecoveryCode()
	recHash, err := auth.HashPassword(auth.NormalizeRecoveryCode(recovery))
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}

	ctx := r.Context()
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer tx.Rollback(ctx)

	via, role := "", "user"
	var inviter *int64
	if bootstrap {
		// 只有库里还没有任何用户时生效
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(424242)`); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		var any bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM users)`).Scan(&any); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		if !any {
			via, role = "bootstrap", "admin"
		}
	}
	if via == "" && campus {
		// 校园网优先（issue #4 裁决 13）：即使带了有效邀请码，也按校园网注册，
		// inviter_id = NULL，邀请码不消耗
		via = "campus"
	}
	if via == "" && code != "" {
		var owner int64
		err := tx.QueryRow(ctx, `SELECT ic.owner_id FROM invite_codes ic JOIN users u ON u.id = ic.owner_id
			WHERE ic.code = $1 AND ic.used_by IS NULL AND u.disabled_at IS NULL
			FOR UPDATE OF ic`, code).Scan(&owner)
		switch {
		case err == nil:
			via, inviter = "invite", &owner
		case !errors.Is(err, pgx.ErrNoRows):
			httpx.Fail(w, r, err)
			return
		}
	}
	if via == "" {
		if code != "" {
			httpx.Fail(w, r, errInviteInvalid)
		} else {
			httpx.Fail(w, r, errNeedInviteOrCampus)
		}
		return
	}

	var uid int64
	err = tx.QueryRow(ctx, `INSERT INTO users (nickname, password_hash, recovery_code_hash, inviter_id, registered_via, role)
		VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`, nick, pwHash, recHash, inviter, via, role).Scan(&uid)
	if store.PgCode(err) == "23505" {
		httpx.Fail(w, r, errNicknameTaken)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if via == "invite" {
		if _, err := tx.Exec(ctx, `UPDATE invite_codes SET used_by = $2, used_at = now() WHERE code = $1`, code, uid); err != nil {
			httpx.Fail(w, r, err)
			return
		}
	}
	t, err := s.issue(ctx, tx, uid, role)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, struct {
		*tokens
		RecoveryCode string `json:"recovery_code"`
	}{t, recovery})
}

// 昵称不存在时也跑一次 argon2，避免用响应时间探测昵称
var dummyHash, _ = auth.HashPassword("dummy-password-for-timing")

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Nickname string `json:"nickname"`
		Password string `json:"password"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	nick := timetable.CleanName(in.Nickname)
	key := failKey("login", nick, r)
	if s.FailLimiter.Blocked(key) {
		httpx.Fail(w, r, errRateLimited)
		return
	}
	var uid int64
	var hash, role string
	var disabled *time.Time
	err := s.DB.QueryRow(r.Context(), `SELECT id, password_hash, role, disabled_at FROM users WHERE nickname = $1`, nick).
		Scan(&uid, &hash, &role, &disabled)
	if errors.Is(err, pgx.ErrNoRows) {
		_, _ = auth.VerifyPassword(in.Password, dummyHash)
		s.FailLimiter.Allow(key)
		httpx.Fail(w, r, errBadCredentials)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ok, err := auth.VerifyPassword(in.Password, hash)
	if err != nil || !ok || disabled != nil {
		s.FailLimiter.Allow(key)
		httpx.Fail(w, r, errBadCredentials)
		return
	}
	t, err := s.issue(r.Context(), s.DB, uid, role)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, t)
}

// refresh 轮换：旧 refresh token 作废，返回新的一对。
func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := httpx.Decode(r, &in); err != nil || in.RefreshToken == "" {
		httpx.Fail(w, r, errBadRefresh)
		return
	}
	ctx := r.Context()
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer tx.Rollback(ctx)
	var uid int64
	var role string
	err = tx.QueryRow(ctx, `UPDATE auth_tokens t SET revoked_at = now()
		FROM users u
		WHERE t.token_hash = $1 AND t.revoked_at IS NULL AND t.expires_at > now()
		  AND u.id = t.user_id AND u.disabled_at IS NULL
		RETURNING t.user_id, u.role`, auth.HashToken(in.RefreshToken)).Scan(&uid, &role)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Fail(w, r, errBadRefresh)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	t, err := s.issue(ctx, tx, uid, role)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, t)
}

// recover 用恢复码重设密码，换新恢复码，并让所有已登录设备下线。
func (s *Server) recover(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Nickname     string `json:"nickname"`
		RecoveryCode string `json:"recovery_code"`
		NewPassword  string `json:"new_password"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := checkPassword(in.NewPassword); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	nick := timetable.CleanName(in.Nickname)
	key := failKey("recover", nick, r)
	if s.FailLimiter.Blocked(key) {
		httpx.Fail(w, r, errRateLimited)
		return
	}
	ctx := r.Context()
	var uid int64
	var recHash string
	err := s.DB.QueryRow(ctx, `SELECT id, recovery_code_hash FROM users WHERE nickname = $1 AND disabled_at IS NULL`, nick).
		Scan(&uid, &recHash)
	if errors.Is(err, pgx.ErrNoRows) {
		_, _ = auth.VerifyPassword("x", dummyHash)
		s.FailLimiter.Allow(key)
		httpx.Fail(w, r, errBadRecovery)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if ok, err := auth.VerifyPassword(auth.NormalizeRecoveryCode(in.RecoveryCode), recHash); err != nil || !ok {
		s.FailLimiter.Allow(key)
		httpx.Fail(w, r, errBadRecovery)
		return
	}
	pwHash, err := auth.HashPassword(in.NewPassword)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	newCode := auth.NewRecoveryCode()
	newRecHash, err := auth.HashPassword(auth.NormalizeRecoveryCode(newCode))
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer tx.Rollback(ctx)
	// 以旧哈希为条件，防止同一恢复码被并发使用两次
	tag, err := tx.Exec(ctx, `UPDATE users SET password_hash = $2, recovery_code_hash = $3 WHERE id = $1 AND recovery_code_hash = $4`,
		uid, pwHash, newRecHash, recHash)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if tag.RowsAffected() == 0 {
		httpx.Fail(w, r, errBadRecovery)
		return
	}
	if _, err := tx.Exec(ctx, `UPDATE auth_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, uid); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"new_recovery_code": newCode})
}

// logout（issue #4 裁决 5）：带 refresh_token 只注销本机这一个；all=true 注销本人全部设备。
// 两者都不给返回 400 bad_request。
func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	u := currentUser(r)
	var in struct {
		RefreshToken string `json:"refresh_token"`
		All          bool   `json:"all"`
	}
	if r.ContentLength != 0 {
		if err := httpx.Decode(r, &in); err != nil {
			httpx.Fail(w, r, err)
			return
		}
	}
	var err error
	switch {
	case in.All:
		_, err = s.DB.Exec(r.Context(), `UPDATE auth_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, u.ID)
	case in.RefreshToken != "":
		_, err = s.DB.Exec(r.Context(), `UPDATE auth_tokens SET revoked_at = now()
			WHERE token_hash = $1 AND user_id = $2 AND revoked_at IS NULL`, auth.HashToken(in.RefreshToken), u.ID)
	default:
		err = httpx.E(http.StatusBadRequest, "bad_request", "请提供 refresh_token，或 all=true 注销全部设备")
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
