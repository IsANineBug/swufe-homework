package api

// WS8（v2）管理员接口：举报处理、班级清理（隐藏/改名/手动合并）、直接删除来源与消息、
// 用户（只含 AdminUser 字段）、处罚（禁言/全站禁发/封号）、调整贡献值、操作日志。
// 全部挂在 /admin 下（RequireAdmin）；每个写操作在同一事务里写一行 admin_actions。
// 隐私底线（requirements.md 十三.8）：不返回密码、恢复码、token、某人加入了哪些班、个人课表、完成情况、提醒、IP。

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/points"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
	"github.com/IsANineBug/swufe-homework/server/internal/timetable"
)

// ---------- 用户 ----------

// adminUser 与 api.yaml components.schemas.AdminUser 一一对应，不得增加字段（有 JSON 快照测试）。
type adminUser struct {
	ID                 int64      `json:"id"`
	Nickname           string     `json:"nickname"`
	Role               string     `json:"role"`
	RegisteredVia      string     `json:"registered_via"`
	InviterNickname    *string    `json:"inviter_nickname"`
	CreatedAt          string     `json:"created_at"`
	Disabled           bool       `json:"disabled"`
	ContributionTotal  int        `json:"contribution_total"`
	DeletedContent30d  int        `json:"deleted_content_30d"`
	ReportsAgainstOpen int        `json:"reports_against_open"`
	Sanctions          []sanction `json:"sanctions"`
}

// loadAdminUsers 按 ids 顺序装载 AdminUser，固定 5 条查询，无 N+1。
func loadAdminUsers(ctx context.Context, q store.Q, ids []int64, now time.Time) ([]adminUser, error) {
	out := make([]adminUser, 0, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	by := map[int64]*adminUser{}
	rows, err := q.Query(ctx, `SELECT u.id, u.nickname, u.role, u.registered_via, i.nickname, u.created_at, u.disabled_at IS NOT NULL
		FROM users u LEFT JOIN users i ON i.id = u.inviter_id WHERE u.id = ANY($1)`, ids)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		u := &adminUser{Sanctions: []sanction{}}
		var at time.Time
		if err := rows.Scan(&u.ID, &u.Nickname, &u.Role, &u.RegisteredVia, &u.InviterNickname, &at, &u.Disabled); err != nil {
			rows.Close()
			return nil, err
		}
		u.CreatedAt = httpx.Time(at)
		by[u.ID] = u
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	counts := []struct {
		sql string
		set func(*adminUser, int)
	}{
		{`SELECT p.user_id, coalesce(sum(p.delta), 0)::int FROM points_ledger p
			JOIN semesters se ON se.id = p.semester_id AND se.is_current
			WHERE p.user_id = ANY($1) AND p.status = 'settled' AND $2::timestamptz IS NOT NULL GROUP BY p.user_id`,
			func(u *adminUser, n int) { u.ContributionTotal = n }},
		{`SELECT author_id, count(*)::int FROM assignment_sources
			WHERE author_id = ANY($1) AND deleted_reason IN ('vote','admin') AND deleted_at > $2::timestamptz - interval '30 days'
			GROUP BY author_id`,
			func(u *adminUser, n int) { u.DeletedContent30d = n }},
		{`SELECT target_id, count(*)::int FROM reports
			WHERE target_kind = 'user' AND target_id = ANY($1) AND handled_at IS NULL AND $2::timestamptz IS NOT NULL
			GROUP BY target_id`,
			func(u *adminUser, n int) { u.ReportsAgainstOpen = n }},
	}
	for _, c := range counts {
		rows, err := q.Query(ctx, c.sql, ids, now)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var id int64
			var n int
			if err := rows.Scan(&id, &n); err != nil {
				rows.Close()
				return nil, err
			}
			if u := by[id]; u != nil {
				c.set(u, n)
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	// 最近 20 条处罚（含已解除）
	rows, err = q.Query(ctx, `SELECT user_id, id, kind, reason, starts_at, ends_at, lifted_at FROM (
			SELECT *, row_number() OVER (PARTITION BY user_id ORDER BY id DESC) AS rn
			FROM user_sanctions WHERE user_id = ANY($1)) x
		WHERE rn <= 20 ORDER BY user_id, id DESC`, ids)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var uid int64
		var sc sanction
		var starts time.Time
		var ends, lifted *time.Time
		if err := rows.Scan(&uid, &sc.ID, &sc.Kind, &sc.Reason, &starts, &ends, &lifted); err != nil {
			rows.Close()
			return nil, err
		}
		sc.StartsAt, sc.EndsAt, sc.LiftedAt = httpx.Time(starts), httpx.TimePtr(ends), httpx.TimePtr(lifted)
		if u := by[uid]; u != nil {
			u.Sanctions = append(u.Sanctions, sc)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, id := range ids {
		if u := by[id]; u != nil {
			out = append(out, *u)
		}
	}
	return out, nil
}

func (s *Server) loadAdminUser(ctx context.Context, q store.Q, id int64) (*adminUser, error) {
	us, err := loadAdminUsers(ctx, q, []int64{id}, s.Now())
	if err != nil {
		return nil, err
	}
	if len(us) == 0 {
		return nil, httpx.ErrNotFound
	}
	return &us[0], nil
}

// GET /admin/users?q= 按昵称找用户（前缀优先，其次包含），最多 50 条。
func (s *Server) adminUsers(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := timetable.CleanName(r.URL.Query().Get("q"))
	if q == "" {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "请输入昵称"))
		return
	}
	esc := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q)
	rows, err := s.DB.Query(ctx, `SELECT id FROM users WHERE nickname ILIKE '%' || $1 || '%'
		ORDER BY (nickname ILIKE $1 || '%') DESC, char_length(nickname), id LIMIT 50`, esc)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[int64])
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	out, err := loadAdminUsers(ctx, s.DB, ids, s.Now())
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

func (s *Server) adminGetUser(w http.ResponseWriter, r *http.Request) {
	id, err := idParam(r, "user_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	u, err := s.loadAdminUser(r.Context(), s.DB, id)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, u)
}

// PATCH /admin/users/{user_id} 强制改违规昵称（同注册规则），用户收到 nickname_reset 通知。
func (s *Server) adminPatchUser(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "user_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var in struct {
		Nickname string `json:"nickname"`
		Reason   string `json:"reason"`
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
	reason, err := cleanReason(in.Reason, true)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var old string
		err := tx.QueryRow(ctx, `SELECT nickname FROM users WHERE id = $1 FOR UPDATE`, id).Scan(&old)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if old == nick {
			return nil
		}
		_, err = tx.Exec(ctx, `UPDATE users SET nickname = $2 WHERE id = $1`, id, nick)
		if store.PgCode(err) == "23505" {
			return errNicknameTaken
		}
		if err != nil {
			return err
		}
		if err := notify(ctx, tx, id, "nickname_reset", map[string]any{"old": old, "new": nick, "reason": *reason}); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "user_nickname_reset", "user", id, reason, map[string]any{"before": old, "after": nick})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	s.adminGetUser(w, r)
}

// POST /admin/users/{user_id}/sanctions 禁言 / 全站禁发 / 封号。
func (s *Server) adminAddSanction(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "user_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var in struct {
		Kind   string `json:"kind"`
		Days   *int   `json:"days"`
		Reason string `json:"reason"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	switch in.Kind {
	case "mute", "post_ban":
		if in.Days == nil {
			httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "禁言和禁发必须给天数（1–365）"))
			return
		}
	case "disable":
	default:
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "kind 须为 mute、post_ban 或 disable"))
		return
	}
	if in.Days != nil && (*in.Days < 1 || *in.Days > 365) {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "天数须为 1–365"))
		return
	}
	reason, err := cleanReason(in.Reason, true)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	now := s.Now()
	var ends *time.Time
	if in.Days != nil {
		e := now.Add(time.Duration(*in.Days) * 24 * time.Hour)
		ends = &e
	}
	var out sanction
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var role string
		err := tx.QueryRow(ctx, `SELECT role FROM users WHERE id = $1 FOR UPDATE`, id).Scan(&role)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if id == admin || role == "admin" {
			return httpx.E(http.StatusForbidden, "forbidden", "不能处罚管理员")
		}
		var starts time.Time
		if err := tx.QueryRow(ctx, `INSERT INTO user_sanctions (user_id, kind, reason, starts_at, ends_at, created_by)
			VALUES ($1, $2, $3, $4, $5, $6) RETURNING id, starts_at`, id, in.Kind, *reason, now, ends, admin).Scan(&out.ID, &starts); err != nil {
			return err
		}
		out.Kind, out.Reason, out.StartsAt, out.EndsAt = in.Kind, *reason, httpx.Time(starts), httpx.TimePtr(ends)
		if in.Kind == "disable" {
			// 立即生效：写 disabled_at、作废全部 refresh token；现有 access token 由 requireUser 按 disabled_at 拦下
			if _, err := tx.Exec(ctx, `UPDATE users SET disabled_at = coalesce(disabled_at, $2) WHERE id = $1`, id, now); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `UPDATE auth_tokens SET revoked_at = $2 WHERE user_id = $1 AND revoked_at IS NULL`, id, now); err != nil {
				return err
			}
		}
		if err := notify(ctx, tx, id, "sanction", map[string]any{"kind": in.Kind, "ends_at": httpx.TimePtr(ends), "reason": *reason}); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "sanction_add", "user", id, reason, map[string]any{
			"sanction_id": out.ID, "kind": in.Kind, "days": in.Days, "ends_at": httpx.TimePtr(ends),
		})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, out)
}

// POST /admin/sanctions/{sanction_id}/lift 解除处罚（幂等）；解除 disable 且没有其他生效中的封号时清空 users.disabled_at。
func (s *Server) adminLiftSanction(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "sanction_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	now := s.Now()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var uid int64
		var kind string
		var lifted *time.Time
		err := tx.QueryRow(ctx, `SELECT user_id, kind, lifted_at FROM user_sanctions WHERE id = $1 FOR UPDATE`, id).Scan(&uid, &kind, &lifted)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if lifted != nil {
			return nil
		}
		if _, err := tx.Exec(ctx, `UPDATE user_sanctions SET lifted_at = $2, lifted_by = $3 WHERE id = $1`, id, now, admin); err != nil {
			return err
		}
		if kind == "disable" {
			if _, err := tx.Exec(ctx, `UPDATE users u SET disabled_at = NULL WHERE u.id = $1
				AND NOT EXISTS (SELECT 1 FROM user_sanctions s WHERE s.user_id = $1 AND s.kind = 'disable'
				                AND s.lifted_at IS NULL AND s.starts_at <= $2 AND (s.ends_at IS NULL OR s.ends_at > $2))`, uid, now); err != nil {
				return err
			}
		}
		return logAdmin(ctx, tx, admin, "sanction_lift", "user", uid, nil, map[string]any{"sanction_id": id, "kind": kind})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /admin/users/{user_id}/points 调整某人在某班的贡献值（admin_adjust，settled，不计每日上限）。
func (s *Server) adminAdjustPoints(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "user_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var in struct {
		ClassID int64  `json:"class_id"`
		Delta   int    `json:"delta"`
		Reason  string `json:"reason"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if in.Delta == 0 || in.Delta < -100 || in.Delta > 100 {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "调整值须为 −100 到 100 且不为 0"))
		return
	}
	reason, err := cleanReason(in.Reason, true)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	now := s.Now()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM users WHERE id = $1)`, id).Scan(&exists); err != nil {
			return err
		}
		if !exists {
			return httpx.ErrNotFound
		}
		cid, err := store.ResolveClass(ctx, tx, in.ClassID)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.E(http.StatusBadRequest, "bad_request", "班级不存在")
		}
		if err != nil {
			return err
		}
		var className string
		if err := tx.QueryRow(ctx, `SELECT name FROM classes WHERE id = $1`, cid).Scan(&className); err != nil {
			return err
		}
		if err := points.AdminAdjust(ctx, tx, id, cid, in.Delta, *reason, now); err != nil {
			return err
		}
		if err := notify(ctx, tx, id, "points_adjusted", map[string]any{"delta": in.Delta, "class_name": className, "reason": *reason}); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "points_adjust", "user", id, reason, map[string]any{"class_id": cid, "delta": in.Delta})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

// ---------- 班级 ----------

// adminClass 与 api.yaml components.schemas.AdminClass 一致。
type adminClass struct {
	ID                int64   `json:"id"`
	Name              string  `json:"name"`
	Teacher           *string `json:"teacher"`
	SemesterName      string  `json:"semester_name"`
	MemberCount       int     `json:"member_count"`
	AssignmentCount   int     `json:"assignment_count"`
	SlotCount         int     `json:"slot_count"`
	CreatedByNickname string  `json:"created_by_nickname"`
	CreatedAt         string  `json:"created_at"`
	Hidden            bool    `json:"hidden"`
}

// adminClassSQL 计数用相关子查询（各走 class_members 主键、assignments_class_ddl、class_slots_class 索引），每页最多 50 行。
const adminClassSQL = `SELECT c.id, c.name, c.teacher, se.name,
	(SELECT count(*) FROM class_members m WHERE m.class_id = c.id AND m.left_at IS NULL)::int,
	(SELECT count(*) FROM assignments a WHERE a.class_id = c.id AND a.deleted_at IS NULL AND a.merged_into IS NULL)::int,
	(SELECT count(*) FROM class_slots cs WHERE cs.class_id = c.id)::int,
	u.nickname, c.created_at, c.hidden_at IS NOT NULL
	FROM classes c JOIN semesters se ON se.id = c.semester_id JOIN users u ON u.id = c.created_by `

// suspiciousNameRE 班名含「非常见字符」：常用汉字、字母数字、常见括号与标点、罗马数字、课表里的 ▲△★☆ 之外的字符。
const suspiciousNameRE = `[^\u4e00-\u9fffA-Za-z0-9 （）()·•—_、，,.．:：/+＋#＃Ⅰ-Ⅻⅰ-ⅻ▲△★☆-]`

func scanAdminClasses(rows pgx.Rows) ([]adminClass, error) {
	defer rows.Close()
	out := []adminClass{}
	for rows.Next() {
		var c adminClass
		var at time.Time
		if err := rows.Scan(&c.ID, &c.Name, &c.Teacher, &c.SemesterName, &c.MemberCount, &c.AssignmentCount, &c.SlotCount,
			&c.CreatedByNickname, &at, &c.Hidden); err != nil {
			return nil, err
		}
		c.CreatedAt = httpx.Time(at)
		out = append(out, c)
	}
	return out, rows.Err()
}

// GET /admin/classes?q=&filter=all|suspicious&include_hidden=&page= 按 created_at 倒序，每页 50；不含已合并走的班。
func (s *Server) adminClasses(w http.ResponseWriter, r *http.Request) {
	qs := r.URL.Query()
	filter := qs.Get("filter")
	if filter == "" {
		filter = "all"
	}
	if filter != "all" && filter != "suspicious" {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "filter 须为 all 或 suspicious"))
		return
	}
	page := 1
	if v := qs.Get("page"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
		page = n
	}
	includeHidden := qs.Get("include_hidden") == "true"
	q := timetable.CleanName(qs.Get("q"))
	esc := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q)
	where := `WHERE c.merged_into IS NULL AND ($1 = '' OR c.name ILIKE '%' || $1 || '%') AND ($2 OR c.hidden_at IS NULL)`
	if filter == "suspicious" {
		where += ` AND (((SELECT count(*) FROM class_members m WHERE m.class_id = c.id AND m.left_at IS NULL) <= 1
			AND NOT EXISTS (SELECT 1 FROM assignments a WHERE a.class_id = c.id AND a.deleted_at IS NULL AND a.merged_into IS NULL))
			OR c.name ~ $3)`
	} else {
		where += ` AND $3 <> ''`
	}
	rows, err := s.DB.Query(r.Context(), adminClassSQL+where+` ORDER BY c.created_at DESC, c.id DESC LIMIT 50 OFFSET $4`,
		esc, includeHidden, suspiciousNameRE, (page-1)*50)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	out, err := scanAdminClasses(rows)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

func loadAdminClass(ctx context.Context, q store.Q, id int64) (*adminClass, error) {
	rows, err := q.Query(ctx, adminClassSQL+`WHERE c.id = $1`, id)
	if err != nil {
		return nil, err
	}
	cs, err := scanAdminClasses(rows)
	if err != nil {
		return nil, err
	}
	if len(cs) == 0 {
		return nil, httpx.ErrNotFound
	}
	return &cs[0], nil
}

// adminClassID 路径里的班级 id，沿 merged_into 解析到最终班级。
func adminClassID(ctx context.Context, q store.Q, r *http.Request) (int64, error) {
	id, err := classIDParam(r)
	if err != nil {
		return 0, err
	}
	id, err = store.ResolveClass(ctx, q, id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, httpx.ErrNotFound
	}
	return id, err
}

// PATCH /admin/classes/{class_id} 改班名/教师。班级作业刷新 updated_at，/sync 会下发新班名。
func (s *Server) adminPatchClass(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var raw map[string]json.RawMessage
	if err := httpx.Decode(r, &raw); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var name *string
	var teacherSet bool
	var teacher *string
	var reasonIn string
	if v, ok := raw["name"]; ok {
		var n string
		if err := json.Unmarshal(v, &n); err != nil {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
		n = timetable.CleanName(n)
		if l := utf8.RuneCountInString(n); l < 1 || l > 60 {
			httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "班名须为 1–60 字"))
			return
		}
		name = &n
	}
	if v, ok := raw["teacher"]; ok {
		teacherSet = true
		if err := json.Unmarshal(v, &teacher); err != nil {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
		if teacher != nil {
			t := timetable.CleanName(*teacher)
			if utf8.RuneCountInString(t) > 60 {
				httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "教师名最多 60 字"))
				return
			}
			if t == "" {
				teacher = nil
			} else {
				teacher = &t
			}
		}
	}
	if v, ok := raw["reason"]; ok {
		if err := json.Unmarshal(v, &reasonIn); err != nil {
			httpx.Fail(w, r, httpx.ErrBadRequest)
			return
		}
	}
	reason, err := cleanReason(reasonIn, false)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	var id int64
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		if id, err = adminClassID(ctx, tx, r); err != nil {
			return err
		}
		var oldName string
		var oldTeacher *string
		if err := tx.QueryRow(ctx, `SELECT name, teacher FROM classes WHERE id = $1 FOR UPDATE`, id).Scan(&oldName, &oldTeacher); err != nil {
			return err
		}
		newName, newTeacher := oldName, oldTeacher
		if name != nil {
			newName = *name
		}
		if teacherSet {
			newTeacher = teacher
		}
		if newName == oldName && strPtrEq(newTeacher, oldTeacher) {
			return nil
		}
		if _, err := tx.Exec(ctx, `UPDATE classes SET name = $2, name_truncated = CASE WHEN $2 = name THEN name_truncated ELSE false END,
			teacher = $3 WHERE id = $1`, id, newName, newTeacher); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE assignments SET updated_at = now() WHERE class_id = $1`, id); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "class_update", "class", id, reason, map[string]any{
			"before": map[string]any{"name": oldName, "teacher": oldTeacher},
			"after":  map[string]any{"name": newName, "teacher": newTeacher},
		})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	c, err := loadAdminClass(ctx, s.DB, id)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, c)
}

func strPtrEq(a, b *string) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// POST /admin/classes/{class_id}/hide | unhide 隐藏（reason 必填）/ 恢复垃圾班级，幂等。
// 隐藏：成员收到 class_hidden 通知；班级作业刷新 updated_at，/sync 放进 removed_ids；数据全部保留。
// 恢复：作业刷新 updated_at，/sync 重新下发。状态没变时不写日志、不发通知。
func (s *Server) adminHideClass(hide bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		var reason *string
		if hide {
			var in struct {
				Reason string `json:"reason"`
			}
			if err := httpx.Decode(r, &in); err != nil {
				httpx.Fail(w, r, err)
				return
			}
			var err error
			if reason, err = cleanReason(in.Reason, true); err != nil {
				httpx.Fail(w, r, err)
				return
			}
		}
		admin := currentUser(r).ID
		now := s.Now()
		err := s.inTx(ctx, func(tx pgx.Tx) error {
			id, err := adminClassID(ctx, tx, r)
			if err != nil {
				return err
			}
			var name string
			var hiddenAt *time.Time
			if err := tx.QueryRow(ctx, `SELECT name, hidden_at FROM classes WHERE id = $1 FOR UPDATE`, id).Scan(&name, &hiddenAt); err != nil {
				return err
			}
			if (hiddenAt != nil) == hide {
				return nil
			}
			var newHidden *time.Time
			if hide {
				newHidden = &now
			}
			if _, err := tx.Exec(ctx, `UPDATE classes SET hidden_at = $2 WHERE id = $1`, id, newHidden); err != nil {
				return err
			}
			if _, err := tx.Exec(ctx, `UPDATE assignments SET updated_at = now() WHERE class_id = $1`, id); err != nil {
				return err
			}
			action := "class_unhide"
			if hide {
				action = "class_hide"
				payload, _ := json.Marshal(map[string]any{"class_id": id, "class_name": name})
				if _, err := tx.Exec(ctx, `INSERT INTO notices (user_id, kind, payload)
					SELECT user_id, 'class_hidden', $2 FROM class_members WHERE class_id = $1 AND left_at IS NULL`, id, payload); err != nil {
					return err
				}
			}
			return logAdmin(ctx, tx, admin, action, "class", id, reason, map[string]any{
				"before": map[string]any{"hidden": !hide}, "after": map[string]any{"hidden": hide}, "class_name": name,
			})
		})
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

var errMergeStale = httpx.E(http.StatusConflict, "merge_stale", "两个班须同学期、都未隐藏且未被合并")

// POST /admin/classes/merge 手动合并两个班：复用自动合并的 timetable.MergeInto 与 class_merges 快照，
// 可用 /admin/class-merges/{merge_id}/revert 撤销。
func (s *Server) adminMergeClasses(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var in struct {
		From   int64  `json:"from_class_id"`
		Into   int64  `json:"into_class_id"`
		Reason string `json:"reason"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if in.From <= 0 || in.Into <= 0 || in.From == in.Into {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "请选择两个不同的班"))
		return
	}
	reason, err := cleanReason(in.Reason, false)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	var mergeID int64
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, `SELECT id, semester_id, merged_into IS NOT NULL, hidden_at IS NOT NULL
			FROM classes WHERE id IN ($1, $2) ORDER BY id FOR UPDATE`, in.From, in.Into)
		if err != nil {
			return err
		}
		type st struct {
			sem            int32
			merged, hidden bool
		}
		got := map[int64]st{}
		for rows.Next() {
			var id int64
			var x st
			if err := rows.Scan(&id, &x.sem, &x.merged, &x.hidden); err != nil {
				rows.Close()
				return err
			}
			got[id] = x
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		f, ok1 := got[in.From]
		t, ok2 := got[in.Into]
		if !ok1 || !ok2 {
			return httpx.ErrNotFound
		}
		if f.sem != t.sem || f.merged || t.merged || f.hidden || t.hidden {
			return errMergeStale
		}
		if mergeID, err = timetable.MergeInto(ctx, tx, in.From, in.Into, &admin); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "class_merge", "class", in.Into, reason, map[string]any{
			"merge_id": mergeID, "from_class_id": in.From, "into_class_id": in.Into,
		})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusCreated, map[string]int64{"merge_id": mergeID})
}

// ---------- 删除来源与消息 ----------

// DELETE /admin/sources/{source_id} 管理员直接删除来源（deleted_reason=admin），幂等。
func (s *Server) adminDeleteSource(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "source_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var in struct {
		Reason   string `json:"reason"`
		Penalize *bool  `json:"penalize"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	reason, err := cleanReason(in.Reason, true)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	penalize := in.Penalize == nil || *in.Penalize
	admin := currentUser(r).ID
	now := s.Now()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		src, err := loadSrcRef(ctx, tx, id, true)
		if err != nil {
			return err
		}
		if src.Deleted {
			return nil
		}
		if err := s.deleteModerated(ctx, tx, src, now, "admin", penalize, reason); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "source_delete", "source", id, reason, map[string]any{
			"penalize": penalize, "assignment_id": src.AssignmentID, "class_id": src.ClassID, "author_id": src.AuthorID,
		})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// DELETE /admin/messages/{message_id} 管理员删除频道消息（幂等，reason 必填）。
func (s *Server) adminDeleteMessage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "message_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var in struct {
		Reason string `json:"reason"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	reason, err := cleanReason(in.Reason, true)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	admin := currentUser(r).ID
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var classID, author int64
		var deleted bool
		err := tx.QueryRow(ctx, `SELECT class_id, user_id, deleted_at IS NOT NULL FROM class_messages WHERE id = $1 FOR UPDATE`, id).
			Scan(&classID, &author, &deleted)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil || deleted {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE class_messages SET deleted_at = now(), deleted_by = 'admin' WHERE id = $1`, id); err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "message_delete", "message", id, reason, map[string]any{"class_id": classID, "author_id": author})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- 举报 ----------

// adminReport 与 api.yaml components.schemas.AdminReport 一致（有 JSON 快照测试）。
type adminReport struct {
	ID         int64            `json:"id"`
	TargetKind string           `json:"target_kind"`
	TargetID   int64            `json:"target_id"`
	Target     any              `json:"target"`
	Reporters  []reportReporter `json:"reporters"`
	HandledAt  *string          `json:"handled_at"`
	Resolution *string          `json:"resolution"`
}

type reportReporter struct {
	Nickname  string `json:"nickname"`
	Reason    string `json:"reason"`
	CreatedAt string `json:"created_at"`
}

// 举报对象只给处理所需的公开内容（api.yaml /admin/reports）。
type reportSourceTarget struct {
	Body           string   `json:"body"`
	ThumbURLs      []string `json:"thumb_urls"`
	AuthorNickname string   `json:"author_nickname"`
	ClassName      string   `json:"class_name"`
}

type reportContextMessage struct {
	ID             int64  `json:"id"`
	AuthorNickname string `json:"author_nickname"`
	Body           string `json:"body"`
	CreatedAt      string `json:"created_at"`
}

type reportMessageTarget struct {
	Body           string                 `json:"body"`
	AuthorNickname string                 `json:"author_nickname"`
	ClassName      string                 `json:"class_name"`
	CreatedAt      string                 `json:"created_at"`
	Context        []reportContextMessage `json:"context"` // 前后各 5 条（不含被举报的这条），按 id 升序
}

type reportClassTarget struct {
	Name              string `json:"name"`
	MemberCount       int    `json:"member_count"`
	AssignmentCount   int    `json:"assignment_count"`
	CreatedByNickname string `json:"created_by_nickname"`
}

// GET /admin/reports?status=open|handled 同一对象的举报合并为一行，按最近一条举报倒序，最多 200 行。
func (s *Server) adminReports(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	status := r.URL.Query().Get("status")
	if status == "" {
		status = "open"
	}
	if status != "open" && status != "handled" {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "status 须为 open 或 handled"))
		return
	}
	// 未处理：按对象分组；已处理：按对象 + 处理时间分组（同一次 resolve 关掉的为一行）
	cond, groupBy := `r.handled_at IS NULL`, `r.target_kind, r.target_id`
	if status == "handled" {
		cond, groupBy = `r.handled_at IS NOT NULL`, `r.target_kind, r.target_id, r.handled_at, r.resolution`
	}
	rows, err := s.DB.Query(ctx, `SELECT min(r.id), r.target_kind, r.target_id, max(r.handled_at), max(r.resolution),
			array_agg(r.id ORDER BY r.created_at, r.id)
		FROM reports r WHERE `+cond+` GROUP BY `+groupBy+` ORDER BY max(r.created_at) DESC, min(r.id) DESC LIMIT 200`)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var out []adminReport
	var allIDs []int64
	byReport := map[int64]int{}
	for rows.Next() {
		var x adminReport
		var handled *time.Time
		var ids []int64
		if err := rows.Scan(&x.ID, &x.TargetKind, &x.TargetID, &handled, &x.Resolution, &ids); err != nil {
			rows.Close()
			httpx.Fail(w, r, err)
			return
		}
		x.HandledAt = httpx.TimePtr(handled)
		x.Reporters = []reportReporter{}
		for _, id := range ids {
			byReport[id] = len(out)
		}
		allIDs = append(allIDs, ids...)
		out = append(out, x)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if out == nil {
		httpx.JSON(w, http.StatusOK, []adminReport{})
		return
	}
	rows, err = s.DB.Query(ctx, `SELECT r.id, u.nickname, r.reason, r.created_at FROM reports r JOIN users u ON u.id = r.reporter_id
		WHERE r.id = ANY($1) ORDER BY r.created_at, r.id`, allIDs)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	for rows.Next() {
		var id int64
		var rp reportReporter
		var at time.Time
		if err := rows.Scan(&id, &rp.Nickname, &rp.Reason, &at); err != nil {
			rows.Close()
			httpx.Fail(w, r, err)
			return
		}
		rp.CreatedAt = httpx.Time(at)
		i := byReport[id]
		out[i].Reporters = append(out[i].Reporters, rp)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := s.loadReportTargets(ctx, out); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}

// loadReportTargets 按对象种类批量装载 target（每种固定条数查询，无 N+1）。对象已被硬删时 target 为 null。
func (s *Server) loadReportTargets(ctx context.Context, out []adminReport) error {
	ids := map[string][]int64{}
	for _, x := range out {
		ids[x.TargetKind] = append(ids[x.TargetKind], x.TargetID)
	}
	targets := map[string]map[int64]any{"source": {}, "message": {}, "class": {}, "user": {}}
	now := s.Now()
	if l := ids["source"]; len(l) > 0 {
		rows, err := s.DB.Query(ctx, `SELECT s.id, s.body, u.nickname, c.name FROM assignment_sources s
			JOIN users u ON u.id = s.author_id JOIN assignments a ON a.id = s.assignment_id JOIN classes c ON c.id = a.class_id
			WHERE s.id = ANY($1)`, l)
		if err != nil {
			return err
		}
		srcs := map[int64]*reportSourceTarget{}
		for rows.Next() {
			var id int64
			t := &reportSourceTarget{ThumbURLs: []string{}}
			if err := rows.Scan(&id, &t.Body, &t.AuthorNickname, &t.ClassName); err != nil {
				rows.Close()
				return err
			}
			srcs[id] = t
			targets["source"][id] = t
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		rows, err = s.DB.Query(ctx, `SELECT source_id, thumb_path FROM attachments
			WHERE source_id = ANY($1) AND purged_at IS NULL AND thumb_path IS NOT NULL ORDER BY id`, l)
		if err != nil {
			return err
		}
		for rows.Next() {
			var sid int64
			var p string
			if err := rows.Scan(&sid, &p); err != nil {
				rows.Close()
				return err
			}
			if t := srcs[sid]; t != nil {
				t.ThumbURLs = append(t.ThumbURLs, s.Files.URL(p, now))
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
	}
	if l := ids["message"]; len(l) > 0 {
		rows, err := s.DB.Query(ctx, `SELECT m.id, m.body, u.nickname, c.name, m.created_at FROM class_messages m
			JOIN users u ON u.id = m.user_id JOIN classes c ON c.id = m.class_id WHERE m.id = ANY($1)`, l)
		if err != nil {
			return err
		}
		msgs := map[int64]*reportMessageTarget{}
		for rows.Next() {
			var id int64
			var at time.Time
			t := &reportMessageTarget{Context: []reportContextMessage{}}
			if err := rows.Scan(&id, &t.Body, &t.AuthorNickname, &t.ClassName, &at); err != nil {
				rows.Close()
				return err
			}
			t.CreatedAt = httpx.Time(at)
			msgs[id] = t
			targets["message"][id] = t
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		// 上下文：同班前后各 5 条未删除的消息（只有被举报消息的上下文，不能浏览频道其他部分）
		rows, err = s.DB.Query(ctx, `SELECT t.id, x.id, u.nickname, x.body, x.created_at
			FROM class_messages t
			CROSS JOIN LATERAL (
				(SELECT * FROM class_messages b WHERE b.class_id = t.class_id AND b.id < t.id AND b.deleted_at IS NULL ORDER BY b.id DESC LIMIT 5)
				UNION ALL
				(SELECT * FROM class_messages a WHERE a.class_id = t.class_id AND a.id > t.id AND a.deleted_at IS NULL ORDER BY a.id LIMIT 5)
			) x
			JOIN users u ON u.id = x.user_id
			WHERE t.id = ANY($1) ORDER BY t.id, x.id`, l)
		if err != nil {
			return err
		}
		for rows.Next() {
			var tid int64
			var c reportContextMessage
			var at time.Time
			if err := rows.Scan(&tid, &c.ID, &c.AuthorNickname, &c.Body, &at); err != nil {
				rows.Close()
				return err
			}
			c.CreatedAt = httpx.Time(at)
			if t := msgs[tid]; t != nil {
				t.Context = append(t.Context, c)
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
	}
	if l := ids["class"]; len(l) > 0 {
		rows, err := s.DB.Query(ctx, adminClassSQL+`WHERE c.id = ANY($1)`, l)
		if err != nil {
			return err
		}
		cs, err := scanAdminClasses(rows)
		if err != nil {
			return err
		}
		for _, c := range cs {
			targets["class"][c.ID] = reportClassTarget{Name: c.Name, MemberCount: c.MemberCount,
				AssignmentCount: c.AssignmentCount, CreatedByNickname: c.CreatedByNickname}
		}
	}
	if l := ids["user"]; len(l) > 0 {
		us, err := loadAdminUsers(ctx, s.DB, l, now)
		if err != nil {
			return err
		}
		for _, u := range us {
			targets["user"][u.ID] = u
		}
	}
	for i := range out {
		if t, ok := targets[out[i].TargetKind][out[i].TargetID]; ok {
			out[i].Target = t
		}
	}
	return nil
}

// POST /admin/reports/{report_id}/resolve 同一对象的所有未处理举报一起关掉。
func (s *Server) adminResolveReport(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id, err := idParam(r, "report_id")
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var in struct {
		Resolution string `json:"resolution"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if in.Resolution != "removed" && in.Resolution != "dismissed" {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "resolution 须为 removed 或 dismissed"))
		return
	}
	admin := currentUser(r).ID
	now := s.Now()
	err = s.inTx(ctx, func(tx pgx.Tx) error {
		var kind string
		var target int64
		var handled *time.Time
		err := tx.QueryRow(ctx, `SELECT target_kind, target_id, handled_at FROM reports WHERE id = $1 FOR UPDATE`, id).Scan(&kind, &target, &handled)
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.ErrNotFound
		}
		if err != nil {
			return err
		}
		if handled != nil {
			return errReportClosed
		}
		rows, err := tx.Query(ctx, `UPDATE reports SET handled_at = $3, handled_by = $4, resolution = $5
			WHERE target_kind = $1 AND target_id = $2 AND handled_at IS NULL RETURNING id`, kind, target, now, admin, in.Resolution)
		if err != nil {
			return err
		}
		closed, err := pgx.CollectRows(rows, pgx.RowTo[int64])
		if err != nil {
			return err
		}
		return logAdmin(ctx, tx, admin, "report_resolve", kind, target, nil, map[string]any{
			"report_ids": closed, "resolution": in.Resolution,
		})
	})
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- 操作日志 ----------

// GET /admin/actions 最近 200 条，倒序。
func (s *Server) adminActions(w http.ResponseWriter, r *http.Request) {
	rows, err := s.DB.Query(r.Context(), `SELECT id, action, target_kind, target_id, reason, payload, created_at
		FROM admin_actions ORDER BY created_at DESC, id DESC LIMIT 200`)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	defer rows.Close()
	type item struct {
		ID         int64           `json:"id"`
		Action     string          `json:"action"`
		TargetKind string          `json:"target_kind"`
		TargetID   int64           `json:"target_id"`
		Reason     *string         `json:"reason"`
		Payload    json.RawMessage `json:"payload"`
		CreatedAt  string          `json:"created_at"`
	}
	out := []item{}
	for rows.Next() {
		var x item
		var at time.Time
		if err := rows.Scan(&x.ID, &x.Action, &x.TargetKind, &x.TargetID, &x.Reason, &x.Payload, &at); err != nil {
			httpx.Fail(w, r, err)
			return
		}
		x.CreatedAt = httpx.Time(at)
		out = append(out, x)
	}
	if err := rows.Err(); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, out)
}
