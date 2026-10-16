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
			return store.SemesterSpec{Name: cfg.SemesterName, StartsOn: a, EndsOn: b}
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

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.New(cfg, pool).Router(),
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
