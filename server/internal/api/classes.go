package api

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
	"github.com/IsANineBug/swufe-homework/server/internal/timetable"
)

var errNoSemester = httpx.E(http.StatusConflict, "no_semester", "当前学期尚未开放，请联系管理员")

func classIDParam(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(chi.URLParam(r, "class_id"), 10, 64)
	if err != nil || id <= 0 {
		return 0, httpx.ErrNotFound
	}
	return id, nil
}

func (s *Server) importTimetable(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Slots []timetable.SlotInput `json:"slots"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	slots, err := timetable.Validate(in.Slots)
	var ve *timetable.ValidationError
	if errors.As(err, &ve) {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "invalid_slot", ve.Msg))
		return
	}
	res, err := timetable.Import(r.Context(), s.DB, currentUser(r).ID, slots)
	if errors.Is(err, timetable.ErrNoSemester) {
		httpx.Fail(w, r, errNoSemester)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, res)
}

func (s *Server) myClasses(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	rows, err := s.DB.Query(ctx, `SELECT c.id FROM class_members m
		JOIN classes c ON c.id = m.class_id
		JOIN semesters se ON se.id = c.semester_id AND se.is_current
		WHERE m.user_id = $1 AND m.left_at IS NULL AND c.merged_into IS NULL
		ORDER BY c.name, c.id`, currentUser(r).ID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[int64])
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	cs, err := store.LoadClasses(ctx, s.DB, ids)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, cs)
}

// leaveClass「不是我的班」：软退出（left_at），幂等。
func (s *Server) leaveClass(w http.ResponseWriter, r *http.Request) {
	id, err := classIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ctx := r.Context()
	id, err = store.ResolveClass(ctx, s.DB, id)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if _, err := s.DB.Exec(ctx, `UPDATE class_members SET left_at = now()
		WHERE class_id = $1 AND user_id = $2 AND left_at IS NULL`, id, currentUser(r).ID); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// getClass 已被自动合并的班返回保留的那个班（id 会变），merged_from 为请求的 id（issue #4 裁决 9）。
func (s *Server) getClass(w http.ResponseWriter, r *http.Request) {
	req, err := classIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ctx := r.Context()
	id, err := store.ResolveClass(ctx, s.DB, req)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	cs, err := store.LoadClasses(ctx, s.DB, []int64{id})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if len(cs) == 0 {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	out := classWithMerge{Class: cs[0]}
	if id != req {
		out.MergedFrom = &req
	}
	httpx.JSON(w, http.StatusOK, out)
}

type classWithMerge struct {
	store.Class
	MergedFrom *int64 `json:"merged_from"`
}

// joinClass 手动加入当前学期的班（含点过「不是我的班」后重新加入），幂等。
// 已合并的班加入保留班；返回加入后的班级。
func (s *Server) joinClass(w http.ResponseWriter, r *http.Request) {
	id, err := classIDParam(r)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ctx := r.Context()
	id, err = store.ResolveClass(ctx, s.DB, id)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	tag, err := s.DB.Exec(ctx, `INSERT INTO class_members (class_id, user_id)
		SELECT c.id, $2 FROM classes c JOIN semesters se ON se.id = c.semester_id AND se.is_current AND NOT se.archived
		WHERE c.id = $1
		ON CONFLICT (class_id, user_id) DO UPDATE SET left_at = NULL`, id, currentUser(r).ID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if tag.RowsAffected() == 0 {
		httpx.Fail(w, r, httpx.E(http.StatusConflict, "class_archived", "该班不属于当前学期，无法加入"))
		return
	}
	cs, err := store.LoadClasses(ctx, s.DB, []int64{id})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, cs[0])
}

// searchClasses 当前学期课名模糊搜索：前缀命中优先，其次 pg_trgm 相似度；最多 20 条。
func (s *Server) searchClasses(w http.ResponseWriter, r *http.Request) {
	q := timetable.StripEllipsis(timetable.CleanName(r.URL.Query().Get("q")))
	if q == "" {
		httpx.JSON(w, http.StatusOK, []store.Class{})
		return
	}
	if len([]rune(q)) > 40 {
		q = string([]rune(q)[:40])
	}
	// LIKE 通配符转义
	esc := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q)
	ctx := r.Context()
	rows, err := s.DB.Query(ctx, `SELECT c.id FROM classes c
		JOIN semesters se ON se.id = c.semester_id AND se.is_current
		WHERE c.merged_into IS NULL
		  AND (c.name ILIKE $1 || '%' OR c.name ILIKE '%' || $1 || '%' OR c.name % $2)
		ORDER BY (c.name ILIKE $1 || '%') DESC, similarity(c.name, $2) DESC, c.id
		LIMIT 20`, esc, q)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[int64])
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	cs, err := store.LoadClasses(ctx, s.DB, ids)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, cs)
}
