package ai

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/files"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

var (
	errAiOffline     = httpx.E(http.StatusConflict, "ai_offline", "电脑未在线，请手动录入")
	errBadInput      = func(msg string) *httpx.Error { return httpx.E(http.StatusBadRequest, "bad_input", msg) }
	errBadAttachment = httpx.E(http.StatusBadRequest, "bad_attachment", "图片不存在、不是本人上传或已清理")
	errNotMember     = httpx.E(http.StatusForbidden, "not_member", "你不是这个班的成员")
	errAiBusy        = httpx.E(http.StatusTooManyRequests, "ai_busy", "上一个识别还没完成，请稍候")
	errAiRateLimited = httpx.E(http.StatusTooManyRequests, "ai_rate_limited", "识别太频繁了，请稍后再试")
)

// Context 是服务器附给每个任务的上下文（ai-contract.md「通用约定」）。
type Context struct {
	Today    string        `json:"today"`
	Weekday  int           `json:"weekday"`
	Timezone string        `json:"timezone"`
	Periods  []Period      `json:"periods"`
	Class    *ContextClass `json:"class,omitempty"`
}

type Period struct {
	No    int    `json:"no"`
	Start string `json:"start"`
	End   string `json:"end"`
}

type ContextClass struct {
	Name  string       `json:"name"`
	Slots []store.Slot `json:"slots"`
}

func (s *Service) buildContext(ctx context.Context, classID *int64) (*Context, error) {
	now := s.Now().In(httpx.Shanghai)
	wd := int(now.Weekday())
	if wd == 0 {
		wd = 7
	}
	c := &Context{Today: now.Format(time.DateOnly), Weekday: wd, Timezone: "Asia/Shanghai", Periods: []Period{}}
	rows, err := s.DB.Query(ctx, `SELECT no, to_char(starts_at, 'HH24:MI'), to_char(ends_at, 'HH24:MI') FROM periods ORDER BY no`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var p Period
		if err := rows.Scan(&p.No, &p.Start, &p.End); err != nil {
			rows.Close()
			return nil, err
		}
		c.Periods = append(c.Periods, p)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if classID != nil {
		cls, err := store.LoadClasses(ctx, s.DB, []int64{*classID})
		if err != nil {
			return nil, err
		}
		if len(cls) == 1 {
			c.Class = &ContextClass{Name: cls[0].Name, Slots: cls[0].Slots}
		}
	}
	return c, nil
}

// signImages 校验附件属于本人、是图片且未清理，按请求顺序签名成 /files 链接。
func (s *Service) signImages(ctx context.Context, userID int64, ids []int64) ([]string, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	seen := map[int64]bool{}
	for _, id := range ids {
		if id <= 0 || seen[id] {
			return nil, errBadAttachment
		}
		seen[id] = true
	}
	rows, err := s.DB.Query(ctx, `SELECT id, path FROM attachments
		WHERE id = ANY($1) AND uploader_id = $2 AND kind = 'image' AND purged_at IS NULL`, ids, userID)
	if err != nil {
		return nil, err
	}
	paths := map[int64]string{}
	for rows.Next() {
		var id int64
		var p string
		if err := rows.Scan(&id, &p); err != nil {
			rows.Close()
			return nil, err
		}
		paths[id] = p
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	now := s.Now()
	urls := make([]string, 0, len(ids))
	for _, id := range ids {
		p, ok := paths[id]
		if !ok {
			return nil, errBadAttachment
		}
		if !files.ValidRel(p) {
			return nil, errBadAttachment
		}
		urls = append(urls, s.Files.URL(p, now))
	}
	return urls, nil
}

// CreateRequest 是 POST /ai/jobs 的请求体。
type CreateRequest struct {
	Kind    string          `json:"kind"`
	ClassID *int64          `json:"class_id"`
	Input   json.RawMessage `json:"input"`
}

type parseAssignmentIn struct {
	Text          *string `json:"text"`
	AttachmentIDs []int64 `json:"attachment_ids"`
}

type parseTimetableIn struct {
	AttachmentIDs []int64 `json:"attachment_ids"`
}

type dedupeIn struct {
	Draft struct {
		Title string  `json:"title"`
		Body  string  `json:"body"`
		DDL   *string `json:"ddl"`
	} `json:"draft"`
	CandidateIDs []int64 `json:"candidate_ids"`
}

type spamIn struct {
	Title         string  `json:"title"`
	Body          string  `json:"body"`
	AttachmentIDs []int64 `json:"attachment_ids"`
}

// decodeStrict 拒绝未知字段（例如客户端自带的 image_urls / context）。
func decodeStrict(raw json.RawMessage, v any) error {
	if len(raw) == 0 {
		return errBadInput("缺少 input")
	}
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return errBadInput("input 格式不正确：" + err.Error())
	}
	return nil
}

func tooLong(s string, n int) bool { return utf8.RuneCountInString(s) > n }

var ddlRe = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?\+08:00$`)

// checkLimits 每人同时进行中 ≤ MaxOpenPerUser，每小时 ≤ MaxPerUserHour。须在持有该用户 advisory 锁的事务里调用。
func (s *Service) checkLimits(ctx context.Context, q store.Q, userID int64) error {
	now := s.Now()
	var open, hour int
	err := q.QueryRow(ctx, `SELECT
			count(*) FILTER (WHERE status IN ('queued', 'running') AND expires_at > $2),
			count(*) FILTER (WHERE created_at > $3)
		FROM ai_jobs WHERE requested_by = $1 AND created_at > $3`, userID, now, now.Add(-time.Hour)).Scan(&open, &hour)
	if err != nil {
		return err
	}
	if open >= MaxOpenPerUser {
		return errAiBusy
	}
	if hour >= MaxPerUserHour {
		return errAiRateLimited
	}
	return nil
}

// memberClass 解析合并后的班级并校验在班成员。
func (s *Service) memberClass(ctx context.Context, classID *int64, userID int64, required bool) (*int64, error) {
	if classID == nil {
		if required {
			return nil, errBadInput("这个任务需要 class_id")
		}
		return nil, nil
	}
	id, err := store.ResolveClass(ctx, s.DB, *classID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, httpx.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	ok, err := store.IsMember(ctx, s.DB, id, userID)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, errNotMember
	}
	return &id, nil
}

// Create 处理 App 发起的任务：校验、拼装 worker 输入、在线才入库。
func (s *Service) Create(ctx context.Context, userID int64, req CreateRequest) (*Job, error) {
	// 先判在线：离线时 App 应已置灰，这里直接 409，不做其他校验也不排队
	online, _, err := s.Status(ctx)
	if err != nil {
		return nil, err
	}
	if !online {
		return nil, errAiOffline
	}
	j := newJob{kind: req.Kind, requestedBy: &userID}
	var input map[string]any
	switch req.Kind {
	case KindParseAssignment:
		var in parseAssignmentIn
		if err := decodeStrict(req.Input, &in); err != nil {
			return nil, err
		}
		text := ""
		if in.Text != nil {
			text = strings.TrimSpace(*in.Text)
		}
		if text == "" && len(in.AttachmentIDs) == 0 {
			return nil, errBadInput("文字和图片至少要有一项")
		}
		if tooLong(text, 5000) || len(in.AttachmentIDs) > 6 {
			return nil, errBadInput("文字最多 5000 字，图片最多 6 张")
		}
		if j.classID, err = s.memberClass(ctx, req.ClassID, userID, true); err != nil {
			return nil, err
		}
		urls, err := s.signImages(ctx, userID, in.AttachmentIDs)
		if err != nil {
			return nil, err
		}
		input = map[string]any{}
		if text != "" {
			input["text"] = text
		}
		if len(urls) > 0 {
			input["image_urls"] = urls
		}
	case KindParseTimetable:
		var in parseTimetableIn
		if err := decodeStrict(req.Input, &in); err != nil {
			return nil, err
		}
		if len(in.AttachmentIDs) < 1 || len(in.AttachmentIDs) > 2 {
			return nil, errBadInput("课表截图 1–2 张")
		}
		urls, err := s.signImages(ctx, userID, in.AttachmentIDs)
		if err != nil {
			return nil, err
		}
		input = map[string]any{"image_urls": urls}
	case KindDedupeReview:
		var in dedupeIn
		if err := decodeStrict(req.Input, &in); err != nil {
			return nil, err
		}
		in.Draft.Title = strings.TrimSpace(in.Draft.Title)
		if in.Draft.Title == "" || tooLong(in.Draft.Title, 80) || tooLong(in.Draft.Body, 5000) {
			return nil, errBadInput("草稿标题 1–80 字，正文最多 5000 字")
		}
		if in.Draft.DDL != nil && !ddlRe.MatchString(*in.Draft.DDL) {
			return nil, errBadInput("ddl 须为带 +08:00 的 ISO 8601")
		}
		if len(in.CandidateIDs) < 1 || len(in.CandidateIDs) > 3 {
			return nil, errBadInput("候选 1–3 条")
		}
		if j.classID, err = s.memberClass(ctx, req.ClassID, userID, true); err != nil {
			return nil, err
		}
		cands, err := s.loadCandidates(ctx, *j.classID, in.CandidateIDs)
		if err != nil {
			return nil, err
		}
		input = map[string]any{"draft": in.Draft, "candidates": cands}
	case KindSpamCheck:
		var in spamIn
		if err := decodeStrict(req.Input, &in); err != nil {
			return nil, err
		}
		if tooLong(in.Title, 80) || tooLong(in.Body, 5000) || len(in.AttachmentIDs) > 6 {
			return nil, errBadInput("标题最多 80 字，正文最多 5000 字，图片最多 6 张")
		}
		if strings.TrimSpace(in.Title) == "" && strings.TrimSpace(in.Body) == "" && len(in.AttachmentIDs) == 0 {
			return nil, errBadInput("没有可检查的内容")
		}
		if j.classID, err = s.memberClass(ctx, req.ClassID, userID, false); err != nil {
			return nil, err
		}
		urls, err := s.signImages(ctx, userID, in.AttachmentIDs)
		if err != nil {
			return nil, err
		}
		input = map[string]any{"title": in.Title, "body": in.Body}
		if len(urls) > 0 {
			input["image_urls"] = urls
		}
	default:
		// ddl_conflict / reminder_text 只由服务器发起
		return nil, errBadInput("不支持的任务种类")
	}
	c, err := s.buildContext(ctx, j.classID)
	if err != nil {
		return nil, err
	}
	input["context"] = c
	j.input = input
	// 计数与入库放进同一事务，并按用户加 advisory 锁：同一人并发发起不会超过限额
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // 提交后回滚无副作用
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1, $2)`, advisoryNS, int32(userID)); err != nil {
		return nil, err
	}
	if err := s.checkLimits(ctx, tx, userID); err != nil {
		return nil, err
	}
	job, err := s.insertWith(ctx, tx, j)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	s.signal()
	return job, nil
}

// advisoryNS pg_advisory_xact_lock 两参数形式的命名空间，避免与其他工作线的锁撞号。
const advisoryNS int32 = 0x5753_3301

type candidate struct {
	ID    int64   `json:"id"`
	Title string  `json:"title"`
	Body  string  `json:"body"`
	DDL   *string `json:"ddl"`
}

// loadCandidates 只取该班未删除、未合并的作业；正文取首发来源（最多 1000 字）。
func (s *Service) loadCandidates(ctx context.Context, classID int64, ids []int64) ([]candidate, error) {
	rows, err := s.DB.Query(ctx, `SELECT a.id, a.title, a.ddl,
			coalesce((SELECT left(src.body, 1000) FROM assignment_sources src
			          WHERE src.assignment_id = a.id AND src.deleted_at IS NULL
			          ORDER BY (src.kind = 'first') DESC, src.created_at, src.id LIMIT 1), '')
		FROM assignments a
		WHERE a.id = ANY($1) AND a.class_id = $2 AND a.deleted_at IS NULL AND a.merged_into IS NULL`, ids, classID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	byID := map[int64]candidate{}
	for rows.Next() {
		var c candidate
		var ddl time.Time
		if err := rows.Scan(&c.ID, &c.Title, &ddl, &c.Body); err != nil {
			return nil, err
		}
		d := httpx.Time(ddl)
		c.DDL = &d
		byID[c.ID] = c
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := []candidate{}
	for _, id := range ids {
		if c, ok := byID[id]; ok {
			out = append(out, c)
			delete(byID, id)
		}
	}
	if len(out) == 0 {
		return nil, errBadInput("候选作业不存在或不在这个班")
	}
	return out, nil
}

// ---------- 服务器自动发起（供 WS2 在作业创建 / DDL 变更 / 新增补充后调用，事务提交之后） ----------

// RequestReminderTexts 为作业预生成三条提醒文案（2d / 1d / 12h）。worker 离线时什么也不做、返回 nil：
// 提醒照常，App 用内置模板。结果写回 assignments.reminder_text，DDL 再变则作废。
func (s *Service) RequestReminderTexts(ctx context.Context, assignmentID int64) error {
	var title, className string
	var ddl time.Time
	err := s.DB.QueryRow(ctx, `SELECT a.title, a.ddl, c.name FROM assignments a JOIN classes c ON c.id = a.class_id
		WHERE a.id = $1 AND a.deleted_at IS NULL AND a.merged_into IS NULL`, assignmentID).Scan(&title, &ddl, &className)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if !ddl.After(s.Now()) {
		return nil
	}
	for _, slot := range ReminderSlots {
		_, err := s.insert(ctx, newJob{kind: KindReminderText, assignmentID: &assignmentID, input: map[string]any{
			"assignment": map[string]any{"title": title, "ddl": httpx.Time(ddl), "class_name": className},
			"slot":       slot,
		}})
		if errors.Is(err, ErrOffline) {
			return nil
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// RequestDDLConflict 让 AI 看各来源正文里写的 DDL 是否矛盾（补充里常只在文字里写日期）。
// 只在 conflict=true 时把作业标红，不改 DDL。离线时返回 nil。
func (s *Service) RequestDDLConflict(ctx context.Context, assignmentID int64) error {
	var title string
	var classID int64
	err := s.DB.QueryRow(ctx, `SELECT title, class_id FROM assignments
		WHERE id = $1 AND deleted_at IS NULL AND merged_into IS NULL`, assignmentID).Scan(&title, &classID)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	rows, err := s.DB.Query(ctx, `SELECT id, left(body, 1000), stated_ddl FROM assignment_sources
		WHERE assignment_id = $1 AND deleted_at IS NULL ORDER BY created_at, id LIMIT 10`, assignmentID)
	if err != nil {
		return err
	}
	type src struct {
		ID        int64   `json:"id"`
		Body      string  `json:"body"`
		StatedDDL *string `json:"stated_ddl"`
	}
	var srcs []src
	for rows.Next() {
		var x src
		var st *time.Time
		if err := rows.Scan(&x.ID, &x.Body, &st); err != nil {
			rows.Close()
			return err
		}
		x.StatedDDL = httpx.TimePtr(st)
		srcs = append(srcs, x)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	if len(srcs) < 2 {
		return nil // 只有一条来源不存在冲突
	}
	online, _, err := s.Status(ctx)
	if err != nil || !online {
		return err
	}
	c, err := s.buildContext(ctx, &classID)
	if err != nil {
		return err
	}
	_, err = s.insert(ctx, newJob{kind: KindDDLConflict, classID: &classID, assignmentID: &assignmentID, input: map[string]any{
		"context":    c,
		"assignment": map[string]any{"title": title},
		"sources":    srcs,
	}})
	if errors.Is(err, ErrOffline) {
		return nil
	}
	return err
}

type reminderOut struct {
	Title string `json:"title"`
	Body  string `json:"body"`
}

type conflictOut struct {
	Conflict bool `json:"conflict"`
}

// applyWriteback 只做两件事：写提醒文案；conflict=true 时标红。不改 DDL、不删不藏内容。
func applyWriteback(ctx context.Context, tx pgx.Tx, kind string, assignmentID int64, input []byte, output json.RawMessage) error {
	switch kind {
	case KindReminderText:
		var in struct {
			Assignment struct {
				DDL string `json:"ddl"`
			} `json:"assignment"`
			Slot string `json:"slot"`
		}
		if err := json.Unmarshal(input, &in); err != nil {
			return fmt.Errorf("reminder input: %w", err)
		}
		var out reminderOut
		dec := json.NewDecoder(strings.NewReader(string(output)))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&out); err != nil {
			return errBadOutput
		}
		out.Title, out.Body = strings.TrimSpace(out.Title), strings.TrimSpace(out.Body)
		if out.Title == "" || tooLong(out.Title, 40) || tooLong(out.Body, 40) {
			return errBadOutput
		}
		ddl, err := time.Parse(time.RFC3339, in.Assignment.DDL)
		if err != nil {
			return fmt.Errorf("reminder input ddl: %w", err)
		}
		val, _ := json.Marshal(map[string]reminderOut{in.Slot: out})
		// DDL 已变（与生成时不同）则不写：WS2 改 DDL 时已清空 reminder_text 并重新发起。刷新 updated_at 供 /sync 下发
		_, err = tx.Exec(ctx, `UPDATE assignments SET
				reminder_text = coalesce(reminder_text, '{}'::jsonb) || $3::jsonb, updated_at = now()
			WHERE id = $1 AND ddl = $2 AND deleted_at IS NULL`, assignmentID, ddl, val)
		return err
	case KindDDLConflict:
		var out conflictOut
		if err := json.Unmarshal(output, &out); err != nil {
			return errBadOutput
		}
		if out.Conflict {
			_, err := tx.Exec(ctx, `UPDATE assignments SET ddl_conflict = true, updated_at = now()
				WHERE id = $1 AND deleted_at IS NULL AND NOT ddl_conflict`, assignmentID)
			return err
		}
	}
	return nil
}
