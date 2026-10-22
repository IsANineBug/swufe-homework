package api

// WS8（v2）班级频道与举报。频道是纯文字短消息，App 打开频道时用 after_id 轮询，不用长连接。
// 只有班级在班成员能看能发；管理员也不例外（只能通过举报看到被举报的消息及前后各 5 条）。

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

const (
	chatMaxBody       = 300
	chatPageMax       = 50
	chatMinInterval   = 10 * time.Second // 每人每 10 秒 1 条（所有班合计）
	chatHourLimit     = 30               // 每人每小时 30 条（所有班合计）
	chatRetention     = store.ChatRetention
	chatLockNS        = 3 // pg_advisory_xact_lock(3, user_id)：发消息限频
	reportLockNS      = 4 // pg_advisory_xact_lock(4, user_id)：每日举报数
	reportsPerDay     = 20
	reportReasonLimit = 200
)

// message 与 api.yaml components.schemas.Message 一致。
type message struct {
	ID             int64  `json:"id"`
	ClassID        int64  `json:"class_id"`
	AuthorNickname string `json:"author_nickname"`
	IsMine         bool   `json:"is_mine"`
	Body           string `json:"body"`
	CreatedAt      string `json:"created_at"`
}

// chatClass 解析频道所属班级（沿 merged_into），校验在班成员（管理员不例外）与隐藏状态。
// 返回最终班级 id 与是否可写（当前未归档学期）。
func chatClass(ctx context.Context, q store.Q, r *http.Request) (int64, bool, error) {
	cid, err := classIDParam(r)
	if err != nil {
		return 0, false, err
	}
	cid, err = store.ResolveClass(ctx, q, cid)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, httpx.ErrNotFound
	}
	if err != nil {
		return 0, false, err
	}
	ok, err := store.IsMember(ctx, q, cid, currentUser(r).ID)
	if err != nil {
		return 0, false, err
	}
	if !ok {
		return 0, false, errNotMember
	}
	var hidden, writable bool
	if err := q.QueryRow(ctx, `SELECT c.hidden_at IS NOT NULL, se.is_current AND NOT se.archived
		FROM classes c JOIN semesters se ON se.id = c.semester_id WHERE c.id = $1`, cid).Scan(&hidden, &writable); err != nil {
		return 0, false, err
	}
	if hidden {
		return 0, false, errClassArchived
	}
	return cid, writable, nil
}

// GET /classes/{class_id}/messages?after_id=&before_id=&limit=
func (s *Server) listMessages(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	parse := func(name string) (int64, bool, error) {
		v := q.Get(name)
		if v == "" {
			return 0, false, nil
		}
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n < 0 {
			return 0, false, httpx.ErrBadRequest
		}
		return n, true, nil
	}
	after, hasAfter, err1 := parse("after_id")
	before, hasBefore, err2 := parse("before_id")
	limit, hasLimit, err3 := parse("limit")
	if err := errors.Join(err1, err2, err3); err != nil {
		httpx.Fail(w, r, httpx.ErrBadRequest)
		return
	}
	if !hasLimit {
		limit = chatPageMax
	}
	if limit < 1 || limit > chatPageMax {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "limit 须为 1–50"))
		return
	}
	cid, _, err := chatClass(ctx, s.DB, r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	const cols = `SELECT m.id, m.class_id, u.nickname, m.user_id = $1, m.body, m.created_at
		FROM class_messages m JOIN users u ON u.id = m.user_id
		WHERE m.class_id = $2 AND m.deleted_at IS NULL AND m.created_at > $3 `
	var rows pgx.Rows
	switch {
	case hasAfter:
		rows, err = s.DB.Query(ctx, cols+`AND m.id > $4 ORDER BY m.id LIMIT $5`, uid, cid, now.Add(-chatRetention), after, limit)
	case hasBefore:
		rows, err = s.DB.Query(ctx, `SELECT * FROM (`+cols+`AND m.id < $4 ORDER BY m.id DESC LIMIT $5) x ORDER BY id`,
			uid, cid, now.Add(-chatRetention), before, limit)
	default:
		rows, err = s.DB.Query(ctx, `SELECT * FROM (`+cols+`ORDER BY m.id DESC LIMIT $4) x ORDER BY id`,
			uid, cid, now.Add(-chatRetention), limit)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	items, err := scanMessages(rows)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	mute, err := activeSanction(ctx, s.DB, uid, "mute", now)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var mutedUntil *string
	if mute != nil {
		mutedUntil = httpx.TimePtr(mute.EndsAt)
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items, "muted_until": mutedUntil})
}

func scanMessages(rows pgx.Rows) ([]message, error) {
	defer rows.Close()
	out := []message{}
	for rows.Next() {
		var m message
		var at time.Time
		if err := rows.Scan(&m.ID, &m.ClassID, &m.AuthorNickname, &m.IsMine, &m.Body, &at); err != nil {
			return nil, err
		}
		m.CreatedAt = httpx.Time(at)
		out = append(out, m)
	}
	return out, rows.Err()
}

// chatRateLimited 限频判断：最近 1 小时（含已删除的）消息时间，新到旧。返回需等待的时长，0 = 可以发。
func chatRateLimited(recent []time.Time, now time.Time) time.Duration {
	var wait time.Duration
	if len(recent) > 0 {
		if d := recent[0].Add(chatMinInterval).Sub(now); d > wait {
			wait = d
		}
	}
	if len(recent) >= chatHourLimit {
		if d := recent[chatHourLimit-1].Add(time.Hour).Sub(now); d > wait {
			wait = d
		}
	}
	return wait
}

// POST /classes/{class_id}/messages
func (s *Server) postMessage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		Body string `json:"body"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	body := strings.TrimSpace(in.Body)
	if n := utf8.RuneCountInString(body); n < 1 || n > chatMaxBody {
		httpx.Fail(w, r, errBadBody)
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	var wait time.Duration
	var out message
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		cid, writable, err := chatClass(ctx, tx, r)
		if err != nil {
			return err
		}
		if !writable {
			return errClassArchived
		}
		if mute, err := activeSanction(ctx, tx, uid, "mute", now); err != nil {
			return err
		} else if mute != nil {
			return errMuted
		}
		// 同一用户串行计数，避免并发越过限频；走 class_messages_user_time 索引
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1, $2::int)`, chatLockNS, uid); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT created_at FROM class_messages
			WHERE user_id = $1 AND created_at > $2 ORDER BY created_at DESC LIMIT $3`, uid, now.Add(-time.Hour), chatHourLimit)
		if err != nil {
			return err
		}
		recent, err := pgx.CollectRows(rows, pgx.RowTo[time.Time])
		if err != nil {
			return err
		}
		if wait = chatRateLimited(recent, now); wait > 0 {
			return errChatRateLimited
		}
		var at time.Time
		if err := tx.QueryRow(ctx, `WITH ins AS (INSERT INTO class_messages (class_id, user_id, body, created_at)
				VALUES ($1, $2, $3, $4) RETURNING id, created_at)
			SELECT ins.id, ins.created_at, u.nickname FROM ins, users u WHERE u.id = $2`, cid, uid, body, now).
			Scan(&out.ID, &at, &out.AuthorNickname); err != nil {
			return err
		}
		out.ClassID, out.IsMine, out.Body, out.CreatedAt = cid, true, body, httpx.Time(at)
		return nil
	})
	if errors.Is(err, errChatRateLimited) {
		secs := int(math.Ceil(wait.Seconds()))
		if secs < 1 {
			secs = 1
		}
		w.Header().Set("Retry-After", strconv.Itoa(secs))
		httpx.Fail(w, r, httpx.E(http.StatusTooManyRequests, "chat_rate_limited", fmt.Sprintf("发得太快了，%d 秒后可再发", secs)))
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, out)
}

var errChatRateLimited = errors.New("chat rate limited")

// DELETE /messages/{message_id} 作者删除自己的消息（幂等）；非作者 403。
func (s *Server) deleteMessage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "message_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var author int64
	err = s.DB.QueryRow(ctx, `SELECT user_id FROM class_messages WHERE id = $1`, id).Scan(&author)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if author != currentUser(r).ID {
		httpx.Fail(w, r, httpx.E(http.StatusForbidden, "forbidden", "只能删除自己的消息"))
		return
	}
	if _, err := s.DB.Exec(ctx, `UPDATE class_messages SET deleted_at = now(), deleted_by = 'author'
		WHERE id = $1 AND deleted_at IS NULL`, id); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- 举报 ----------

// POST /reports 举报作业来源、频道消息、班级、用户。须能看到该对象；同一对象未处理的举报每人一条；每人每天 20 条。
func (s *Server) createReport(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		TargetKind string `json:"target_kind"`
		TargetID   int64  `json:"target_id"`
		Reason     string `json:"reason"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	reason := strings.TrimSpace(in.Reason)
	if n := utf8.RuneCountInString(reason); n < 1 || n > reportReasonLimit {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "举报理由须为 1–200 字"))
		return
	}
	if in.TargetID <= 0 {
		httpx.Fail(w, r, httpx.ErrBadRequest)
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		targetID, err := s.reportVisible(ctx, tx, uid, in.TargetKind, in.TargetID)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1, $2::int)`, reportLockNS, uid); err != nil {
			return err
		}
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM reports WHERE reporter_id = $1 AND created_at >= $2`,
			uid, shanghaiDayStart(now)).Scan(&n); err != nil {
			return err
		}
		if n >= reportsPerDay {
			return httpx.E(http.StatusTooManyRequests, "rate_limited", "今天的举报次数已用完（每天 20 条），明天再来")
		}
		_, err = tx.Exec(ctx, `INSERT INTO reports (reporter_id, target_kind, target_id, reason, created_at)
			VALUES ($1, $2, $3, $4, $5)`, uid, in.TargetKind, targetID, reason, now)
		if store.PgCode(err) == "23505" {
			return errAlreadyReported
		}
		return err
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

// reportVisible 举报人须能看到对象：来源/消息/班级须为该班在班成员；用户须与举报人同在一个当前学期的班。
// 返回规范化后的对象 id（班级沿 merged_into 解析）。
func (s *Server) reportVisible(ctx context.Context, q store.Q, uid int64, kind string, id int64) (int64, error) {
	var classID int64
	switch kind {
	case "source":
		src, err := loadSrcRef(ctx, q, id, false)
		if err != nil {
			return 0, err
		}
		if src.Deleted {
			return 0, httpx.ErrNotFound
		}
		classID = src.ClassID
	case "message":
		err := q.QueryRow(ctx, `SELECT class_id FROM class_messages WHERE id = $1 AND deleted_at IS NULL`, id).Scan(&classID)
		if errors.Is(err, pgx.ErrNoRows) {
			return 0, httpx.ErrNotFound
		}
		if err != nil {
			return 0, err
		}
	case "class":
		cid, err := store.ResolveClass(ctx, q, id)
		if errors.Is(err, pgx.ErrNoRows) {
			return 0, httpx.ErrNotFound
		}
		if err != nil {
			return 0, err
		}
		id, classID = cid, cid
	case "user":
		if id == uid {
			return 0, httpx.E(http.StatusBadRequest, "bad_request", "不能举报自己")
		}
		var same bool
		err := q.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM class_members a
			JOIN class_members b ON b.class_id = a.class_id AND b.user_id = $2 AND b.left_at IS NULL
			JOIN classes c ON c.id = a.class_id AND c.merged_into IS NULL
			JOIN semesters se ON se.id = c.semester_id AND se.is_current
			WHERE a.user_id = $1 AND a.left_at IS NULL)`, uid, id).Scan(&same)
		if err != nil {
			return 0, err
		}
		if !same {
			return 0, httpx.E(http.StatusForbidden, "forbidden", "只能举报和你同班的同学")
		}
		return id, nil
	default:
		return 0, httpx.E(http.StatusBadRequest, "bad_request", "target_kind 须为 source、message、class 或 user")
	}
	ok, err := store.IsMember(ctx, q, classID, uid)
	if err != nil {
		return 0, err
	}
	if !ok {
		return 0, errNotMember
	}
	return id, nil
}
