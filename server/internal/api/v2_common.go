package api

// WS8（v2）公共部分：处罚查询、管理员操作日志、原因校验、v2 错误码。

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

var (
	errMuted           = httpx.E(http.StatusForbidden, "muted", "你已被管理员禁言，暂时不能在频道发消息")
	errBadBody         = httpx.E(http.StatusBadRequest, "bad_body", "消息须为 1–300 字")
	errAlreadyReported = httpx.E(http.StatusConflict, "already_reported", "你已经举报过，管理员处理中")
	errReportClosed    = httpx.E(http.StatusConflict, "report_closed", "这条举报已经处理过了")
	errAccountDisabled = httpx.E(http.StatusForbidden, "account_disabled", "账号已被停用，如有疑问请联系管理员")
)

// sanction 与 api.yaml components.schemas.Sanction 一致。
type sanction struct {
	ID       int64   `json:"id"`
	Kind     string  `json:"kind"`
	Reason   string  `json:"reason"`
	StartsAt string  `json:"starts_at"`
	EndsAt   *string `json:"ends_at"`
	LiftedAt *string `json:"lifted_at"`
}

// activeSanctionCond 生效中的处罚：未解除、已开始、未到期（ends_at NULL = 永久，仅 disable）。$2 = 现在。
const activeSanctionCond = `lifted_at IS NULL AND starts_at <= $2 AND (ends_at IS NULL OR ends_at > $2)`

type activeSanctionRow struct {
	Kind   string
	EndsAt *time.Time
	Reason string
}

// activeSanction 某类生效中的处罚，取到期最晚的一条（永久优先）；没有返回 nil。
func activeSanction(ctx context.Context, q store.Q, userID int64, kind string, now time.Time) (*activeSanctionRow, error) {
	var r activeSanctionRow
	err := q.QueryRow(ctx, `SELECT kind, ends_at, reason FROM user_sanctions
		WHERE user_id = $1 AND kind = $3 AND `+activeSanctionCond+`
		ORDER BY ends_at DESC NULLS FIRST, id DESC LIMIT 1`, userID, now, kind).Scan(&r.Kind, &r.EndsAt, &r.Reason)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &r, nil
}

// restriction 与 api.yaml Me.restrictions 的元素一致。
type restriction struct {
	Kind   string `json:"kind"`
	EndsAt string `json:"ends_at"`
	Reason string `json:"reason"`
}

// loadRestrictions 我当前生效的禁言 / 全站禁发（每类取到期最晚的一条）。
func loadRestrictions(ctx context.Context, q store.Q, userID int64, now time.Time) ([]restriction, error) {
	rows, err := q.Query(ctx, `SELECT DISTINCT ON (kind) kind, ends_at, reason FROM user_sanctions
		WHERE user_id = $1 AND kind IN ('mute','post_ban') AND ends_at IS NOT NULL AND `+activeSanctionCond+`
		ORDER BY kind, ends_at DESC, id DESC`, userID, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []restriction{}
	for rows.Next() {
		var x restriction
		var ends time.Time
		if err := rows.Scan(&x.Kind, &ends, &x.Reason); err != nil {
			return nil, err
		}
		x.EndsAt = httpx.Time(ends)
		out = append(out, x)
	}
	return out, rows.Err()
}

// cleanReason 管理员/举报填写的原因：去首尾空白，最多 200 字；required 时不能为空。
func cleanReason(s string, required bool) (*string, error) {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) > 200 {
		return nil, httpx.E(http.StatusBadRequest, "bad_request", "原因最多 200 字")
	}
	if s == "" {
		if required {
			return nil, httpx.E(http.StatusBadRequest, "bad_request", "请填写原因")
		}
		return nil, nil
	}
	return &s, nil
}

// logAdmin 写一行管理员操作日志（与写操作同一事务）。payload 只放改前/改后的业务值，不放密码、恢复码、token。
func logAdmin(ctx context.Context, q store.Q, adminID int64, action, targetKind string, targetID int64, reason *string, payload map[string]any) error {
	if payload == nil {
		payload = map[string]any{}
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	_, err = q.Exec(ctx, `INSERT INTO admin_actions (admin_id, action, target_kind, target_id, reason, payload)
		VALUES ($1, $2, $3, $4, $5, $6)`, adminID, action, targetKind, targetID, reason, b)
	return err
}

// shanghaiDayStart t 所在 Asia/Shanghai 日期的 00:00。
func shanghaiDayStart(t time.Time) time.Time {
	d := t.In(httpx.Shanghai)
	return time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, httpx.Shanghai)
}
