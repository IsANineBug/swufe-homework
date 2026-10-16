package api

// WS2 管理员接口：恢复误删、撤销作业合并、撤销班级自动合并（issue #4 裁决 10）、被删内容复核、规则公告。

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/points"
)

// POST /admin/sources/{source_id}/restore 恢复被投票删除的内容，返还积分；lift_ban（默认 true）解除作者在该班的禁发。
func (s *Server) adminRestoreSource(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "source_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	in := struct {
		LiftBan *bool `json:"lift_ban"`
	}{}
	if r.ContentLength != 0 {
		if err := httpx.Decode(r, &in); err != nil {
			httpx.Fail(w, r, err)
			return
		}
	}
	lift := in.LiftBan == nil || *in.LiftBan
	now := s.Now()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		src, err := loadSrcRef(ctx, tx, id, true)
		if err != nil {
			return err
		}
		if !src.Deleted {
			return nil // 幂等
		}
		if src.DeletedWhy == nil || *src.DeletedWhy == "author" {
			return httpx.E(http.StatusConflict, "not_restorable", "作者自己删除的内容不能恢复")
		}
		if _, err := loadAsgRef(ctx, tx, src.AssignmentID, true); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET deleted_at = NULL, deleted_reason = NULL WHERE id = $1`, id); err != nil {
			return err
		}
		// 清掉旧票，避免再有一票就立即删除
		if _, err := tx.Exec(ctx, `DELETE FROM delete_votes WHERE source_id = $1`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE assignments SET deleted_at = NULL WHERE id = $1`, src.AssignmentID); err != nil {
			return err
		}
		if err := recomputeConflict(ctx, tx, src.AssignmentID); err != nil {
			return err
		}
		if err := points.Lock(ctx, tx, src.AuthorID); err != nil {
			return err
		}
		if err := points.RestoreForUndelete(ctx, tx, src.AuthorID, src.ClassID, id, now); err != nil {
			return err
		}
		if lift {
			if _, err := tx.Exec(ctx, `UPDATE post_bans SET lifted_at = $3
				WHERE user_id = $1 AND class_id = $2 AND lifted_at IS NULL AND ends_at > $3`, src.AuthorID, src.ClassID, now); err != nil {
				return err
			}
		}
		return notify(ctx, tx, src.AuthorID, "content_restored", map[string]any{"source_id": id, "assignment_id": src.AssignmentID})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /admin/merge-proposals/{proposal_id}/revert 撤销作业合并：按快照把来源搬回、首发来源与积分还原、去掉合并补上的完成状态。
// 目标作业之后又被合并走时需先撤销后一次（409 merge_chain）。
func (s *Server) adminRevertMerge(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	pid, err := proposalIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var fromID, toID int64
		var status string
		var raw []byte
		err := tx.QueryRow(ctx, `SELECT from_id, to_id, status, snapshot FROM merge_proposals WHERE id = $1 FOR UPDATE`, pid).
			Scan(&fromID, &toID, &status, &raw)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if status != "applied" || raw == nil {
			return httpx.E(http.StatusConflict, "merge_closed", "只能撤销已执行的合并")
		}
		var snap mergeSnapshot
		if err := json.Unmarshal(raw, &snap); err != nil {
			return err
		}
		first, second := fromID, toID
		if second < first {
			first, second = second, first
		}
		a, err := loadAsgRef(ctx, tx, first, true)
		if err != nil {
			return err
		}
		b, err := loadAsgRef(ctx, tx, second, true)
		if err != nil {
			return err
		}
		from, to := a, b
		if a.ID != fromID {
			from, to = b, a
		}
		if from.MergedInto == nil || *from.MergedInto != to.ID || to.MergedInto != nil {
			return httpx.E(http.StatusConflict, "merge_chain", "目标作业之后又被合并，请先撤销后一次合并")
		}
		if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET assignment_id = $1 WHERE id = ANY($2) AND assignment_id = $3`,
			from.ID, snap.SourceIDs, to.ID); err != nil {
			return err
		}
		if snap.FirstSourceID != nil {
			if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET kind = 'first' WHERE id = $1`, *snap.FirstSourceID); err != nil {
				return err
			}
		}
		if len(snap.AddedLedgerIDs) > 0 {
			if _, err := tx.Exec(ctx, `UPDATE points_ledger SET status = 'void' WHERE id = ANY($1)`, snap.AddedLedgerIDs); err != nil {
				return err
			}
		}
		for _, l := range snap.VoidedLedger {
			// 来源之后被删除的，积分保持作废
			if _, err := tx.Exec(ctx, `UPDATE points_ledger p SET status = $2, class_id = $3 WHERE p.id = $1 AND p.status = 'void'
				AND EXISTS (SELECT 1 FROM assignment_sources s WHERE s.id = p.source_id AND s.deleted_at IS NULL)`, l.ID, l.Status, from.ClassID); err != nil {
				return err
			}
		}
		if len(snap.AddedCompletionUsers) > 0 {
			if _, err := tx.Exec(ctx, `DELETE FROM completions WHERE assignment_id = $1 AND user_id = ANY($2)`, to.ID, snap.AddedCompletionUsers); err != nil {
				return err
			}
		}
		// from 的来源若全被删了，from 也处于删除状态
		if _, err := tx.Exec(ctx, `UPDATE assignments a SET merged_into = NULL,
			deleted_at = CASE WHEN EXISTS (SELECT 1 FROM assignment_sources s WHERE s.assignment_id = a.id AND s.deleted_at IS NULL)
			                  THEN NULL ELSE coalesce(a.deleted_at, now()) END
			WHERE a.id = $1`, from.ID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE assignments a SET deleted_at = now() WHERE a.id = $1 AND a.deleted_at IS NULL
			AND NOT EXISTS (SELECT 1 FROM assignment_sources s WHERE s.assignment_id = a.id AND s.deleted_at IS NULL)`, to.ID); err != nil {
			return err
		}
		if err := recomputeConflict(ctx, tx, from.ID); err != nil {
			return err
		}
		if err := recomputeConflict(ctx, tx, to.ID); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE merge_proposals SET status = 'reverted', reverted_by = $2 WHERE id = $1`, pid, admin)
		return err
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// GET /admin/deleted 最近 30 天被投票删除的来源（最多 200 条），供复核。
func (s *Server) adminDeleted(w http.ResponseWriter, r *http.Request) {
	out, err := s.loadSources(r.Context(), s.DB, currentUser(r).ID,
		`s.deleted_reason = 'vote' AND s.deleted_at > $2 ORDER BY s.deleted_at DESC LIMIT 200`, s.Now().Add(-30*24*time.Hour))
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

// POST /admin/announcements 规则变更公告（生效 ≥ 7 天后），同时给所有在用账号写一条 announcement 通知。
func (s *Server) adminAnnounce(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		Title       string     `json:"title"`
		Body        string     `json:"body"`
		EffectiveAt *time.Time `json:"effective_at"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	in.Title, in.Body = strings.TrimSpace(in.Title), strings.TrimSpace(in.Body)
	now := s.Now()
	if in.Title == "" || in.Body == "" || in.EffectiveAt == nil || utf8.RuneCountInString(in.Title) > 80 || utf8.RuneCountInString(in.Body) > 5000 {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "标题、正文、生效时间都要填"))
		return
	}
	if in.EffectiveAt.Before(now.Add(7 * 24 * time.Hour)) {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "effective_too_soon", "规则变动须提前 7 天公告"))
		return
	}
	var id int64
	err := s.inTx(ctx, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx, `INSERT INTO announcements (title, body, published_at, effective_at) VALUES ($1, $2, $3, $4) RETURNING id`,
			in.Title, in.Body, now, *in.EffectiveAt).Scan(&id); err != nil {
			return err
		}
		payload, _ := json.Marshal(map[string]any{"announcement_id": id, "title": in.Title, "body": in.Body, "effective_at": httpx.Time(*in.EffectiveAt)})
		_, err := tx.Exec(ctx, `INSERT INTO notices (user_id, kind, payload) SELECT id, 'announcement', $1 FROM users WHERE disabled_at IS NULL`, payload)
		return err
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, map[string]int64{"id": id})
}

// GET /admin/class-merges 最近 100 次班级自动合并（含已撤销），供复核。
func (s *Server) adminClassMerges(w http.ResponseWriter, r *http.Request) {
	rows, err := s.DB.Query(r.Context(), `SELECT m.id, m.from_class_id, f.name, m.into_class_id, i.name, u.nickname, m.created_at, m.undone_at
		FROM class_merges m JOIN classes f ON f.id = m.from_class_id JOIN classes i ON i.id = m.into_class_id
		LEFT JOIN users u ON u.id = m.triggered_by ORDER BY m.id DESC LIMIT 100`)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	type item struct {
		ID          int64   `json:"id"`
		FromClassID int64   `json:"from_class_id"`
		FromName    string  `json:"from_name"`
		IntoClassID int64   `json:"into_class_id"`
		IntoName    string  `json:"into_name"`
		TriggeredBy *string `json:"triggered_by_nickname"`
		CreatedAt   string  `json:"created_at"`
		UndoneAt    *string `json:"undone_at"`
	}
	out := []item{}
	for rows.Next() {
		var x item
		var at time.Time
		var undone *time.Time
		if err := rows.Scan(&x.ID, &x.FromClassID, &x.FromName, &x.IntoClassID, &x.IntoName, &x.TriggeredBy, &at, &undone); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		x.CreatedAt, x.UndoneAt = httpx.Time(at), httpx.TimePtr(undone)
		out = append(out, x)
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

type classMergeSnapshot struct {
	SlotIDs         []int64        `json:"slot_ids"`
	AssignmentIDs   []int64        `json:"assignment_ids"`
	PointsLedgerIDs []int64        `json:"points_ledger_ids"`
	PostBanIDs      []int64        `json:"post_ban_ids"`
	ChildClassIDs   []int64        `json:"child_class_ids"`
	FromMembers     []memberRecord `json:"from_members"`
	IntoMembers     []memberRecord `json:"into_members"`
	IntoName        string         `json:"into_name"`
	IntoTruncated   bool           `json:"into_name_truncated"`
	IntoTeacher     *string        `json:"into_teacher"`
}

type memberRecord struct {
	UserID   int64      `json:"user_id"`
	JoinedAt time.Time  `json:"joined_at"`
	LeftAt   *time.Time `json:"left_at"`
}

// POST /admin/class-merges/{merge_id}/revert 撤销班级自动合并：按 class_merges 快照把时段、作业、积分流水、
// 禁发记录、子合并班搬回 from，恢复双方成员与 into 的课名/教师。into 之后又被合并走时需先撤销后一次（409 merge_chain）。
func (s *Server) adminRevertClassMerge(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	mid, err := idParam(r, "merge_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var fromID, intoID int64
		var undone *time.Time
		var raw []byte
		err := tx.QueryRow(ctx, `SELECT from_class_id, into_class_id, undone_at, snapshot FROM class_merges WHERE id = $1 FOR UPDATE`, mid).
			Scan(&fromID, &intoID, &undone, &raw)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if undone != nil {
			return httpx.E(http.StatusConflict, "merge_closed", "这次合并已经撤销过")
		}
		var snap classMergeSnapshot
		if err := json.Unmarshal(raw, &snap); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `SELECT id FROM classes WHERE id IN ($1, $2) ORDER BY id FOR UPDATE`, fromID, intoID); err != nil {
			return err
		}
		var fromInto, intoInto *int64
		if err := tx.QueryRow(ctx, `SELECT (SELECT merged_into FROM classes WHERE id = $1),
			(SELECT merged_into FROM classes WHERE id = $2)`, fromID, intoID).Scan(&fromInto, &intoInto); err != nil {
			return err
		}
		if fromInto == nil || *fromInto != intoID || intoInto != nil {
			return httpx.E(http.StatusConflict, "merge_chain", "保留班之后又被合并，请先撤销后一次合并")
		}
		moves := []struct {
			sql string
			ids []int64
		}{
			{`UPDATE class_slots SET class_id = $1 WHERE id = ANY($2) AND class_id = $3`, snap.SlotIDs},
			{`UPDATE assignments SET class_id = $1, updated_at = now() WHERE id = ANY($2) AND class_id = $3`, snap.AssignmentIDs},
			{`UPDATE points_ledger SET class_id = $1 WHERE id = ANY($2) AND class_id = $3`, snap.PointsLedgerIDs},
			{`UPDATE post_bans SET class_id = $1 WHERE id = ANY($2) AND class_id = $3`, snap.PostBanIDs},
			{`UPDATE classes SET merged_into = $1 WHERE id = ANY($2) AND merged_into = $3`, snap.ChildClassIDs},
		}
		for _, m := range moves {
			if len(m.ids) == 0 {
				continue
			}
			if _, err := tx.Exec(ctx, m.sql, fromID, m.ids, intoID); err != nil {
				return err
			}
		}
		// 成员：from 按快照重建；into 里因合并新增的成员移除，原有成员恢复合并前的在班状态
		if _, err := tx.Exec(ctx, `DELETE FROM class_members WHERE class_id = $1`, fromID); err != nil {
			return err
		}
		inInto := map[int64]bool{}
		for _, m := range snap.IntoMembers {
			inInto[m.UserID] = true
			if _, err := tx.Exec(ctx, `UPDATE class_members SET joined_at = $3, left_at = $4 WHERE class_id = $1 AND user_id = $2`,
				intoID, m.UserID, m.JoinedAt, m.LeftAt); err != nil {
				return err
			}
		}
		for _, m := range snap.FromMembers {
			if _, err := tx.Exec(ctx, `INSERT INTO class_members (class_id, user_id, joined_at, left_at) VALUES ($1, $2, $3, $4)`,
				fromID, m.UserID, m.JoinedAt, m.LeftAt); err != nil {
				return err
			}
			if !inInto[m.UserID] {
				if _, err := tx.Exec(ctx, `DELETE FROM class_members WHERE class_id = $1 AND user_id = $2`, intoID, m.UserID); err != nil {
					return err
				}
			}
		}
		if _, err := tx.Exec(ctx, `UPDATE classes SET merged_into = NULL WHERE id = $1`, fromID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE classes SET name = $2, name_truncated = $3, teacher = $4 WHERE id = $1`,
			intoID, snap.IntoName, snap.IntoTruncated, snap.IntoTeacher); err != nil {
			return err
		}
		// 两个班的作业都刷新，App 下次 /sync 会重新拉到
		if _, err := tx.Exec(ctx, `UPDATE assignments SET updated_at = now() WHERE class_id IN ($1, $2)`, fromID, intoID); err != nil {
			return err
		}
		_, err = tx.Exec(ctx, `UPDATE class_merges SET undone_at = now(), undone_by = $2 WHERE id = $1`, mid, admin)
		return err
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
