// swufe-homework 后端入口。
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	_ "time/tzdata" // 镜像里没有 tzdata 也能加载 Asia/Shanghai

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/config"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/jobs"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

func main() {
	if err := run(); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	level := slog.LevelInfo
	_ = level.UnmarshalText([]byte(cfg.LogLevel))
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})))

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if err := store.Migrate(cfg.DatabaseURL); err != nil {
		return err
	}
	pool, err := store.Connect(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	// 学期（issue #4 裁决 12）：设置了 SEMESTER_* 则以其为准；否则按校历自动推算与切换。
	// 启动时一次，之后每小时检查
	semesterSpec := func() store.SemesterSpec {
		if cfg.SemesterName != "" {
			a, _ := time.Parse(time.DateOnly, cfg.SemesterStartsOn)
			b, _ := time.Parse(time.DateOnly, cfg.SemesterEndsOn)
			return store.SemesterSpec{Name: cfg.SemesterName, StartsOn: a, EndsOn: b, Explicit: true}
		}
		return store.SemesterFor(time.Now().In(httpx.Shanghai))
	}
	ensureSemester := func() error {
		spec := semesterSpec()
		switched, err := store.EnsureCurrentSemester(ctx, pool, spec)
		if switched {
			slog.Info("current semester switched", "name", spec.Name)
		}
		return err
	}
	if err := ensureSemester(); err != nil {
		return err
	}
	go func() {
		t := time.NewTicker(time.Hour)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if err := ensureSemester(); err != nil {
					slog.Error("ensure semester", "err", err)
				}
			}
		}
	}()

	// WS2 定时任务：积分结算、合并提议过期、token 清理、图片清理
	(&jobs.Runner{DB: pool, UploadDir: cfg.UploadDir}).Start(ctx)

	app := api.New(cfg, pool)
	if cfg.WorkerTokenPlaceholder {
		slog.Warn("WORKER_TOKEN 还是 .env.example 的占位值，按未设置处理：AI 始终离线")
	} else if cfg.WorkerToken == "" {
		slog.Warn("WORKER_TOKEN 未设置：AI 始终离线")
	}
	go app.AI.Run(ctx) // WS3：AI 任务过期与清理
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           app.Router(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	errc := make(chan error, 1)
	go func() {
		slog.Info("listening", "addr", cfg.Addr, "campus_cidrs", len(cfg.CampusCIDRs))
		errc <- srv.ListenAndServe()
	}()
	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return srv.Shutdown(shutdown)
}
