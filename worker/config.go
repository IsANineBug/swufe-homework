package main

import (
	"bufio"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// Config 全部来自同目录 .env（系统环境变量同名时优先，便于 launchd / systemd 覆盖）。
type Config struct {
	ServerURL      *url.URL // 形如 https://1.2.3.4:8443/api/v1
	WorkerToken    string
	CertPins       []string // sha256/<base64>，可多个
	LLMBaseURL     string
	LLMAPIKey      string
	LLMModel       string
	LLMJSONMode    bool     // 请求 response_format=json_object
	LLMMaxTokens   int      // 识图/解析作业的输出上限；文字任务取 min(此值, 16384)
	LLMTemperature *float64 // nil = 不传 temperature
	Proxy          *url.URL // 只用于访问 LLM；连服务器不走代理
	WorkerID       string
	Concurrency    int
	ActiveHours    *Window // 可选：只在这个时段在线
	JobTimeout     time.Duration
	HeartbeatEach  time.Duration
}

// DefaultLLMModel Command Code 上 Gemini 3.8 Flash 的模型 id（思考等级由 Command Code 固定为 high）。
// 来源：commandcode.ai 模型清单（docs/reference/cli/models），未用 API Key 实测 GET /models，可用 LLM_MODEL 覆盖。
const DefaultLLMModel = "google/gemini-3.8-flash"

// LLM_MAX_TOKENS 的默认值与允许范围。
const (
	DefaultMaxTokens = 32768
	MinMaxTokens     = 4096
	MaxMaxTokens     = 65536
	TextTaskMaxCap   = 16384
)

// Window 每天的在线时段，[From, To) 小时，To 可为 24。
type Window struct{ From, To int }

func (w *Window) Contains(t time.Time) bool {
	h := t.Hour()
	if w.From <= w.To {
		return h >= w.From && h < w.To
	}
	return h >= w.From || h < w.To // 跨零点，如 20-2
}

// ParseDotEnv 解析 KEY=VALUE；支持 # 注释、export 前缀、单双引号。
func ParseDotEnv(s string) (map[string]string, error) {
	out := map[string]string{}
	sc := bufio.NewScanner(strings.NewReader(s))
	n := 0
	for sc.Scan() {
		n++
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf(".env 第 %d 行缺少 =", n)
		}
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if len(v) >= 2 && (v[0] == '"' && v[len(v)-1] == '"' || v[0] == '\'' && v[len(v)-1] == '\'') {
			v = v[1 : len(v)-1]
		} else if i := strings.Index(v, " #"); i >= 0 {
			v = strings.TrimSpace(v[:i]) // 行尾注释
		}
		out[k] = v
	}
	return out, sc.Err()
}

// findDotEnv 优先 -env 指定的路径，其次可执行文件同目录，最后当前目录。
func findDotEnv(explicit string) string {
	if explicit != "" {
		return explicit
	}
	if exe, err := os.Executable(); err == nil {
		if p := filepath.Join(filepath.Dir(exe), ".env"); fileExists(p) {
			return p
		}
	}
	if fileExists(".env") {
		return ".env"
	}
	return ""
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

// LoadConfig 读取并校验配置。lookup 为 nil 时用 os.LookupEnv。
func LoadConfig(dotenv map[string]string, lookup func(string) (string, bool)) (*Config, error) {
	if lookup == nil {
		lookup = os.LookupEnv
	}
	get := func(k, def string) string {
		if v, ok := lookup(k); ok && v != "" {
			return v
		}
		if v := dotenv[k]; v != "" {
			return v
		}
		return def
	}
	c := &Config{
		WorkerToken:   get("WORKER_TOKEN", ""),
		LLMBaseURL:    strings.TrimRight(get("LLM_BASE_URL", ""), "/"),
		LLMAPIKey:     get("LLM_API_KEY", ""),
		LLMModel:      get("LLM_MODEL", DefaultLLMModel),
		JobTimeout:    90 * time.Second,
		HeartbeatEach: 20 * time.Second,
	}
	var errs []string
	su, err := url.Parse(get("SERVER_URL", ""))
	switch {
	case err != nil || su.Host == "":
		errs = append(errs, "SERVER_URL 须为 https://<IP>:<端口>/api/v1")
	case su.Scheme != "https" && su.Scheme != "http":
		errs = append(errs, "SERVER_URL 须为 https")
	default:
		su.Path = strings.TrimRight(su.Path, "/")
		c.ServerURL = su
	}
	if len(c.WorkerToken) < 24 || strings.HasPrefix(c.WorkerToken, "CHANGE_ME") {
		errs = append(errs, "WORKER_TOKEN 未填写（与服务器 .env 里的一致）")
	}
	for _, p := range strings.Split(get("SERVER_CERT_PIN", ""), ",") {
		if p = strings.TrimSpace(p); p != "" && !strings.Contains(p, "CHANGE_ME") {
			c.CertPins = append(c.CertPins, p)
		}
	}
	if c.ServerURL != nil && c.ServerURL.Scheme == "https" && len(c.CertPins) == 0 {
		errs = append(errs, "SERVER_CERT_PIN 未填写（deploy/scripts/gen-cert.sh 输出的 sha256/...）")
	}
	if c.ServerURL != nil && c.ServerURL.Scheme == "http" && get("ALLOW_INSECURE_HTTP", "") != "1" {
		errs = append(errs, "SERVER_URL 是 http：只有本机调试时设 ALLOW_INSECURE_HTTP=1 才允许")
	}
	if c.LLMBaseURL == "" || strings.Contains(c.LLMBaseURL, "CHANGE_ME") {
		errs = append(errs, "LLM_BASE_URL 未填写")
	}
	if c.LLMAPIKey == "" || strings.Contains(c.LLMAPIKey, "CHANGE_ME") {
		errs = append(errs, "LLM_API_KEY 未填写")
	}
	if p := get("HTTPS_PROXY", ""); p != "" {
		pu, err := url.Parse(p)
		if err != nil || pu.Host == "" {
			errs = append(errs, "HTTPS_PROXY 格式不对，如 http://127.0.0.1:7890")
		} else {
			c.Proxy = pu
		}
	}
	switch strings.ToLower(get("LLM_JSON_MODE", "on")) {
	case "on", "1", "true":
		c.LLMJSONMode = true
	case "off", "0", "false":
	default:
		errs = append(errs, "LLM_JSON_MODE 只能是 on / off")
	}
	c.LLMMaxTokens, err = strconv.Atoi(get("LLM_MAX_TOKENS", strconv.Itoa(DefaultMaxTokens)))
	if err != nil || c.LLMMaxTokens < MinMaxTokens || c.LLMMaxTokens > MaxMaxTokens {
		errs = append(errs, fmt.Sprintf("LLM_MAX_TOKENS 须为 %d–%d 的整数", MinMaxTokens, MaxMaxTokens))
	}
	if v := get("LLM_TEMPERATURE", ""); v != "" {
		f, err := strconv.ParseFloat(v, 64)
		if err != nil || f < 0 || f > 2 {
			errs = append(errs, "LLM_TEMPERATURE 须为 0–2 的数，或留空（不传，用模型默认值）")
		} else {
			c.LLMTemperature = &f
		}
	}
	c.Concurrency, err = strconv.Atoi(get("WORKER_CONCURRENCY", "2"))
	if err != nil || c.Concurrency < 1 || c.Concurrency > 8 {
		errs = append(errs, "WORKER_CONCURRENCY 须为 1–8")
	}
	if v := get("ACTIVE_HOURS", ""); v != "" {
		w, err := parseWindow(v)
		if err != nil {
			errs = append(errs, err.Error())
		}
		c.ActiveHours = w
	}
	host, _ := os.Hostname()
	if host == "" {
		host = "pc"
	}
	c.WorkerID = get("WORKER_ID", host)
	if len(c.WorkerID) > 64 {
		c.WorkerID = c.WorkerID[:64]
	}
	if len(errs) > 0 {
		return nil, errors.New("配置有误：\n  - " + strings.Join(errs, "\n  - "))
	}
	return c, nil
}

func parseWindow(v string) (*Window, error) {
	a, b, ok := strings.Cut(v, "-")
	from, err1 := strconv.Atoi(strings.TrimSpace(a))
	to, err2 := strconv.Atoi(strings.TrimSpace(b))
	if !ok || err1 != nil || err2 != nil || from < 0 || from > 23 || to < 1 || to > 24 || from == to {
		return nil, fmt.Errorf("ACTIVE_HOURS 格式为 起-止（整点，如 12-24）")
	}
	return &Window{From: from, To: to}, nil
}
