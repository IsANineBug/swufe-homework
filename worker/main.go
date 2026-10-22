// swufe-worker：AI 任务拉取程序，和后端部署在同一台服务器上常驻（systemd 或 docker，见 README）。
//
// 主动连后端：每 20 秒心跳，长轮询领取任务，调用模型（需要时经 HTTPS_PROXY），
// 用 JSON Schema 校验输出后回传。契约见 docs/ai-contract.md，用法见 worker/README.md。
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"runtime/debug"
	"sync"
	"syscall"
	"time"
)

// defaultMemLimit 未设 GOMEMLIMIT 时的 Go 内存软上限（硬上限由 systemd MemoryMax / docker mem_limit 的 64M 兜底）。
const defaultMemLimit = 48 << 20

// version 由 release 构建注入：-ldflags "-X main.version=v1.2.3"
var version = "dev"

func main() {
	envPath := flag.String("env", "", ".env 路径（默认取可执行文件同目录，其次当前目录）")
	showVersion := flag.Bool("version", false, "打印版本后退出")
	check := flag.Bool("check", false, "只检查配置、服务器连通和证书固定，然后退出")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
	// 和后端同机，总内存控制在 64 MB 以内：没设 GOMEMLIMIT 环境变量时给 Go 一个 48 MiB 的软上限。
	if os.Getenv("GOMEMLIMIT") == "" {
		debug.SetMemoryLimit(defaultMemLimit)
	}
	if err := run(*envPath, *check); err != nil {
		slog.Error("退出", "err", err)
		os.Exit(1)
	}
}

func run(envPath string, checkOnly bool) error {
	dotenv := map[string]string{}
	if p := findDotEnv(envPath); p != "" {
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		if dotenv, err = ParseDotEnv(string(b)); err != nil {
			return err
		}
		slog.Info("读取配置", "file", p)
	} else if envPath != "" {
		return fmt.Errorf("找不到 %s", envPath)
	}
	cfg, err := LoadConfig(dotenv, nil)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	w := &Worker{cfg: cfg, srv: NewServerClient(cfg)}
	w.proc = &Processor{LLM: NewLLM(cfg), Images: w.srv, MaxTokens: cfg.LLMMaxTokens}
	if checkOnly {
		if err := w.srv.Heartbeat(ctx, cfg.LLMModel); err != nil {
			return fmt.Errorf("连服务器失败：%w", err)
		}
		fmt.Println("服务器连通、证书固定与 WORKER_TOKEN 均正常")
		return nil
	}
	slog.Info("swufe-worker 启动", "version", version, "server", cfg.ServerURL.Host, "model", cfg.LLMModel,
		"max_tokens", cfg.LLMMaxTokens, "temperature", tempLabel(cfg.LLMTemperature),
		"concurrency", cfg.Concurrency, "proxy", cfg.Proxy != nil)
	w.Run(ctx)
	slog.Info("已停止")
	return nil
}

// Worker 心跳 + N 个领取循环。
type Worker struct {
	cfg  *Config
	srv  *ServerClient
	proc *Processor
	now  func() time.Time
}

func (w *Worker) active() bool {
	if w.cfg.ActiveHours == nil {
		return true
	}
	now := time.Now
	if w.now != nil {
		now = w.now
	}
	return w.cfg.ActiveHours.Contains(now())
}

// Run 阻塞到 ctx 结束；正在处理的任务会做完再退出（最多 90 秒）。
func (w *Worker) Run(ctx context.Context) {
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); w.heartbeatLoop(ctx) }()
	for i := 0; i < w.cfg.Concurrency; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); w.claimLoop(ctx) }()
	}
	wg.Wait()
}

func (w *Worker) heartbeatLoop(ctx context.Context) {
	t := time.NewTicker(w.cfg.HeartbeatEach)
	defer t.Stop()
	wasActive := false
	for {
		if w.active() {
			if !wasActive {
				slog.Info("进入在线时段")
			}
			wasActive = true
			hctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			if err := w.srv.Heartbeat(hctx, w.cfg.LLMModel); err != nil && ctx.Err() == nil {
				slog.Warn("心跳失败", "err", err)
			}
			cancel()
		} else if wasActive {
			// 不再心跳，60 秒后服务器判离线，App 置灰 AI 按钮
			slog.Info("离开在线时段，停止心跳")
			wasActive = false
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func (w *Worker) claimLoop(ctx context.Context) {
	backoff := time.Second
	for ctx.Err() == nil {
		if !w.active() {
			sleep(ctx, time.Minute)
			continue
		}
		job, err := w.srv.Claim(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			var he *httpError
			if errors.As(err, &he) && he.Status == 401 {
				slog.Error("WORKER_TOKEN 被服务器拒绝，检查两边 .env 是否一致")
				backoff = time.Minute
			} else {
				slog.Warn("领取任务失败", "err", err, "retry_in", backoff)
			}
			sleep(ctx, backoff)
			backoff = min(backoff*2, time.Minute)
			continue
		}
		backoff = time.Second
		if job == nil {
			continue
		}
		w.handle(job)
	}
}

// handle 不随 ctx 取消：收到退出信号时把手上的任务做完并回传。
func (w *Worker) handle(job *Job) {
	start := time.Now()
	jctx, cancel := context.WithTimeout(context.Background(), w.cfg.JobTimeout)
	out, err := w.proc.Process(jctx, job.Kind, job.Input)
	cancel()
	msg := ""
	if err != nil {
		msg = err.Error()
		switch {
		case errors.Is(err, ErrTruncated):
			msg = "输出被截断"
		case errors.Is(err, context.DeadlineExceeded):
			msg = "超时（90 秒）"
		}
		out = nil
	}
	rctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if rerr := w.srv.Result(rctx, job.ID, out, msg); rerr != nil {
		slog.Warn("回传结果失败", "job", job.ID, "err", rerr)
		return
	}
	// 日志不记输入输出内容（作业正文、图片），只记种类、结果与耗时
	if err != nil {
		slog.Warn("任务失败", "job", job.ID, "kind", job.Kind, "ms", time.Since(start).Milliseconds(), "err", msg)
	} else {
		slog.Info("任务完成", "job", job.ID, "kind", job.Kind, "ms", time.Since(start).Milliseconds())
	}
}

func tempLabel(t *float64) string {
	if t == nil {
		return "default"
	}
	return fmt.Sprint(*t)
}

func sleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}
