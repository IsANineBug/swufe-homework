package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/auth"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

var errInviteQuota = httpx.E(http.StatusForbidden, "invite_quota_exhausted", "邀请名额已用完（每人 5 个，未使用的码也占名额）")

type period struct {
	No    int    `json:"no"`
	Start string `json:"start"`
	End   string `json:"end"`
}

func (s *Server) meta(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	sem, err := store.CurrentSemester(ctx, s.DB)
	if err != nil && !errors.Is(err, store.ErrNoSemester) {
		httpx.Fail(w, r, err)
		return
	}
	rows, err := s.DB.Query(ctx, `SELECT no, to_char(starts_at, 'HH24:MI'), to_char(ends_at, 'HH24:MI') FROM periods ORDER BY no`)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	ps := []period{}
	for rows.Next() {
		var p period
		if err := rows.Scan(&p.No, &p.Start, &p.End); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		ps = append(ps, p)
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"semester":    sem, // 没有当前学期时为 null
		"periods":     ps,
		"server_time": httpx.Time(s.Now()),
	})
}

type me struct {
	ID                   int64  `json:"id"`
	Nickname             string `json:"nickname"`
	Role                 string `json:"role"`
	RegisteredVia        string `json:"registered_via"`
	LeaderboardAnonymous bool   `json:"leaderboard_anonymous"`
	InvitesRemaining     int    `json:"invites_remaining"`
	// v2：当前生效的管理员禁言 / 全站禁发
	Restrictions []restriction `json:"restrictions"`
}

func loadMe(ctx context.Context, q store.Q, id int64) (*me, error) {
	var m me
	err := q.QueryRow(ctx, `SELECT u.id, u.nickname, u.role, u.registered_via, u.leaderboard_anonymous,
		GREATEST(u.invite_quota - (SELECT count(*) FROM invite_codes WHERE owner_id = u.id), 0)
		FROM users u WHERE u.id = $1`, id).
		Scan(&m.ID, &m.Nickname, &m.Role, &m.RegisteredVia, &m.LeaderboardAnonymous, &m.InvitesRemaining)
	return &m, err
}

func (s *Server) getMe(w http.ResponseWriter, r *http.Request) {
	m, err := loadMe(r.Context(), s.DB, currentUser(r).ID)
	if err == nil {
		m.Restrictions, err = loadRestrictions(r.Context(), s.DB, m.ID, s.Now())
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, m)
}

func (s *Server) patchMe(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Nickname             *string `json:"nickname"`
		LeaderboardAnonymous *bool   `json:"leaderboard_anonymous"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var nick *string
	if in.Nickname != nil {
		n, err := cleanNickname(*in.Nickname)
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		nick = &n
	}
	uid := currentUser(r).ID
	_, err := s.DB.Exec(r.Context(), `UPDATE users SET nickname = COALESCE($2, nickname),
		leaderboard_anonymous = COALESCE($3, leaderboard_anonymous) WHERE id = $1`, uid, nick, in.LeaderboardAnonymous)
	if store.PgCode(err) == "23505" {
		httpx.Fail(w, r, errNicknameTaken)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.getMe(w, r)
}

type inviteCode struct {
	Code           string  `json:"code"`
	UsedByNickname *string `json:"used_by_nickname"`
	UsedAt         *string `json:"used_at"`
}

func (s *Server) listInvites(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := currentUser(r).ID
	m, err := loadMe(ctx, s.DB, uid)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	rows, err := s.DB.Query(ctx, `SELECT ic.code, u.nickname, ic.used_at FROM invite_codes ic
		LEFT JOIN users u ON u.id = ic.used_by WHERE ic.owner_id = $1 ORDER BY ic.created_at`, uid)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	codes := []inviteCode{}
	for rows.Next() {
		var c inviteCode
		var usedAt *time.Time
		if err := rows.Scan(&c.Code, &c.UsedByNickname, &usedAt); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		c.UsedAt = httpx.TimePtr(usedAt)
		codes = append(codes, c)
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"remaining": m.InvitesRemaining, "codes": codes})
}

func (s *Server) createInvite(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := currentUser(r).ID
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer func() { _ = tx.Rollback(ctx) }()
	// 锁住本人行，防止并发多发
	var quota, used int
	err = tx.QueryRow(ctx, `SELECT invite_quota, (SELECT count(*) FROM invite_codes WHERE owner_id = $1)
		FROM users WHERE id = $1 FOR UPDATE`, uid).Scan(&quota, &used)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if used >= quota {
		httpx.Fail(w, r, errInviteQuota)
		return
	}
	var code string
	for i := 0; i < 5; i++ {
		code = auth.NewInviteCode()
		tag, err := tx.Exec(ctx, `INSERT INTO invite_codes (code, owner_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, code, uid)
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		if tag.RowsAffected() == 1 {
			break
		}
		code = ""
	}
	if code == "" {
		httpx.Fail(w, r, errors.New("invite code collision"))
		return
	}
	if err := tx.Commit(ctx); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, map[string]string{"code": code})
}

type notice struct {
	ID        int64           `json:"id"`
	Kind      string          `json:"kind"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt string          `json:"created_at"`
	Read      bool            `json:"read"`
}

// listNotices 最近 100 条通知。只读，不改已读状态。
func (s *Server) listNotices(w http.ResponseWriter, r *http.Request) {
	rows, err := s.DB.Query(r.Context(), `SELECT id, kind, payload, created_at, read_at IS NOT NULL
		FROM notices WHERE user_id = $1 ORDER BY created_at DESC, id DESC LIMIT 100`, currentUser(r).ID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	out := []notice{}
	for rows.Next() {
		var n notice
		var at time.Time
		if err := rows.Scan(&n.ID, &n.Kind, &n.Payload, &at, &n.Read); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		n.CreatedAt = httpx.Time(at)
		out = append(out, n)
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}
