package ai

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"strconv"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"

	"github.com/IsANineBug/swufe-homework/server/internal/auth"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
)

// HandleStatus GET /ai/status
func (s *Service) HandleStatus(w http.ResponseWriter, r *http.Request) {
	online, last, err := s.Status(r.Context())
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"online": online, "last_seen": httpx.TimePtr(last)})
}

// HandleCreate POST /ai/jobs
func (s *Service) HandleCreate(w http.ResponseWriter, r *http.Request) {
	u, ok := auth.FromContext(r.Context())
	if !ok {
		httpx.Fail(w, r, httpx.ErrUnauthorized)
		return
	}
	var req CreateRequest
	if err := httpx.Decode(r, &req); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	job, err := s.Create(r.Context(), u.ID, req)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusAccepted, job)
}

// HandleGet GET /ai/jobs/{job_id}：只能看自己发起的任务。
func (s *Service) HandleGet(w http.ResponseWriter, r *http.Request) {
	u, ok := auth.FromContext(r.Context())
	if !ok {
		httpx.Fail(w, r, httpx.ErrUnauthorized)
		return
	}
	id, err := strconv.ParseInt(chi.URLParam(r, "job_id"), 10, 64)
	if err != nil || id <= 0 {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	job, err := s.Get(r.Context(), id, u.ID)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	httpx.JSON(w, http.StatusOK, job)
}

// RequireWorker 校验 WORKER_TOKEN（常数时间比较）。未配置 WORKER_TOKEN 时一律 401。
func (s *Service) RequireWorker(next http.Handler) http.Handler {
	want := sha256.Sum256([]byte(s.WorkerToken))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := sha256.Sum256([]byte(auth.BearerToken(r)))
		if s.WorkerToken == "" || subtle.ConstantTimeCompare(want[:], got[:]) != 1 {
			httpx.Fail(w, r, httpx.ErrUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func workerID(r *http.Request) string {
	id := r.Header.Get("X-Worker-Id")
	if id == "" || utf8.RuneCountInString(id) > 64 {
		return "worker"
	}
	return id
}

// HandleHeartbeat POST /worker/heartbeat
func (s *Service) HandleHeartbeat(w http.ResponseWriter, r *http.Request) {
	var in struct {
		WorkerID string `json:"worker_id"`
		Model    string `json:"model"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if in.WorkerID == "" {
		in.WorkerID = "worker"
	}
	if utf8.RuneCountInString(in.WorkerID) > 64 || utf8.RuneCountInString(in.Model) > 100 {
		httpx.Fail(w, r, httpx.ErrBadRequest)
		return
	}
	if err := s.Heartbeat(r.Context(), in.WorkerID, in.Model); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// HandleClaim POST /worker/jobs/claim：长轮询，最长 ClaimWait，无任务 204。
func (s *Service) HandleClaim(w http.ResponseWriter, r *http.Request) {
	job, err := s.Claim(r.Context(), workerID(r), s.ClaimWait)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if job == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	httpx.JSON(w, http.StatusOK, job)
}

// HandleResult POST /worker/jobs/{job_id}/result
func (s *Service) HandleResult(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(chi.URLParam(r, "job_id"), 10, 64)
	if err != nil || id <= 0 {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	var in struct {
		Status string          `json:"status"`
		Output json.RawMessage `json:"output"`
		Error  *string         `json:"error"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if err := s.Finish(r.Context(), id, in.Status, in.Output, in.Error); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
