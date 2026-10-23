package api

// WS2 公共部分：作业/来源的装载、成员与禁发校验、DDL 冲突重算。

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

var (
	errNotMember  = httpx.E(http.StatusForbidden, "not_member", "你不是这个班的成员")
	errPostBanned = httpx.E(http.StatusForbidden, "post_banned", "你在这个班暂时被禁止发布")
	// v2：管理员全站禁发，错误码与单班禁发相同
	errPostBannedGlobal = httpx.E(http.StatusForbidden, "post_banned", "你已被管理员暂时禁止发布作业，可在「我的」查看原因和到期时间")
	errDailyLimit       = httpx.E(http.StatusTooManyRequests, "daily_limit", "今天在这个班已发布 5 条，明天再来吧")
	errDDLRequired      = httpx.E(http.StatusBadRequest, "ddl_required", "请填写截止时间")
	errClassArchived    = httpx.E(http.StatusConflict, "class_archived", "该班不属于当前学期，只能查看")
	errBadAttachment    = httpx.E(http.StatusBadRequest, "bad_attachment", "附件无效或已被使用")
	errAlreadyVoted     = httpx.E(http.StatusConflict, "already_voted", "你已经投过票了")
	errSelfThanks       = httpx.E(http.StatusConflict, "self_thanks", "不能给自己点谢谢")
	errSelfVote         = httpx.E(http.StatusConflict, "self_vote", "自己的内容请直接删除")
)

const (
	dailyPostLimit = 5
	maxAttachments = 6
	maxTitle       = 80
	maxBody        = 5000
	postLockNS     = 1 // pg_advisory_xact_lock(1, user_id)：发布计数
	// ddlTolerance DDL 相差不足 1 分钟视为相同
	ddlTolerance = time.Minute
)

func idParam(r *http.Request, name string) (int64, error) {
	id, err := strconv.ParseInt(chi.URLParam(r, name), 10, 64)
	if err != nil || id <= 0 {
		return 0, httpx.ErrNotFound
	}
	return id, nil
}

// asgRef 作业的归属信息（校验权限用）。
type asgRef struct {
	ID         int64
	ClassID    int64
	CreatedBy  int64
	DDL        time.Time
	MergedInto *int64
	Deleted    bool
	Writable   bool // 班级属于当前未归档学期且未被管理员隐藏
}

const asgRefSQL = `SELECT a.id, a.class_id, a.created_by, a.ddl, a.merged_into, a.deleted_at IS NOT NULL,
	(se.is_current AND NOT se.archived AND c.hidden_at IS NULL)
	FROM assignments a JOIN classes c ON c.id = a.class_id JOIN semesters se ON se.id = c.semester_id
	WHERE a.id = $1`

func loadAsgRef(ctx context.Context, q store.Q, id int64, lock bool) (*asgRef, error) {
	sql := asgRefSQL
	if lock {
		sql += ` FOR UPDATE OF a`
	}
	var a asgRef
	err := q.QueryRow(ctx, sql, id).Scan(&a.ID, &a.ClassID, &a.CreatedBy, &a.DDL, &a.MergedInto, &a.Deleted, &a.Writable)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, httpx.ErrNotFound
	}
	return &a, err
}

// resolveAsg 沿 merged_into 找到最终作业；已删除返回 404。
func resolveAsg(ctx context.Context, q store.Q, id int64) (*asgRef, error) {
	for i := 0; i < 16; i++ {
		a, err := loadAsgRef(ctx, q, id, false)
		if err != nil {
			return nil, err
		}
		if a.MergedInto == nil {
			if a.Deleted {
				return nil, httpx.ErrNotFound
			}
			return a, nil
		}
		id = *a.MergedInto
	}
	return nil, httpx.ErrNotFound
}

// srcRef 来源的归属信息。
type srcRef struct {
	ID           int64
	AssignmentID int64
	ClassID      int64
	AuthorID     int64
	Kind         string
	Deleted      bool
	DeletedWhy   *string
	Writable     bool
}

func loadSrcRef(ctx context.Context, q store.Q, id int64, lock bool) (*srcRef, error) {
	sql := `SELECT s.id, s.assignment_id, a.class_id, s.author_id, s.kind, s.deleted_at IS NOT NULL, s.deleted_reason,
		(se.is_current AND NOT se.archived AND c.hidden_at IS NULL)
		FROM assignment_sources s JOIN assignments a ON a.id = s.assignment_id
		JOIN classes c ON c.id = a.class_id JOIN semesters se ON se.id = c.semester_id
		WHERE s.id = $1`
	if lock {
		sql += ` FOR UPDATE OF s`
	}
	var s srcRef
	err := q.QueryRow(ctx, sql, id).Scan(&s.ID, &s.AssignmentID, &s.ClassID, &s.AuthorID, &s.Kind, &s.Deleted, &s.DeletedWhy, &s.Writable)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, httpx.ErrNotFound
	}
	return &s, err
}

// requireMember 班级在班成员；allowAdmin 时管理员也可（只读场景）。
func requireMember(ctx context.Context, q store.Q, classID int64, r *http.Request, allowAdmin bool) error {
	u := currentUser(r)
	if allowAdmin && u.IsAdmin() {
		return nil
	}
	ok, err := store.IsMember(ctx, q, classID, u.ID)
	if err != nil {
		return err
	}
	if !ok {
		return errNotMember
	}
	return nil
}

// classWritable 班级存在、属于当前未归档学期且未被管理员隐藏（v2）。
func classWritable(ctx context.Context, q store.Q, classID int64) error {
	var ok bool
	err := q.QueryRow(ctx, `SELECT se.is_current AND NOT se.archived AND c.merged_into IS NULL AND c.hidden_at IS NULL
		FROM classes c JOIN semesters se ON se.id = c.semester_id WHERE c.id = $1`, classID).Scan(&ok)
	if errors.Is(err, pgx.ErrNoRows) {
		return httpx.ErrNotFound
	}
	if err != nil {
		return err
	}
	if !ok {
		return errClassArchived
	}
	return nil
}

// checkCanPost 禁发（投票触发的单班禁发、v2 管理员全站禁发）与每日 5 条（Asia/Shanghai 日期，被删除的不计）。
// 须在事务内调用，会拿本人发布锁。
func checkCanPost(ctx context.Context, tx pgx.Tx, userID, classID int64, now time.Time) error {
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1, $2::int)`, postLockNS, userID); err != nil {
		return err
	}
	if sc, err := activeSanction(ctx, tx, userID, "post_ban", now); err != nil {
		return err
	} else if sc != nil {
		return errPostBannedGlobal
	}
	var banned bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM post_bans WHERE user_id = $1 AND class_id = $2
		AND lifted_at IS NULL AND starts_at <= $3 AND ends_at > $3)`, userID, classID, now).Scan(&banned); err != nil {
		return err
	}
	if banned {
		return errPostBanned
	}
	day := now.In(httpx.Shanghai)
	start := time.Date(day.Year(), day.Month(), day.Day(), 0, 0, 0, 0, httpx.Shanghai)
	var n int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM assignment_sources s JOIN assignments a ON a.id = s.assignment_id
		WHERE s.author_id = $1 AND a.class_id = $2 AND s.deleted_at IS NULL
		  AND s.created_at >= $3 AND s.created_at < $4`, userID, classID, start, start.AddDate(0, 0, 1)).Scan(&n); err != nil {
		return err
	}
	if n >= dailyPostLimit {
		return errDailyLimit
	}
	return nil
}

// bindAttachments 把本人未使用的附件挂到来源上，返回是否含图片。
func bindAttachments(ctx context.Context, tx pgx.Tx, sourceID, userID int64, ids []int64) (bool, error) {
	ids = uniqIDs(ids)
	if len(ids) == 0 {
		return false, nil
	}
	if len(ids) > maxAttachments {
		return false, httpx.E(http.StatusBadRequest, "bad_request", "最多 6 个附件")
	}
	rows, err := tx.Query(ctx, `UPDATE attachments SET source_id = $1
		WHERE id = ANY($2) AND uploader_id = $3 AND source_id IS NULL AND purged_at IS NULL RETURNING kind`, sourceID, ids, userID)
	if err != nil {
		return false, err
	}
	kinds, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return false, err
	}
	if len(kinds) != len(ids) {
		return false, errBadAttachment
	}
	for _, k := range kinds {
		if k == "image" {
			return true, nil
		}
	}
	return false, nil
}

func uniqIDs(ids []int64) []int64 {
	seen := map[int64]bool{}
	out := make([]int64, 0, len(ids))
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// recomputeConflict 作业 DDL 与任一在世来源声称的 DDL 相差 ≥ 1 分钟即标红；同时刷新 updated_at。
func recomputeConflict(ctx context.Context, tx pgx.Tx, assignmentID int64) error {
	_, err := tx.Exec(ctx, `UPDATE assignments a SET ddl_conflict = EXISTS (
		SELECT 1 FROM assignment_sources s WHERE s.assignment_id = a.id AND s.deleted_at IS NULL
		  AND s.stated_ddl IS NOT NULL AND abs(extract(epoch FROM s.stated_ddl - a.ddl)) >= $2),
		updated_at = now() WHERE a.id = $1`, assignmentID, ddlTolerance.Seconds())
	return err
}

// softDeleteSource 删除来源（作者 / 投票），作业无剩余来源时随之删除。
func softDeleteSource(ctx context.Context, tx pgx.Tx, src *srcRef, reason string) error {
	if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET deleted_at = now(), deleted_reason = $2 WHERE id = $1`, src.ID, reason); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE assignments a SET deleted_at = now() WHERE a.id = $1 AND a.deleted_at IS NULL
		AND NOT EXISTS (SELECT 1 FROM assignment_sources s WHERE s.assignment_id = a.id AND s.deleted_at IS NULL)`, src.AssignmentID); err != nil {
		return err
	}
	return recomputeConflict(ctx, tx, src.AssignmentID)
}

// hiddenFromUser 班级被管理员隐藏（v2）时，对非管理员按不存在处理（404）；管理员仍可查看。
func hiddenFromUser(ctx context.Context, q store.Q, classID int64, r *http.Request) error {
	if currentUser(r).IsAdmin() {
		return nil
	}
	var hidden bool
	err := q.QueryRow(ctx, `SELECT hidden_at IS NOT NULL FROM classes WHERE id = $1`, classID).Scan(&hidden)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && hidden) {
		return httpx.ErrNotFound
	}
	return err
}

func notify(ctx context.Context, q store.Q, userID int64, kind string, payload map[string]any) error {
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	_, err = q.Exec(ctx, `INSERT INTO notices (user_id, kind, payload) VALUES ($1, $2, $3)`, userID, kind, b)
	return err
}

func (s *Server) inTx(ctx context.Context, fn func(pgx.Tx) error) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
