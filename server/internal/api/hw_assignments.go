package api

// WS2 作业：班级页、首发、查重、首页、增量同步、详情、改标题/DDL、补充、完成状态、来源编辑/删除。

import (
	"context"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/points"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

// GET /classes/{class_id}/assignments?from=&to= 按 DDL 日期（Asia/Shanghai）浏览，默认前 7 天到后 60 天，最多 120 天。
func (s *Server) listClassAssignments(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	cid, err := classIDParam(r)
	if err == nil {
		cid, err = store.ResolveClass(ctx, s.DB, cid)
		if err == pgx.ErrNoRows {
			err = httpx.ErrNotFound
		}
	}
	if err == nil {
		err = requireMember(ctx, s.DB, cid, r, true)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	today := s.Now().In(httpx.Shanghai)
	today = time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, httpx.Shanghai)
	from, to := today.AddDate(0, 0, -7), today.AddDate(0, 0, 60)
	q := r.URL.Query()
	if v := q.Get("from"); v != "" {
		if from, err = time.ParseInLocation(time.DateOnly, v, httpx.Shanghai); err != nil {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
		if q.Get("to") == "" {
			to = from.AddDate(0, 0, 60)
		}
	}
	if v := q.Get("to"); v != "" {
		if to, err = time.ParseInLocation(time.DateOnly, v, httpx.Shanghai); err != nil {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
	}
	if to.Before(from) || to.Sub(from) > 120*24*time.Hour {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "日期范围不正确（最多 120 天）"))
		return
	}
	out, err := querySummaries(ctx, s.DB, `WHERE a.class_id = $2 AND a.deleted_at IS NULL AND a.merged_into IS NULL
		AND a.ddl >= $3 AND a.ddl < $4 ORDER BY a.ddl, a.id LIMIT 500`, currentUser(r).ID, cid, from, to.AddDate(0, 0, 1))
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

type assignmentCreate struct {
	Title         string     `json:"title"`
	DDL           *time.Time `json:"ddl"`
	DDLUncertain  bool       `json:"ddl_uncertain"`
	Body          string     `json:"body"`
	AttachmentIDs []int64    `json:"attachment_ids"`
	AIJobID       *int64     `json:"ai_job_id"`
}

func cleanTitle(t string) (string, error) {
	t = strings.Join(strings.Fields(t), " ")
	n := utf8.RuneCountInString(t)
	if n < 1 || n > maxTitle {
		return "", httpx.E(http.StatusBadRequest, "bad_request", "标题须为 1–80 字")
	}
	return t, nil
}

func checkBody(b string) error {
	if utf8.RuneCountInString(b) > maxBody {
		return httpx.E(http.StatusBadRequest, "bad_request", "正文最多 5000 字")
	}
	return nil
}

// POST /classes/{class_id}/assignments 首发：作业 + 首发来源 + 附件 + 首发积分（pending 24h）。
func (s *Server) createAssignment(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in assignmentCreate
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	cid, err := classIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	title, err := cleanTitle(in.Title)
	if err == nil {
		err = checkBody(in.Body)
	}
	if err == nil && in.DDL == nil {
		// 契约里 ddl 必填（有图片时尤其必须）
		err = errDDLRequired
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	var aid int64
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		cid, err = store.ResolveClass(ctx, tx, cid)
		if err == pgx.ErrNoRows {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if err := requireMember(ctx, tx, cid, r, false); err != nil {
			return err
		}
		if err := classWritable(ctx, tx, cid); err != nil {
			return err
		}
		if err := checkCanPost(ctx, tx, uid, cid, now); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, `INSERT INTO assignments (class_id, title, ddl, ddl_uncertain, created_by, created_at, updated_at)
			VALUES ($1, $2, $3, $4, $5, $6, $6) RETURNING id`, cid, title, *in.DDL, in.DDLUncertain, uid, now).Scan(&aid); err != nil {
			return err
		}
		var sid int64
		if err := tx.QueryRow(ctx, `INSERT INTO assignment_sources (assignment_id, original_assignment_id, author_id, kind, body, stated_ddl, created_at)
			VALUES ($1, $1, $2, 'first', $3, $4, $5) RETURNING id`, aid, uid, in.Body, *in.DDL, now).Scan(&sid); err != nil {
			return err
		}
		if _, err := bindAttachments(ctx, tx, sid, uid, in.AttachmentIDs); err != nil {
			return err
		}
		return points.AddPending(ctx, tx, uid, cid, sid, "first_post", points.FirstPost, now)
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	v, err := s.loadAssignmentView(ctx, s.DB, uid, aid)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, v)
}

// POST /classes/{class_id}/assignments/candidates 发布前查重：近 7 天发布、DDL ±1 天（给了 DDL 时），
// 按标题相似度取前 3。相似度用字符二元组 Dice 系数（中文标题下比 pg_trgm 稳定，且不依赖数据库 locale）。
func (s *Server) assignmentCandidates(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		Title string     `json:"title"`
		DDL   *time.Time `json:"ddl"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	cid, err := classIDParam(r)
	if err == nil {
		cid, err = store.ResolveClass(ctx, s.DB, cid)
		if err == pgx.ErrNoRows {
			err = httpx.ErrNotFound
		}
	}
	if err == nil {
		err = requireMember(ctx, s.DB, cid, r, false)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	title := strings.TrimSpace(in.Title)
	if title == "" {
		httpx.JSON(w, http.StatusOK, []assignmentSummary{})
		return
	}
	now := s.Now()
	tail := `WHERE a.class_id = $2 AND a.deleted_at IS NULL AND a.merged_into IS NULL AND a.created_at >= $3`
	args := []any{currentUser(r).ID, cid, now.AddDate(0, 0, -7)}
	if in.DDL != nil {
		tail += ` AND a.ddl BETWEEN $4 AND $5`
		args = append(args, in.DDL.Add(-24*time.Hour), in.DDL.Add(24*time.Hour))
	}
	all, err := querySummaries(ctx, s.DB, tail+` ORDER BY a.created_at DESC LIMIT 200`, args...)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	type scored struct {
		a     assignmentSummary
		score float64
	}
	var list []scored
	for _, a := range all {
		if sc := TitleSimilarity(title, a.Title); sc >= candidateMinScore {
			list = append(list, scored{a, sc})
		}
	}
	sort.SliceStable(list, func(i, j int) bool { return list[i].score > list[j].score })
	out := []assignmentSummary{}
	for i := 0; i < len(list) && i < 3; i++ {
		out = append(out, list[i].a)
	}
	httpx.JSON(w, http.StatusOK, out)
}

// candidateMinScore 低于此相似度不算疑似重复。
const candidateMinScore = 0.3

func titleKey(s string) []rune {
	var out []rune
	for _, r := range strings.ToLower(s) {
		if r >= '\uFF01' && r <= '\uFF5E' {
			r -= 0xFEE0
		}
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			out = append(out, unicode.ToLower(r))
		}
	}
	return out
}

// TitleSimilarity 两个标题的字符二元组 Dice 系数（0–1），忽略大小写、全半角、空格与标点。
func TitleSimilarity(a, b string) float64 {
	ra, rb := titleKey(a), titleKey(b)
	if len(ra) == 0 || len(rb) == 0 {
		return 0
	}
	if string(ra) == string(rb) {
		return 1
	}
	grams := func(r []rune) map[string]int {
		m := map[string]int{}
		if len(r) == 1 {
			m[string(r)]++
			return m
		}
		for i := 0; i+1 < len(r); i++ {
			m[string(r[i:i+2])]++
		}
		return m
	}
	ga, gb := grams(ra), grams(rb)
	inter, na, nb := 0, 0, 0
	for k, v := range ga {
		na += v
		if w := gb[k]; w > 0 {
			inter += min(v, w)
		}
	}
	for _, v := range gb {
		nb += v
	}
	return 2 * float64(inter) / float64(na+nb)
}

// myClassesCond 当前用户在当前学期在班的班级（$1 = 用户 id）。
const myClassesCond = `a.class_id IN (SELECT m.class_id FROM class_members m
	JOIN classes mc ON mc.id = m.class_id JOIN semesters se ON se.id = mc.semester_id AND se.is_current
	WHERE m.user_id = $1 AND m.left_at IS NULL AND mc.merged_into IS NULL)`

// GET /feed 首页：我所有班级未过期作业，按 DDL 升序。
func (s *Server) feed(w http.ResponseWriter, r *http.Request) {
	includeDone := r.URL.Query().Get("include_done") == "true"
	tail := `WHERE ` + myClassesCond + ` AND a.deleted_at IS NULL AND a.merged_into IS NULL AND a.ddl >= $2`
	if !includeDone {
		tail += ` AND NOT EXISTS (SELECT 1 FROM completions d WHERE d.assignment_id = a.id AND d.user_id = $1)`
	}
	out, err := querySummaries(r.Context(), s.DB, tail+` ORDER BY a.ddl, a.id LIMIT 300`, currentUser(r).ID, s.Now())
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

// syncOverlap 返回的 now 往前留出余量，覆盖 since 边界上仍在提交的事务（重复下发无害）。
const syncOverlap = 30 * time.Second

// GET /sync?since= 增量同步：changed = since 之后有变化的在世作业（含本人完成状态变化），
// removed_ids = since 之后被删除或合并走的作业。不给 since 时下发全部未过期（DDL ≥ 现在 − 1 天）的作业。
func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := currentUser(r).ID
	now := s.Now()
	var since *time.Time
	if v := r.URL.Query().Get("since"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
		since = &t
	}
	var changed []assignmentSummary
	removed := []int64{}
	var err error
	if since == nil {
		changed, err = querySummaries(ctx, s.DB, `WHERE `+myClassesCond+` AND a.deleted_at IS NULL AND a.merged_into IS NULL
			AND a.ddl >= $2 ORDER BY a.ddl, a.id LIMIT 1000`, uid, now.Add(-24*time.Hour))
	} else {
		changed, err = querySummaries(ctx, s.DB, `WHERE `+myClassesCond+` AND a.deleted_at IS NULL AND a.merged_into IS NULL
			AND (a.updated_at > $2 OR EXISTS (SELECT 1 FROM completions d WHERE d.assignment_id = a.id AND d.user_id = $1 AND d.done_at > $2))
			ORDER BY a.ddl, a.id LIMIT 1000`, uid, *since)
		if err == nil {
			var rows pgx.Rows
			rows, err = s.DB.Query(ctx, `SELECT a.id FROM assignments a WHERE `+myClassesCond+`
				AND (a.deleted_at IS NOT NULL OR a.merged_into IS NOT NULL) AND a.updated_at > $2 ORDER BY a.id LIMIT 1000`, uid, *since)
			if err == nil {
				removed, err = pgx.CollectRows(rows, pgx.RowTo[int64])
				if removed == nil {
					removed = []int64{}
				}
			}
		}
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"now":         httpx.Time(now.Add(-syncOverlap)),
		"changed":     changed,
		"removed_ids": removed,
	})
}

// GET /assignments/{assignment_id} 已合并的作业返回合并目标（merged_from 含请求的 id）。
func (s *Server) getAssignment(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "assignment_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	a, err := resolveAsg(ctx, s.DB, id)
	if err == nil {
		err = requireMember(ctx, s.DB, a.ClassID, r, true)
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	v, err := s.loadAssignmentView(ctx, s.DB, currentUser(r).ID, a.ID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, v)
}

// PATCH /assignments/{assignment_id} 首发者修改标题/DDL。改 DDL 同步首发来源的 stated_ddl、重算标红、清空旧提醒文案。
func (s *Server) patchAssignment(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		Title        *string    `json:"title"`
		DDL          *time.Time `json:"ddl"`
		DDLUncertain *bool      `json:"ddl_uncertain"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	id, err := idParam(r, "assignment_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var title *string
	if in.Title != nil {
		t, err := cleanTitle(*in.Title)
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		title = &t
	}
	uid := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		a, err := loadAsgRef(ctx, tx, id, true)
		if err != nil {
			return err
		}
		if a.Deleted || a.MergedInto != nil {
			return httpx.ErrNotFound
		}
		if a.CreatedBy != uid {
			return httpx.E(http.StatusForbidden, "forbidden", "只有首发者可以修改")
		}
		if !a.Writable {
			return errClassArchived
		}
		ddlChanged := in.DDL != nil && !in.DDL.Equal(a.DDL)
		if _, err := tx.Exec(ctx, `UPDATE assignments SET title = COALESCE($2, title), ddl = COALESCE($3, ddl),
			ddl_uncertain = CASE WHEN $3::timestamptz IS NOT NULL THEN COALESCE($4, false) ELSE COALESCE($4, ddl_uncertain) END,
			reminder_text = CASE WHEN $5 THEN NULL ELSE reminder_text END
			WHERE id = $1`, id, title, in.DDL, in.DDLUncertain, ddlChanged); err != nil {
			return err
		}
		if ddlChanged {
			if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET stated_ddl = $2, edited_at = now()
				WHERE assignment_id = $1 AND kind = 'first' AND author_id = $3 AND deleted_at IS NULL`, id, *in.DDL, uid); err != nil {
				return err
			}
		}
		return recomputeConflict(ctx, tx, id)
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	v, err := s.loadAssignmentView(ctx, s.DB, uid, id)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, v)
}

type sourceInput struct {
	Body          *string    `json:"body"`
	StatedDDL     *time.Time `json:"stated_ddl"`
	AttachmentIDs *[]int64   `json:"attachment_ids"`
}

// POST /assignments/{assignment_id}/sources 补充：须有正文或附件；stated_ddl 与作业 DDL 不一致则标红。
func (s *Server) addSource(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in sourceInput
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	id, err := idParam(r, "assignment_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	body := ""
	if in.Body != nil {
		body = *in.Body
	}
	var atts []int64
	if in.AttachmentIDs != nil {
		atts = *in.AttachmentIDs
	}
	if err := checkBody(body); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if strings.TrimSpace(body) == "" && len(atts) == 0 {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "补充内容不能为空"))
		return
	}
	uid := currentUser(r).ID
	now := s.Now()
	var sid int64
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		a, err := resolveAsg(ctx, tx, id)
		if err != nil {
			return err
		}
		if a, err = loadAsgRef(ctx, tx, a.ID, true); err != nil {
			return err
		}
		if err := requireMember(ctx, tx, a.ClassID, r, false); err != nil {
			return err
		}
		if !a.Writable {
			return errClassArchived
		}
		if err := checkCanPost(ctx, tx, uid, a.ClassID, now); err != nil {
			return err
		}
		if err := tx.QueryRow(ctx, `INSERT INTO assignment_sources (assignment_id, original_assignment_id, author_id, kind, body, stated_ddl, created_at)
			VALUES ($1, $1, $2, 'supplement', $3, $4, $5) RETURNING id`, a.ID, uid, body, in.StatedDDL, now).Scan(&sid); err != nil {
			return err
		}
		if _, err := bindAttachments(ctx, tx, sid, uid, atts); err != nil {
			return err
		}
		if err := points.AddPending(ctx, tx, uid, a.ClassID, sid, "supplement", points.Supplement, now); err != nil {
			return err
		}
		return recomputeConflict(ctx, tx, a.ID)
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.writeSource(ctx, w, r, sid, http.StatusCreated)
}

func (s *Server) writeSource(ctx context.Context, w http.ResponseWriter, r *http.Request, sid int64, status int) {
	vs, err := s.loadSources(ctx, s.DB, currentUser(r).ID, `s.id = $2`, sid)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if len(vs) == 0 {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	httpx.JSON(w, status, vs[0])
}

// PUT/DELETE /assignments/{assignment_id}/done 完成状态，仅本人可见。
func (s *Server) setDone(done bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		id, err := idParam(r, "assignment_id")
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		a, err := resolveAsg(ctx, s.DB, id)
		if err == nil {
			err = requireMember(ctx, s.DB, a.ClassID, r, false)
		}
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		uid := currentUser(r).ID
		if done {
			_, err = s.DB.Exec(ctx, `INSERT INTO completions (user_id, assignment_id) VALUES ($1, $2)
				ON CONFLICT (user_id, assignment_id) DO NOTHING`, uid, a.ID)
		} else {
			// 合并后完成状态是并集：取消时一并取消被合并作业上的记录
			_, err = s.DB.Exec(ctx, `DELETE FROM completions WHERE user_id = $1 AND (assignment_id = $2
				OR assignment_id IN (SELECT id FROM assignments WHERE merged_into = $2))`, uid, a.ID)
		}
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// PATCH /sources/{source_id} 作者编辑自己的来源。给了 attachment_ids 则替换附件（去掉的附件解绑，24h 后清理）。
func (s *Server) patchSource(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in sourceInput
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	id, err := idParam(r, "source_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if in.Body != nil {
		if err := checkBody(*in.Body); err != nil {
			httpx.Fail(w, r, err)
			return
		}
	}
	uid := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		src, err := loadSrcRef(ctx, tx, id, true)
		if err != nil {
			return err
		}
		if src.Deleted {
			return httpx.ErrNotFound
		}
		if src.AuthorID != uid {
			return httpx.E(http.StatusForbidden, "forbidden", "只能编辑自己的内容")
		}
		if !src.Writable {
			return errClassArchived
		}
		if _, err := tx.Exec(ctx, `UPDATE assignment_sources SET body = COALESCE($2, body),
			stated_ddl = CASE WHEN $4 THEN $3 ELSE stated_ddl END, edited_at = now() WHERE id = $1`,
			id, in.Body, in.StatedDDL, in.StatedDDL != nil); err != nil {
			return err
		}
		if in.AttachmentIDs != nil {
			keep := uniqIDs(*in.AttachmentIDs)
			if len(keep) > maxAttachments {
				return httpx.E(http.StatusBadRequest, "bad_request", "最多 6 个附件")
			}
			if _, err := tx.Exec(ctx, `UPDATE attachments SET source_id = NULL, created_at = now()
				WHERE source_id = $1 AND NOT (id = ANY($2))`, id, keep); err != nil {
				return err
			}
			var newIDs []int64
			rows, err := tx.Query(ctx, `SELECT x FROM unnest($1::bigint[]) x
				WHERE NOT EXISTS (SELECT 1 FROM attachments WHERE id = x AND source_id = $2)`, keep, id)
			if err != nil {
				return err
			}
			if newIDs, err = pgx.CollectRows(rows, pgx.RowTo[int64]); err != nil {
				return err
			}
			if _, err := bindAttachments(ctx, tx, id, uid, newIDs); err != nil {
				return err
			}
			var left int
			if err := tx.QueryRow(ctx, `SELECT count(*) FROM attachments WHERE source_id = $1`, id).Scan(&left); err != nil {
				return err
			}
			var body string
			if err := tx.QueryRow(ctx, `SELECT body FROM assignment_sources WHERE id = $1`, id).Scan(&body); err != nil {
				return err
			}
			if src.Kind == "supplement" && left == 0 && strings.TrimSpace(body) == "" {
				return httpx.E(http.StatusBadRequest, "bad_request", "补充内容不能为空")
			}
		}
		return recomputeConflict(ctx, tx, src.AssignmentID)
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.writeSource(ctx, w, r, id, http.StatusOK)
}

// DELETE /sources/{source_id} 作者删除自己的来源：不扣分，但该来源积分作废。
func (s *Server) deleteSource(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "source_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	uid := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		src, err := loadSrcRef(ctx, tx, id, true)
		if err != nil {
			return err
		}
		if src.Deleted {
			return nil // 幂等
		}
		if src.AuthorID != uid {
			return httpx.E(http.StatusForbidden, "forbidden", "只能删除自己的内容")
		}
		if !src.Writable {
			return errClassArchived
		}
		if _, err := loadAsgRef(ctx, tx, src.AssignmentID, true); err != nil {
			return err
		}
		if err := softDeleteSource(ctx, tx, src, "author"); err != nil {
			return err
		}
		return points.VoidSource(ctx, tx, id)
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
