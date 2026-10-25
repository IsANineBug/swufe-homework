package api

// WS2 输出结构与批量装载（固定条数查询，无 N+1）。完成状态只取本人。

import (
	"context"
	"encoding/json"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

type assignmentSummary struct {
	ID           int64           `json:"id"`
	ClassID      int64           `json:"class_id"`
	ClassName    string          `json:"class_name"`
	Title        string          `json:"title"`
	DDL          string          `json:"ddl"`
	DDLUncertain bool            `json:"ddl_uncertain"`
	DDLConflict  bool            `json:"ddl_conflict"`
	Done         bool            `json:"done"`
	SourceCount  int             `json:"source_count"`
	ReminderText json.RawMessage `json:"reminder_text"`
	UpdatedAt    string          `json:"updated_at"`
}

type attachmentView struct {
	ID       int64   `json:"id"`
	Kind     string  `json:"kind"`
	URL      string  `json:"url"`
	ThumbURL *string `json:"thumb_url"`
	Bytes    int     `json:"bytes"`
}

type sourceView struct {
	ID              int64            `json:"id"`
	Kind            string           `json:"kind"`
	AuthorID        int64            `json:"author_id"` // 作者用户 id（举报用户用）
	AuthorNickname  string           `json:"author_nickname"`
	IsMine          bool             `json:"is_mine"` // v2：按用户 id 判断，不按昵称
	Body            string           `json:"body"`
	StatedDDL       *string          `json:"stated_ddl"`
	Attachments     []attachmentView `json:"attachments"`
	CreatedAt       string           `json:"created_at"`
	ThanksCount     int              `json:"thanks_count"`
	ThankedByMe     bool             `json:"thanked_by_me"`
	VotedDeleteByMe bool             `json:"voted_delete_by_me"`
	// 以下仅管理员 /admin/deleted 返回
	AssignmentID  *int64  `json:"assignment_id,omitempty"`
	DeletedAt     *string `json:"deleted_at,omitempty"`
	DeletedReason *string `json:"deleted_reason,omitempty"`
}

type mergeProposalView struct {
	ID          int64  `json:"id"`
	FromID      int64  `json:"from_id"`
	ToID        int64  `json:"to_id"`
	Status      string `json:"status"`
	Votes       int    `json:"votes"`
	NeededVotes int    `json:"needed_votes"`
	VotedByMe   bool   `json:"voted_by_me"` // v2：当前用户已投同意票（提议人本人为 true）
}

type assignmentView struct {
	assignmentSummary
	FirstPosterNickname string              `json:"first_poster_nickname"`
	IsFirstPoster       bool                `json:"is_first_poster"` // v2：按用户 id 判断
	Sources             []sourceView        `json:"sources"`
	MergedFrom          []int64             `json:"merged_from"`
	PendingMerges       []mergeProposalView `json:"pending_merges"`
}

// summarySQL 的 $1 固定为当前用户 id；调用方拼接 WHERE/ORDER。
const summarySQL = `SELECT a.id, a.class_id, c.name, a.title, a.ddl, a.ddl_uncertain, a.ddl_conflict,
	EXISTS (SELECT 1 FROM completions d WHERE d.assignment_id = a.id AND d.user_id = $1),
	(SELECT count(*) FROM assignment_sources s WHERE s.assignment_id = a.id AND s.deleted_at IS NULL),
	a.reminder_text, a.updated_at
	FROM assignments a JOIN classes c ON c.id = a.class_id `

func scanSummaries(rows pgx.Rows) ([]assignmentSummary, error) {
	defer rows.Close()
	out := []assignmentSummary{}
	for rows.Next() {
		var a assignmentSummary
		var ddl, upd time.Time
		var rt []byte
		if err := rows.Scan(&a.ID, &a.ClassID, &a.ClassName, &a.Title, &ddl, &a.DDLUncertain, &a.DDLConflict,
			&a.Done, &a.SourceCount, &rt, &upd); err != nil {
			return nil, err
		}
		a.DDL, a.UpdatedAt = httpx.Time(ddl), httpx.Time(upd)
		if len(rt) > 0 {
			a.ReminderText = rt
		} else {
			a.ReminderText = json.RawMessage("null")
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func querySummaries(ctx context.Context, q store.Q, sqlTail string, args ...any) ([]assignmentSummary, error) {
	rows, err := q.Query(ctx, summarySQL+sqlTail, args...)
	if err != nil {
		return nil, err
	}
	return scanSummaries(rows)
}

// loadSources 装载来源（含附件、谢谢数、本人是否谢过/投过删除）。where 的 $1 为当前用户 id。
func (s *Server) loadSources(ctx context.Context, q store.Q, uid int64, where string, args ...any) ([]sourceView, error) {
	all := append([]any{uid}, args...)
	rows, err := q.Query(ctx, `SELECT s.id, s.kind, s.author_id, u.nickname, s.body, s.stated_ddl, s.created_at,
		(SELECT count(*) FROM thanks t WHERE t.source_id = s.id),
		EXISTS (SELECT 1 FROM thanks t WHERE t.source_id = s.id AND t.user_id = $1),
		EXISTS (SELECT 1 FROM delete_votes v WHERE v.source_id = s.id AND v.user_id = $1),
		s.assignment_id, s.deleted_at, s.deleted_reason, s.author_id = $1
		FROM assignment_sources s JOIN users u ON u.id = s.author_id WHERE `+where, all...)
	if err != nil {
		return nil, err
	}
	out := []sourceView{}
	idx := map[int64]int{}
	var ids []int64
	for rows.Next() {
		var v sourceView
		var stated *time.Time
		var created time.Time
		var aid int64
		var delAt *time.Time
		var delWhy *string
		if err := rows.Scan(&v.ID, &v.Kind, &v.AuthorID, &v.AuthorNickname, &v.Body, &stated, &created,
			&v.ThanksCount, &v.ThankedByMe, &v.VotedDeleteByMe, &aid, &delAt, &delWhy, &v.IsMine); err != nil {
			rows.Close()
			return nil, err
		}
		v.StatedDDL, v.CreatedAt = httpx.TimePtr(stated), httpx.Time(created)
		v.Attachments = []attachmentView{}
		if delAt != nil {
			v.AssignmentID, v.DeletedAt, v.DeletedReason = &aid, httpx.TimePtr(delAt), delWhy
		}
		idx[v.ID] = len(out)
		ids = append(ids, v.ID)
		out = append(out, v)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err = q.Query(ctx, `SELECT id, source_id, kind, path, thumb_path, bytes FROM attachments
		WHERE source_id = ANY($1) AND purged_at IS NULL ORDER BY id`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	now := s.Now()
	for rows.Next() {
		var a attachmentView
		var sid int64
		var p string
		var thumb *string
		if err := rows.Scan(&a.ID, &sid, &a.Kind, &p, &thumb, &a.Bytes); err != nil {
			return nil, err
		}
		a.URL = s.Files.URL(p, now)
		if thumb != nil {
			t := s.Files.URL(*thumb, now)
			a.ThumbURL = &t
		}
		out[idx[sid]].Attachments = append(out[idx[sid]].Attachments, a)
	}
	return out, rows.Err()
}

// loadProposals 的 $1 固定为当前用户 id（算 voted_by_me），where 里的参数从 $2 起。
func loadProposals(ctx context.Context, q store.Q, uid int64, where string, args ...any) ([]mergeProposalView, error) {
	rows, err := q.Query(ctx, `SELECT p.id, p.from_id, p.to_id, p.status,
		(SELECT count(*) FROM merge_votes v WHERE v.proposal_id = p.id),
		p.proposer_id = $1 OR EXISTS (SELECT 1 FROM merge_votes v WHERE v.proposal_id = p.id AND v.user_id = $1)
		FROM merge_proposals p WHERE `+where+` ORDER BY p.id`, append([]any{uid}, args...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []mergeProposalView{}
	for rows.Next() {
		var m mergeProposalView
		if err := rows.Scan(&m.ID, &m.FromID, &m.ToID, &m.Status, &m.Votes, &m.VotedByMe); err != nil {
			return nil, err
		}
		m.NeededVotes = mergeVotesNeeded
		out = append(out, m)
	}
	return out, rows.Err()
}

// loadAssignmentView 作业详情（已解析到最终作业 id）。
func (s *Server) loadAssignmentView(ctx context.Context, q store.Q, uid, id int64) (*assignmentView, error) {
	sums, err := querySummaries(ctx, q, `WHERE a.id = $2`, uid, id)
	if err != nil {
		return nil, err
	}
	if len(sums) == 0 {
		return nil, httpx.ErrNotFound
	}
	v := &assignmentView{assignmentSummary: sums[0]}
	if err := q.QueryRow(ctx, `SELECT u.nickname, a.created_by = $2 FROM assignments a JOIN users u ON u.id = a.created_by WHERE a.id = $1`, id, uid).
		Scan(&v.FirstPosterNickname, &v.IsFirstPoster); err != nil {
		return nil, err
	}
	if v.Sources, err = s.loadSources(ctx, q, uid, `s.assignment_id = $2 AND s.deleted_at IS NULL ORDER BY (s.kind = 'first') DESC, s.created_at, s.id`, id); err != nil {
		return nil, err
	}
	rows, err := q.Query(ctx, `SELECT id FROM assignments WHERE merged_into = $1 ORDER BY id`, id)
	if err != nil {
		return nil, err
	}
	if v.MergedFrom, err = pgx.CollectRows(rows, pgx.RowTo[int64]); err != nil {
		return nil, err
	}
	if v.MergedFrom == nil {
		v.MergedFrom = []int64{}
	}
	if v.PendingMerges, err = loadProposals(ctx, q, uid, `p.status = 'pending' AND (p.from_id = $2 OR p.to_id = $2)`, id); err != nil {
		return nil, err
	}
	return v, nil
}
