// Package httpx 统一的 JSON 输出、错误格式 {code,message} 与时间格式。
package httpx

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"
)

// Shanghai 业务时区。
var Shanghai = mustLoad("Asia/Shanghai")

func mustLoad(name string) *time.Location {
	loc, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return loc
}

// Error 是对外的错误；Code 机器可读，Message 可直接展示的中文。
type Error struct {
	Status  int    `json:"-"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

func E(status int, code, msg string) *Error { return &Error{Status: status, Code: code, Message: msg} }

var (
	ErrUnauthorized = E(http.StatusUnauthorized, "unauthorized", "请先登录")
	ErrForbidden    = E(http.StatusForbidden, "forbidden", "没有权限")
	ErrNotFound     = E(http.StatusNotFound, "not_found", "内容不存在")
	ErrBadRequest   = E(http.StatusBadRequest, "bad_request", "请求格式不正确")
)

func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

// Fail 输出错误；非 *Error 一律记日志并返回 500。
func Fail(w http.ResponseWriter, r *http.Request, err error) {
	var he *Error
	if errors.As(err, &he) {
		JSON(w, he.Status, he)
		return
	}
	slog.ErrorContext(r.Context(), "internal error", "err", err, "path", r.URL.Path)
	JSON(w, http.StatusInternalServerError, E(500, "internal", "服务器开小差了，请稍后再试"))
}

// Decode 读 JSON 请求体（最大 1MB，不允许未知字段以外的格式错误）。
func Decode(r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 1<<20)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		return ErrBadRequest
	}
	return nil
}

// Time 以 +08:00 输出 ISO 8601。
func Time(t time.Time) string { return t.In(Shanghai).Format(time.RFC3339) }

func TimePtr(t *time.Time) *string {
	if t == nil {
		return nil
	}
	s := Time(*t)
	return &s
}
