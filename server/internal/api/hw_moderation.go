package api

// WS2 社区：谢谢、删除投票（含禁发与邀请人提醒）、合并提议/同意/赞同/执行、榜单、我的贡献值。

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/points"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

const (
	mergeVotesNeeded = 3
	mergeExpiry      = 7 * 24 * time.Hour
	banWindow        = 30 * 24 * time.Hour
	banStrikes       = 3
	banLength        = 7 * 24 * time.Hour
)

// POST /sources/{source_id}/thanks 不能谢自己，每人每条一次；作者 +1（每条 ≤10，计入每日上限）。
func (s *Server) thankSource(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "source_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		src, err := loadSrcRef(ctx, tx, id, false)
		if err != nil {
			return err
		}
		if src.Deleted {
			return httpx.ErrNotFound
		}
		if err := requireMember(ctx, tx, src.ClassID, r, false); err != nil {
			return err
		}
		if src.AuthorID == uid {
			return errSelfThanks
		}
		if !src.Writable {
			return errClassArchived
		}
		tag, err := tx.Exec(ctx, `INSERT INTO thanks (source_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, id, uid)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return httpx.E(http.StatusConflict, "already_thanked", "你已经谢过了")
		}
		_, err = points.AddThanks(ctx, tx, src.AuthorID, src.ClassID, id, now)
		return err
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /sources/{source_id}/delete-votes 达到 clamp(ceil(成员数×10%),3,6) 即软删除：
// 收回贡献值并 -10；30 天内第 3 次被删 → 该班禁发 7 天；作者为邀请注册时提醒直接邀请人。
func (s *Server) voteDelete(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "source_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	var out struct {
		Votes     int  `json:"votes"`
		Threshold int  `json:"threshold"`
		Deleted   bool `json:"deleted"`
	}
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		src, err := loadSrcRef(ctx, tx, id, true) // 锁来源，串行化同一来源的投票
		if err != nil {
			return err
		}
		if src.Deleted {
			return httpx.ErrNotFound
		}
		if err := requireMember(ctx, tx, src.ClassID, r, false); err != nil {
			return err
		}
		if src.AuthorID == uid {
			return errSelfVote
		}
		if !src.Writable {
			return errClassArchived
		}
		tag, err := tx.Exec(ctx, `INSERT INTO delete_votes (source_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, id, uid)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return errAlreadyVoted
		}
		var members int
		if err := tx.QueryRow(ctx, `SELECT
			(SELECT count(*) FROM delete_votes WHERE source_id = $1),
			(SELECT count(*) FROM class_members WHERE class_id = $2 AND left_at IS NULL)`, id, src.ClassID).
			Scan(&out.Votes, &members); err != nil {
			return err
		}
		out.Threshold = points.DeleteThreshold(members)
		if out.Votes < out.Threshold {
			return nil
		}
		out.Deleted = true
		return s.deleteModerated(ctx, tx, src, now, "vote", true, nil)
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

// deleteModerated 投票删除（why=vote）或管理员删除（why=admin，v2）一条来源：软删除，作业无剩余来源时随之删除。
// penalize=true：收回该来源贡献值并 -10、计入 30 天禁发次数（满 3 次该班禁发 7 天）、通知邀请人；
// penalize=false（仅管理员删除可选）：只收回不罚。作者都收到 content_deleted 通知（管理员删除时含 reason）。
func (s *Server) deleteModerated(ctx context.Context, tx pgx.Tx, src *srcRef, now time.Time, why string, penalize bool, reason *string) error {
	if _, err := loadAsgRef(ctx, tx, src.AssignmentID, true); err != nil {
		return err
	}
	if err := softDeleteSource(ctx, tx, src, why); err != nil {
		return err
	}
	if err := points.Lock(ctx, tx, src.AuthorID); err != nil {
		return err
	}
	if penalize {
		if err := points.RevokeForDeletion(ctx, tx, src.AuthorID, src.ClassID, src.ID, now); err != nil {
			return err
		}
	} else if err := points.VoidSource(ctx, tx, src.ID); err != nil {
		return err
	}
	var title string
	if err := tx.QueryRow(ctx, `SELECT title FROM assignments WHERE id = $1`, src.AssignmentID).Scan(&title); err != nil {
		return err
	}
	payload := map[string]any{"source_id": src.ID, "assignment_id": src.AssignmentID, "class_id": src.ClassID, "title": title}
	if reason != nil {
		payload["reason"] = *reason
	}
	if err := notify(ctx, tx, src.AuthorID, "content_deleted", payload); err != nil {
		return err
	}
	if !penalize {
		return nil
	}
	// 30 天内被删除且受罚的次数（含本次），按作业当前所在班级统计：投票删除，以及 penalize=true 的管理员删除
	// （仍处于删除状态且扣分未被返还，即 deleted_penalty 条数多于 restore_refund）
	var strikes int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM assignment_sources s JOIN assignments a ON a.id = s.assignment_id
		WHERE s.author_id = $1 AND a.class_id = $2 AND s.deleted_at > $3
		  AND (s.deleted_reason = 'vote' OR (s.deleted_reason = 'admin' AND
		       (SELECT count(*) FILTER (WHERE p.reason = 'deleted_penalty') - count(*) FILTER (WHERE p.reason = 'restore_refund')
		        FROM points_ledger p WHERE p.source_id = s.id AND p.status = 'settled') > 0))`,
		src.AuthorID, src.ClassID, now.Add(-banWindow)).Scan(&strikes); err != nil {
		return err
	}
	if strikes >= banStrikes {
		tag, err := tx.Exec(ctx, `INSERT INTO post_bans (user_id, class_id, starts_at, ends_at)
			SELECT $1, $2, $3, $4 WHERE NOT EXISTS (SELECT 1 FROM post_bans
			  WHERE user_id = $1 AND class_id = $2 AND lifted_at IS NULL AND ends_at > $3)`,
			src.AuthorID, src.ClassID, now, now.Add(banLength))
		if err != nil {
			return err
		}
		if tag.RowsAffected() > 0 {
			if err := notify(ctx, tx, src.AuthorID, "ban", map[string]any{"class_id": src.ClassID, "ends_at": httpx.Time(now.Add(banLength))}); err != nil {
				return err
			}
		}
	}
	// 邀请来的用户违规：只提醒直接邀请人，不往上追
	var inviter *int64
	if err := tx.QueryRow(ctx, `SELECT inviter_id FROM users WHERE id = $1`, src.AuthorID).Scan(&inviter); err != nil {
		return err
	}
	if inviter != nil {
		var nick string
		if err := tx.QueryRow(ctx, `SELECT nickname FROM users WHERE id = $1`, src.AuthorID).Scan(&nick); err != nil {
			return err
		}
		if err := notify(ctx, tx, *inviter, "invitee_violation", map[string]any{"invitee_nickname": nick, "strikes_30d": strikes}); err != nil {
			return err
		}
	}
	return nil
}

// ---------- 合并 ----------

func proposalIDParam(r *http.Request) (int64, error) { return idParam(r, "proposal_id") }

func (s *Server) writeProposal(ctx context.Context, w http.ResponseWriter, r *http.Request, id int64, status int) {
	ps, err := loadProposals(ctx, s.DB, currentUser(r).ID, `p.id = $2`, id)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if len(ps) == 0 {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	httpx.JSON(w, status, ps[0])
}

// POST /merge-proposals 提议把 from 合并到 to（同一班级、都在世）。提议人算第一票；
// 提议人就是 from 的首发者时视同同意，立即执行。
func (s *Server) proposeMerge(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		FromID int64 `json:"from_id"`
		ToID   int64 `json:"to_id"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if in.FromID <= 0 || in.ToID <= 0 || in.FromID == in.ToID {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "请选择两条不同的作业"))
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	var pid int64
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		from, to, err := lockPair(ctx, tx, in.FromID, in.ToID)
		if err != nil {
			return err
		}
		if err := requireMember(ctx, tx, from.ClassID, r, false); err != nil {
			return err
		}
		if !from.Writable {
			return errClassArchived
		}
		err = tx.QueryRow(ctx, `INSERT INTO merge_proposals (from_id, to_id, proposer_id, created_at)
			VALUES ($1, $2, $3, $4) RETURNING id`, from.ID, to.ID, uid, now).Scan(&pid)
		if store.PgCode(err) == "23505" {
			return httpx.E(http.StatusConflict, "already_proposed", "已经有人提议过这次合并")
		}
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO merge_votes (proposal_id, user_id) VALUES ($1, $2)`, pid, uid); err != nil {
			return err
		}
		if from.CreatedBy == uid {
			return s.applyMerge(ctx, tx, pid, from, to, "poster", now)
		}
		return notify(ctx, tx, from.CreatedBy, "merge_request", map[string]any{"proposal_id": pid, "from_id": from.ID, "to_id": to.ID})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.writeProposal(ctx, w, r, pid, http.StatusCreated)
}

// lockPair 按 id 顺序锁住两条作业，校验都在世且同班。
func lockPair(ctx context.Context, tx pgx.Tx, fromID, toID int64) (*asgRef, *asgRef, error) {
	first, second := fromID, toID
	if second < first {
		first, second = second, first
	}
	a, err := loadAsgRef(ctx, tx, first, true)
	if err != nil {
		return nil, nil, err
	}
	b, err := loadAsgRef(ctx, tx, second, true)
	if err != nil {
		return nil, nil, err
	}
	from, to := a, b
	if a.ID != fromID {
		from, to = b, a
	}
	if from.Deleted || to.Deleted || from.MergedInto != nil || to.MergedInto != nil {
		return nil, nil, httpx.E(http.StatusConflict, "merge_stale", "作业已被删除或合并")
	}
	if from.ClassID != to.ClassID {
		return nil, nil, httpx.E(http.StatusBadRequest, "bad_request", "只能合并同一班级的作业")
	}
	return from, to, nil
}

type pendingProposal struct {
	ID, FromID, ToID int64
}

func lockProposal(ctx context.Context, tx pgx.Tx, id int64) (*pendingProposal, error) {
	var p pendingProposal
	var status string
	err := tx.QueryRow(ctx, `SELECT id, from_id, to_id, status FROM merge_proposals WHERE id = $1 FOR UPDATE`, id).
		Scan(&p.ID, &p.FromID, &p.ToID, &status)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, httpx.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if status != "pending" {
		return nil, httpx.E(http.StatusConflict, "merge_closed", "这个合并提议已结束")
	}
	return &p, nil
}

// closeStale 提议的作业已被删除/合并：标记 rejected 后提交，再返回 409。
func (s *Server) closeStale(ctx context.Context, pid int64) {
	_, _ = s.DB.Exec(ctx, `UPDATE merge_proposals SET status = 'rejected' WHERE id = $1 AND status = 'pending'`, pid)
}

// POST /merge-proposals/{id}/accept 被合并作业（from）的首发者同意，立即执行。
func (s *Server) acceptMerge(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	pid, err := proposalIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		p, err := lockProposal(ctx, tx, pid)
		if err != nil {
			return err
		}
		from, to, err := lockPair(ctx, tx, p.FromID, p.ToID)
		if err != nil {
			return err
		}
		if from.CreatedBy != uid {
			return httpx.E(http.StatusForbidden, "forbidden", "只有被合并作业的首发者可以同意")
		}
		if !from.Writable {
			return errClassArchived
		}
		return s.applyMerge(ctx, tx, pid, from, to, "poster", s.Now())
	})
	if isStale(err) {
		s.closeStale(ctx, pid)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.writeProposal(ctx, w, r, pid, http.StatusOK)
}

func isStale(err error) bool {
	var he *httpx.Error
	return errors.As(err, &he) && he.Code == "merge_stale"
}

// POST /merge-proposals/{id}/votes 班级成员赞同，满 3 人执行。
func (s *Server) voteMerge(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	pid, err := proposalIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		p, err := lockProposal(ctx, tx, pid)
		if err != nil {
			return err
		}
		from, to, err := lockPair(ctx, tx, p.FromID, p.ToID)
		if err != nil {
			return err
		}
		if err := requireMember(ctx, tx, from.ClassID, r, false); err != nil {
			return err
		}
		if !from.Writable {
			return errClassArchived
		}
		tag, err := tx.Exec(ctx, `INSERT INTO merge_votes (proposal_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, pid, uid)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return errAlreadyVoted
		}
		if from.CreatedBy == uid { // 被合并作业的首发者赞同 = 同意
			return s.applyMerge(ctx, tx, pid, from, to, "poster", s.Now())
		}
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM merge_votes WHERE proposal_id = $1`, pid).Scan(&n); err != nil {
			return err
		}
		if n >= mergeVotesNeeded {
			return s.applyMerge(ctx, tx, pid, from, to, "votes", s.Now())
		}
		return nil
	})
	if isStale(err) {
		s.closeStale(ctx, pid)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.writeProposal(ctx, w, r, pid, http.StatusOK)
}

type mergeSnapshot struct {
	SourceIDs            []int64       `json:"source_ids"`
	FirstSourceID        *int64        `json:"first_source_id"`
	VoidedLedger         []ledgerState `json:"voided_ledger"`
	AddedLedgerIDs       []int64       `json:"added_ledger_ids"`
	AddedCompletionUsers []int64       `json:"added_completion_users"`
	SupersededIDs        []int64       `json:"superseded_ids"`
}

type ledgerState struct {
	ID     int64  `json:"id"`
	Status string `json:"status"`
}

// applyMerge 执行合并（调用方已锁住提议与两条作业）：
// 搬来源（保留 original_assignment_id）、from 首发来源改为补充、首发分作废（有新内容改记补充 +3）、
// 完成状态取并集、from.merged_into = to、重算 to 的 DDL 标红、同对作业的其他提议失效、通知 from 首发者。
func (s *Server) applyMerge(ctx context.Context, tx pgx.Tx, pid int64, from, to *asgRef, by string, now time.Time) error {
	var snap mergeSnapshot
	rows, err := tx.Query(ctx, `UPDATE assignment_sources SET assignment_id = $2 WHERE assignment_id = $1 RETURNING id`, from.ID, to.ID)
	if err != nil {
		return err
	}
	if snap.SourceIDs, err = pgx.CollectRows(rows, pgx.RowTo[int64]); err != nil {
		return err
	}
	var first int64
	var firstAuthor int64
	var hasContent bool
	err = tx.QueryRow(ctx, `SELECT s.id, s.author_id, (btrim(s.body) <> '' OR EXISTS (SELECT 1 FROM attachments t WHERE t.source_id = s.id))
		FROM assignment_sources s WHERE s.original_assignment_id = $1 AND s.kind = 'first' AND s.id = ANY($2)`,
		from.ID, snap.SourceIDs).Scan(&first, &firstAuthor, &hasContent)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	if err == nil {
		snap.FirstSourceID = &first
		if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET kind = 'supplement' WHERE id = $1`, first); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT id, status, settle_at FROM points_ledger
			WHERE source_id = $1 AND reason = 'first_post' AND status <> 'void'`, first)
		if err != nil {
			return err
		}
		type fp struct {
			id     int64
			status string
			at     time.Time
		}
		var fps []fp
		for rows.Next() {
			var f fp
			if err := rows.Scan(&f.id, &f.status, &f.at); err != nil {
				rows.Close()
				return err
			}
			fps = append(fps, f)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, f := range fps {
			snap.VoidedLedger = append(snap.VoidedLedger, ledgerState{ID: f.id, Status: f.status})
			if _, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'void' WHERE id = $1`, f.id); err != nil {
				return err
			}
			if !hasContent {
				continue
			}
			// 有新内容按补充算：未结算的仍 24h 后结算；已结算的直接 settled（同一天的上限内）
			var lid int64
			if err := points.Lock(ctx, tx, firstAuthor); err != nil {
				return err
			}
			if err := tx.QueryRow(ctx, `INSERT INTO points_ledger (user_id, class_id, semester_id, source_id, reason, delta, status, settle_at, local_day, created_at)
				SELECT user_id, $2, semester_id, source_id, 'supplement', LEAST(delta, $3), $4, settle_at, local_day, created_at
				FROM points_ledger WHERE id = $1 RETURNING id`, f.id, to.ClassID, points.Supplement, f.status).Scan(&lid); err != nil {
				return err
			}
			snap.AddedLedgerIDs = append(snap.AddedLedgerIDs, lid)
		}
	}
	rows, err = tx.Query(ctx, `INSERT INTO completions (user_id, assignment_id, done_at)
		SELECT user_id, $2, done_at FROM completions WHERE assignment_id = $1
		ON CONFLICT (user_id, assignment_id) DO NOTHING RETURNING user_id`, from.ID, to.ID)
	if err != nil {
		return err
	}
	if snap.AddedCompletionUsers, err = pgx.CollectRows(rows, pgx.RowTo[int64]); err != nil {
		return err
	}
	rows, err = tx.Query(ctx, `UPDATE merge_proposals SET status = 'expired'
		WHERE status = 'pending' AND id <> $1 AND (from_id = $2 OR to_id = $2) RETURNING id`, pid, from.ID)
	if err != nil {
		return err
	}
	if snap.SupersededIDs, err = pgx.CollectRows(rows, pgx.RowTo[int64]); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE assignments SET merged_into = $2, updated_at = now() WHERE id = $1`, from.ID, to.ID); err != nil {
		return err
	}
	// from 的 DDL 也算一个「声称的 DDL」：from 首发来源的 stated_ddl 即 from 的 DDL，已随来源搬过来
	if err := recomputeConflict(ctx, tx, to.ID); err != nil {
		return err
	}
	b, err := json.Marshal(snap)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE merge_proposals SET status = 'applied', applied_by = $2, applied_at = $3, snapshot = $4
		WHERE id = $1`, pid, by, now, b); err != nil {
		return err
	}
	return notify(ctx, tx, from.CreatedBy, "merge_applied", map[string]any{"proposal_id": pid, "from_id": from.ID, "to_id": to.ID})
}

// ---------- 榜单与贡献值 ----------

const anonymousName = "匿名同学"

// GET /classes/{class_id}/leaderboard?scope=week|semester
// 周榜：本周一 00:00（Asia/Shanghai）起的已结算贡献值；学期榜：本班（班级即属于某学期）全部已结算贡献值。
// 课代表 = 学期榜第一（> 0 分），同分取先达到该分数者。前 100 名，另附本人行（若不在前 100）。
func (s *Server) leaderboard(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	scope := r.URL.Query().Get("scope")
	if scope != "week" && scope != "semester" {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "scope 须为 week 或 semester"))
		return
	}
	cid, err := classIDParam(r)
	if err == nil {
		cid, err = store.ResolveClass(ctx, s.DB, cid)
		if errors.Is(err, pgx.ErrNoRows) {
			err = httpx.ErrNotFound
		}
	}
	if err == nil {
		err = requireMember(ctx, s.DB, cid, r, true)
	}
	if err == nil {
		err = hiddenFromUser(ctx, s.DB, cid, r)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	since := time.Time{}
	if scope == "week" {
		since = WeekStart(s.Now())
	}
	type row struct {
		Rank        int    `json:"rank"`
		DisplayName string `json:"display_name"`
		Points      int    `json:"points"`
		IsMe        bool   `json:"is_me"`
	}
	rows, err := s.DB.Query(ctx, `WITH t AS (
			SELECT user_id, sum(delta)::int AS pts, max(settle_at) AS last_at FROM points_ledger
			WHERE class_id = $1 AND status = 'settled' AND created_at >= $2 GROUP BY user_id)
		SELECT rank() OVER (ORDER BY t.pts DESC)::int, u.nickname, u.leaderboard_anonymous, t.pts, t.user_id = $3,
		       row_number() OVER (ORDER BY t.pts DESC, t.last_at, t.user_id)
		FROM t JOIN users u ON u.id = t.user_id ORDER BY t.pts DESC, t.last_at, t.user_id`, cid, since, uid)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	out := []row{}
	for rows.Next() {
		var x row
		var anon bool
		var n int64
		if err := rows.Scan(&x.Rank, &x.DisplayName, &anon, &x.Points, &x.IsMe, &n); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		if anon {
			x.DisplayName = anonymousName
		}
		if n <= 100 || x.IsMe {
			out = append(out, x)
		}
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	rep, err := classRep(ctx, s.DB, cid)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"rep": rep, "rows": out})
}

// classRep 课代表：学期贡献值最高（> 0），同分取累计达到该分数最早者。
func classRep(ctx context.Context, q store.Q, classID int64) (map[string]string, error) {
	var nick string
	var anon bool
	err := q.QueryRow(ctx, `WITH l AS (
			SELECT user_id, settle_at, id, sum(delta) OVER (PARTITION BY user_id ORDER BY settle_at, id) AS run,
			       sum(delta) OVER (PARTITION BY user_id) AS total
			FROM points_ledger WHERE class_id = $1 AND status = 'settled'),
		top AS (SELECT max(total) AS m FROM l),
		reach AS (SELECT l.user_id, min(l.settle_at) AS at FROM l, top
			WHERE l.total = top.m AND top.m > 0 AND l.run >= top.m GROUP BY l.user_id)
		SELECT u.nickname, u.leaderboard_anonymous FROM reach JOIN users u ON u.id = reach.user_id
		ORDER BY reach.at, reach.user_id LIMIT 1`, classID).Scan(&nick, &anon)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if anon {
		nick = anonymousName
	}
	return map[string]string{"display_name": nick}, nil
}

// WeekStart 本周一 00:00（Asia/Shanghai）。
func WeekStart(now time.Time) time.Time {
	t := now.In(httpx.Shanghai)
	d := (int(t.Weekday()) + 6) % 7 // 周一 = 0
	return time.Date(t.Year(), t.Month(), t.Day()-d, 0, 0, 0, 0, httpx.Shanghai)
}

// GET /me/points?cursor= 贡献值明细（新到旧，每页 50）与汇总。
func (s *Server) myPoints(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := currentUser(r).ID
	var cursor int64 = 1 << 62
	if v := r.URL.Query().Get("cursor"); v != "" {
		c, err := strconv.ParseInt(v, 10, 64)
		if err != nil || c <= 0 {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
		cursor = c
	}
	var semTotal, today int
	err := s.DB.QueryRow(ctx, `SELECT
		coalesce((SELECT sum(p.delta) FROM points_ledger p JOIN semesters se ON se.id = p.semester_id AND se.is_current
			WHERE p.user_id = $1 AND p.status = 'settled'), 0),
		coalesce((SELECT sum(delta) FROM points_ledger WHERE user_id = $1 AND status = 'settled' AND delta > 0 AND local_day = $2
			AND reason IN `+points.CapReasons+`), 0)`,
		uid, points.LocalDay(s.Now())).Scan(&semTotal, &today)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	const page = 50
	rows, err := s.DB.Query(ctx, `SELECT p.id, p.delta, p.reason, p.note, p.status, c.name, a.title, p.created_at
		FROM points_ledger p JOIN classes c ON c.id = p.class_id
		LEFT JOIN assignment_sources s ON s.id = p.source_id LEFT JOIN assignments a ON a.id = s.assignment_id
		WHERE p.user_id = $1 AND p.id < $2 ORDER BY p.id DESC LIMIT $3`, uid, cursor, page+1)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	type entry struct {
		Delta           int     `json:"delta"`
		Reason          string  `json:"reason"`
		Note            *string `json:"note"` // v2：admin_adjust 的原因
		Status          string  `json:"status"`
		ClassName       string  `json:"class_name"`
		AssignmentTitle *string `json:"assignment_title"`
		CreatedAt       string  `json:"created_at"`
	}
	items := []entry{}
	var next *string
	var lastID int64
	for rows.Next() {
		var e entry
		var id int64
		var at time.Time
		if err := rows.Scan(&id, &e.Delta, &e.Reason, &e.Note, &e.Status, &e.ClassName, &e.AssignmentTitle, &at); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		if len(items) == page {
			c := strconv.FormatInt(lastID, 10)
			next = &c
			break
		}
		e.CreatedAt = httpx.Time(at)
		items = append(items, e)
		lastID = id
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"semester_total": semTotal, "today_earned": today, "items": items, "next_cursor": next,
	})
}
