// Package ai 服务器侧 AI 任务：/ai/*（App）与 /worker/*（电脑端 worker）。契约见 docs/ai-contract.md。
//
// 要点：
//   - worker 心跳 60 秒内算在线；离线时 POST /ai/jobs 直接 409 ai_offline，不排队。
//   - 任务 2 分钟内未完成即 expired；结束 24 小时后连同输入一起删除（输入含作业正文）。
//   - 任务输入由服务器拼装：context（today、periods、班级时段）由服务器给，图片只接受本人上传的
//     attachment_ids，服务器签名成 /files 链接交给 worker（issue #4 裁决 6），不接受客户端传 URL。
//   - AI 只出草稿：输出原样回给发起人。服务器只在两处使用输出：reminder_text 写回作业文案、
//     ddl_conflict 为 true 时把 assignments.ddl_conflict 标红；不改 DDL、不删不藏任何内容。
package ai

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/config"
	"github.com/IsANineBug/swufe-homework/server/internal/files"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

const (
	// OnlineWindow 心跳超过这个时间视为离线。
	OnlineWindow = 60 * time.Second
	// JobTTL 任务从创建起的有效期。
	JobTTL = 2 * time.Minute
	// ClaimWait 长轮询最长挂起时间（nginx 读超时 75 秒）。
	ClaimWait = 25 * time.Second
	// Retention 结束的任务保留多久后删除。
	Retention = 24 * time.Hour
	// MaxOpenPerUser 每人同时进行中的任务数。
	MaxOpenPerUser = 3
	// MaxPerUserHour 每人每小时发起次数。
	MaxPerUserHour = 60
	// MaxOutputBytes worker 回传输出的上限。
	MaxOutputBytes = 64 << 10
)

// 任务种类。
const (
	KindParseAssignment = "parse_assignment"
	KindParseTimetable  = "parse_timetable"
	KindDedupeReview    = "dedupe_review"
	KindDDLConflict     = "ddl_conflict"
	KindSpamCheck       = "spam_check"
	KindReminderText    = "reminder_text"
)

// ReminderSlots reminder_text 的三个时点，与提醒一一对应。
var ReminderSlots = []string{"2d", "1d", "12h"}

var ErrOffline = errors.New("ai offline")

type Service struct {
	DB          *pgxpool.Pool
	WorkerToken string
	// Files 与 /files 处理器共用同一个签名器（WS2），交给 worker 的图片链接才能验签通过
	Files *files.Signer
	Now   func() time.Time
	// PollEvery 长轮询时兜底查库的间隔（新任务通常由 wake 立即唤醒）。
	PollEvery time.Duration
	// ClaimWait 长轮询最长挂起时间，默认 ClaimWait 常量（测试里调短）。
	ClaimWait time.Duration

	mu   sync.Mutex
	wake chan struct{}
}

func New(cfg *config.Config, db *pgxpool.Pool, signer *files.Signer) *Service {
	return &Service{
		DB:          db,
		WorkerToken: cfg.WorkerToken,
		Files:       signer,
		Now:         time.Now,
		PollEvery:   3 * time.Second,
		ClaimWait:   ClaimWait,
		wake:        make(chan struct{}),
	}
}

// signal 唤醒所有挂起中的长轮询。
func (s *Service) signal() {
	s.mu.Lock()
	close(s.wake)
	s.wake = make(chan struct{})
	s.mu.Unlock()
}

func (s *Service) waitCh() <-chan struct{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.wake
}

// Job 对外的任务表示（api.yaml AiJob）。Input 只在 worker 领取时返回。
type Job struct {
	ID        int64           `json:"id"`
	Kind      string          `json:"kind"`
	Status    string          `json:"status"`
	Input     json.RawMessage `json:"input,omitempty"`
	Output    json.RawMessage `json:"output"`
	Error     *string         `json:"error"`
	CreatedAt string          `json:"created_at"`
	ExpiresAt string          `json:"expires_at"`
}

// Status 返回是否在线与最近心跳时间。
func (s *Service) Status(ctx context.Context) (bool, *time.Time, error) {
	var last *time.Time
	if err := s.DB.QueryRow(ctx, `SELECT max(last_seen) FROM ai_workers`).Scan(&last); err != nil {
		return false, nil, err
	}
	return last != nil && s.Now().Sub(*last) < OnlineWindow, last, nil
}

// Heartbeat 记录 worker 心跳。
func (s *Service) Heartbeat(ctx context.Context, workerID, model string) error {
	_, err := s.DB.Exec(ctx, `INSERT INTO ai_workers (id, last_seen, model) VALUES ($1, $2, $3)
		ON CONFLICT (id) DO UPDATE SET last_seen = EXCLUDED.last_seen, model = EXCLUDED.model`,
		workerID, s.Now(), model)
	return err
}

// expireStale 把超时未完成的任务标 expired。
func (s *Service) expireStale(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `UPDATE ai_jobs SET status = 'expired', finished_at = $1
		WHERE status IN ('queued', 'running') AND expires_at <= $1`, s.Now())
	return err
}

// purgeOld 删除结束超过 Retention 的任务（输入含作业正文，不长期保存）。
func (s *Service) purgeOld(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM ai_jobs WHERE status IN ('done', 'failed', 'expired')
		AND coalesce(finished_at, expires_at) < $1`, s.Now().Add(-Retention))
	return err
}

// Run 定时过期与清理，随进程退出。
func (s *Service) Run(ctx context.Context) {
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	n := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if err := s.expireStale(ctx); err != nil && ctx.Err() == nil {
				slog.Error("ai expire", "err", err)
			}
			if n++; n%240 == 0 { // 约每小时
				if err := s.purgeOld(ctx); err != nil && ctx.Err() == nil {
					slog.Error("ai purge", "err", err)
				}
			}
		}
	}
}

type newJob struct {
	kind         string
	requestedBy  *int64
	classID      *int64
	assignmentID *int64
	input        any
}

// insert 在线才入库；离线返回 ErrOffline（不排队）。
func (s *Service) insert(ctx context.Context, j newJob) (*Job, error) {
	online, _, err := s.Status(ctx)
	if err != nil {
		return nil, err
	}
	if !online {
		return nil, ErrOffline
	}
	job, err := s.insertWith(ctx, s.DB, j)
	if err != nil {
		return nil, err
	}
	s.signal()
	return job, nil
}

// insertWith 只做 INSERT（调用方负责判在线与唤醒长轮询）。
func (s *Service) insertWith(ctx context.Context, q store.Q, j newJob) (*Job, error) {
	raw, err := json.Marshal(j.input)
	if err != nil {
		return nil, err
	}
	now := s.Now()
	var out Job
	var created, expires time.Time
	err = q.QueryRow(ctx, `INSERT INTO ai_jobs (kind, requested_by, class_id, assignment_id, input, created_at, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id, kind, status, created_at, expires_at`,
		j.kind, j.requestedBy, j.classID, j.assignmentID, raw, now, now.Add(JobTTL)).
		Scan(&out.ID, &out.Kind, &out.Status, &created, &expires)
	if err != nil {
		return nil, err
	}
	out.CreatedAt, out.ExpiresAt = httpx.Time(created), httpx.Time(expires)
	out.Output = json.RawMessage("null")
	return &out, nil
}

// Get 返回任务（不含输入）。requester 非 nil 时只返回本人发起的任务。
func (s *Service) Get(ctx context.Context, id int64, requester int64) (*Job, error) {
	if err := s.expireStale(ctx); err != nil {
		return nil, err
	}
	var j Job
	var out []byte
	var created, expires time.Time
	err := s.DB.QueryRow(ctx, `SELECT id, kind, status, output, error, created_at, expires_at
		FROM ai_jobs WHERE id = $1 AND requested_by = $2`, id, requester).
		Scan(&j.ID, &j.Kind, &j.Status, &out, &j.Error, &created, &expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, httpx.ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	j.Output = nullable(out)
	j.CreatedAt, j.ExpiresAt = httpx.Time(created), httpx.Time(expires)
	return &j, nil
}

func nullable(b []byte) json.RawMessage {
	if len(b) == 0 {
		return json.RawMessage("null")
	}
	return json.RawMessage(b)
}

// tryClaim 取最早的排队任务并标 running。
func (s *Service) tryClaim(ctx context.Context, workerID string) (*Job, error) {
	now := s.Now()
	var j Job
	var in []byte
	var created, expires time.Time
	err := s.DB.QueryRow(ctx, `UPDATE ai_jobs SET status = 'running', claimed_at = $1, worker_id = $2
		WHERE id = (SELECT id FROM ai_jobs WHERE status = 'queued' AND expires_at > $1
		            ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
		RETURNING id, kind, status, input, created_at, expires_at`, now, workerID).
		Scan(&j.ID, &j.Kind, &j.Status, &in, &created, &expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	j.Input = json.RawMessage(in)
	j.Output = json.RawMessage("null")
	j.CreatedAt, j.ExpiresAt = httpx.Time(created), httpx.Time(expires)
	return &j, nil
}

// Claim 长轮询：有任务立即返回，否则最多挂起 wait；返回 nil 表示暂无任务。
func (s *Service) Claim(ctx context.Context, workerID string, wait time.Duration) (*Job, error) {
	if err := s.expireStale(ctx); err != nil {
		return nil, err
	}
	deadline := time.NewTimer(wait)
	defer deadline.Stop()
	for {
		ch := s.waitCh() // 先取通道再查库，避免查完到等待之间漏掉唤醒
		j, err := s.tryClaim(ctx, workerID)
		if err != nil || j != nil {
			return j, err
		}
		poll := time.NewTimer(s.PollEvery)
		select {
		case <-ctx.Done():
			poll.Stop()
			return nil, nil
		case <-deadline.C:
			poll.Stop()
			return nil, nil
		case <-ch:
		case <-poll.C:
		}
		poll.Stop()
	}
}

var (
	errJobNotRunning = httpx.E(409, "job_not_running", "任务已过期或已结束")
	errBadOutput     = httpx.E(400, "bad_output", "输出格式不正确")
)

// Finish 记录 worker 回传的结果；reminder_text / ddl_conflict 在同一事务里写回作业。
func (s *Service) Finish(ctx context.Context, id int64, status string, output json.RawMessage, errMsg *string) error {
	if status != "done" && status != "failed" {
		return httpx.ErrBadRequest
	}
	var outArg any // nil → SQL NULL
	if status == "done" {
		if len(output) == 0 || len(output) > MaxOutputBytes || !isObject(output) {
			return errBadOutput
		}
		outArg = []byte(output)
		errMsg = nil
	} else {
		if errMsg == nil {
			e := "failed"
			errMsg = &e
		}
		e := truncateRunes(*errMsg, 300)
		errMsg = &e
	}
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // 提交后回滚无副作用
	now := s.Now()
	var kind string
	var assignmentID *int64
	var input []byte
	err = tx.QueryRow(ctx, `UPDATE ai_jobs SET status = $2, output = $3, error = $4, finished_at = $5
		WHERE id = $1 AND status = 'running' AND expires_at > $5
		RETURNING kind, assignment_id, input`, id, status, outArg, errMsg, now).
		Scan(&kind, &assignmentID, &input)
	if errors.Is(err, pgx.ErrNoRows) {
		return errJobNotRunning
	}
	if err != nil {
		return err
	}
	if status == "done" && assignmentID != nil {
		if err := applyWriteback(ctx, tx, kind, *assignmentID, input, output); err != nil {
			if !errors.Is(err, errBadOutput) {
				return err
			}
			// 输出不合格：回滚写回，任务记为 failed，不再等到过期
			_ = tx.Rollback(ctx)
			if _, e := s.DB.Exec(ctx, `UPDATE ai_jobs SET status = 'failed', error = 'bad_output', finished_at = $2
				WHERE id = $1 AND status = 'running'`, id, now); e != nil {
				return e
			}
			return err
		}
	}
	return tx.Commit(ctx)
}

func isObject(b json.RawMessage) bool {
	var m map[string]json.RawMessage
	return json.Unmarshal(b, &m) == nil && m != nil
}

func truncateRunes(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}
