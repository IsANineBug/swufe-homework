// Package api HTTP 路由与处理器（账号与班级模块）。其他工作线在 Router 上挂自己的路由。
package api

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/auth"
	"github.com/IsANineBug/swufe-homework/server/internal/config"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/netx"
)

type Server struct {
	Cfg        *config.Config
	DB         *pgxpool.Pool
	Tokens     *auth.Issuer
	RegLimiter *netx.Limiter
	Now        func() time.Time
}

func New(cfg *config.Config, db *pgxpool.Pool) *Server {
	return &Server{
		Cfg:        cfg,
		DB:         db,
		Tokens:     auth.NewIssuer(cfg.JWTSecret, cfg.AccessTTL),
		RegLimiter: netx.NewLimiter(cfg.RegisterPerIPHour, time.Hour),
		Now:        time.Now,
	}
}

// Router 返回完整路由。/healthz 在根路径，业务接口在 /api/v1。
func (s *Server) Router() http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID, s.realIP, requestLogger, middleware.Recoverer)
	r.Get("/healthz", s.healthz)
	r.NotFound(func(w http.ResponseWriter, r *http.Request) { httpx.Fail(w, r, httpx.ErrNotFound) })
	r.MethodNotAllowed(func(w http.ResponseWriter, r *http.Request) {
		httpx.Fail(w, r, httpx.E(http.StatusMethodNotAllowed, "method_not_allowed", "不支持的请求方法"))
	})

	r.Route("/api/v1", func(r chi.Router) {
		// 公开接口
		r.Get("/auth/campus-check", s.campusCheck)
		r.Post("/auth/register", s.register)
		r.Post("/auth/login", s.login)
		r.Post("/auth/refresh", s.refresh)
		r.Post("/auth/recover", s.recover)
		r.Get("/meta", s.meta)

		// 需登录
		r.Group(func(r chi.Router) {
			r.Use(s.requireUser)
			r.Post("/auth/logout", s.logout)
			r.Get("/me", s.getMe)
			r.Patch("/me", s.patchMe)
			r.Get("/me/invites", s.listInvites)
			r.Post("/me/invites", s.createInvite)
			r.Get("/me/notices", s.listNotices)
			r.Get("/me/classes", s.myClasses)
			r.Delete("/me/classes/{class_id}", s.leaveClass)
			r.Post("/timetable/import", s.importTimetable)
			r.Get("/classes/search", s.searchClasses)
			r.Get("/classes/{class_id}", s.getClass)
		})
	})
	return r
}

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.DB.Ping(ctx); err != nil {
		httpx.JSON(w, http.StatusServiceUnavailable, map[string]string{"status": "db_down"})
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

type ipKey struct{}

// realIP 把可信代理传来的 X-Real-IP 解析后放进 context（不改写 RemoteAddr）。
func (s *Server) realIP(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip := netx.ClientIP(r, s.Cfg.TrustedProxies)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ipKey{}, ip.String())))
	})
}

func clientIP(r *http.Request) string {
	v, _ := r.Context().Value(ipKey{}).(string)
	return v
}

// requestLogger 只记方法、路由、状态、耗时，不记请求体（不落作业正文和图片）。
func requestLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
		next.ServeHTTP(ww, r)
		route := r.URL.Path
		if rc := chi.RouteContext(r.Context()); rc != nil && rc.RoutePattern() != "" {
			route = rc.RoutePattern()
		}
		slog.InfoContext(r.Context(), "http",
			"method", r.Method, "route", route, "status", ww.Status(),
			"ms", time.Since(start).Milliseconds(), "req_id", middleware.GetReqID(r.Context()))
	})
}

// requireUser 校验 access token，并确认账号存在且未停用（角色以库为准）。
func (s *Server) requireUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := auth.BearerToken(r)
		if tok == "" {
			httpx.Fail(w, r, httpx.ErrUnauthorized)
			return
		}
		uid, _, err := s.Tokens.Parse(tok)
		if err != nil {
			httpx.Fail(w, r, httpx.E(http.StatusUnauthorized, "token_expired", "登录已过期，请重新登录"))
			return
		}
		var role string
		var disabled *time.Time
		err = s.DB.QueryRow(r.Context(), `SELECT role, disabled_at FROM users WHERE id = $1`, uid).Scan(&role, &disabled)
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && disabled != nil) {
			httpx.Fail(w, r, httpx.ErrUnauthorized)
			return
		}
		if err != nil {
			httpx.Fail(w, r, err)
			return
		}
		next.ServeHTTP(w, r.WithContext(auth.WithUser(r.Context(), auth.User{ID: uid, Role: role})))
	})
}

func currentUser(r *http.Request) auth.User {
	u, _ := auth.FromContext(r.Context())
	return u
}

// RequireAdmin 供管理员接口使用（role=admin）。
func RequireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !currentUser(r).IsAdmin() {
			httpx.Fail(w, r, httpx.ErrForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}
