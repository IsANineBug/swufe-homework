// Package config 从环境变量读取全部配置。
package config

import (
	"fmt"
	"net/netip"
	"os"
	"strings"
	"time"
)

type Config struct {
	Addr                string
	DatabaseURL         string
	JWTSecret           []byte
	BootstrapAdminToken string
	CampusCIDRs         []netip.Prefix
	TrustedProxies      []netip.Prefix
	RegisterPerIPHour   int
	AccessTTL           time.Duration
	RefreshTTL          time.Duration
	LogLevel            string
	// WorkerToken 电脑端 worker 的 Bearer 令牌；为空则 /worker/* 一律 401（AI 始终离线）
	WorkerToken string
	// WorkerTokenPlaceholder WORKER_TOKEN 仍是 .env.example 的 CHANGE_ME 占位值，按未设置处理（main 打警告）
	WorkerTokenPlaceholder bool
	// 可选：给定则启动时确保该学期存在并设为当前学期；不给则按校历自动推算（store.SemesterFor）
	SemesterName     string
	SemesterStartsOn string
	SemesterEndsOn   string
	// WS2：上传目录与 /files 签名密钥（未设置时由 JWT_SECRET 派生）
	UploadDir     string
	FileURLSecret []byte
	FileURLTTL    time.Duration
}

// DefaultTrustedProxy 只信任 docker 网络里固定 IP 的 nginx（与 WS6 docker-compose 约定）。
const DefaultTrustedProxy = "172.28.0.10/32"

func Load() (*Config, error) {
	c := &Config{
		Addr:                getenv("HTTP_ADDR", ":8080"),
		DatabaseURL:         os.Getenv("DATABASE_URL"),
		JWTSecret:           []byte(os.Getenv("JWT_SECRET")),
		BootstrapAdminToken: os.Getenv("BOOTSTRAP_ADMIN_TOKEN"),
		RegisterPerIPHour:   30,
		AccessTTL:           15 * time.Minute,
		RefreshTTL:          30 * 24 * time.Hour,
		LogLevel:            getenv("LOG_LEVEL", "info"),
		SemesterName:        os.Getenv("SEMESTER_NAME"),
		SemesterStartsOn:    os.Getenv("SEMESTER_STARTS_ON"),
		SemesterEndsOn:      os.Getenv("SEMESTER_ENDS_ON"),
		UploadDir:           getenv("UPLOAD_DIR", "/data/uploads"),
		FileURLSecret:       []byte(os.Getenv("FILE_URL_SECRET")),
		FileURLTTL:          time.Hour,
		WorkerToken:         os.Getenv("WORKER_TOKEN"),
	}
	if c.DatabaseURL == "" {
		return nil, fmt.Errorf("DATABASE_URL 未设置")
	}
	if len(c.JWTSecret) < 32 {
		return nil, fmt.Errorf("JWT_SECRET 至少 32 个字符")
	}
	if len(c.FileURLSecret) > 0 && len(c.FileURLSecret) < 16 {
		return nil, fmt.Errorf("FILE_URL_SECRET 至少 16 个字符（或留空，由 JWT_SECRET 派生）")
	}
	if strings.HasPrefix(c.WorkerToken, "CHANGE_ME") {
		// 照抄 .env.example 没改：不让服务器起不来，按未设置处理（AI 离线）
		c.WorkerToken, c.WorkerTokenPlaceholder = "", true
	}
	if c.WorkerToken != "" && len(c.WorkerToken) < 24 {
		return nil, fmt.Errorf("WORKER_TOKEN 至少 24 个字符（留空 = 不启用 AI）")
	}
	var err error
	if c.CampusCIDRs, err = ParseCIDRs(os.Getenv("CAMPUS_CIDRS")); err != nil {
		return nil, fmt.Errorf("CAMPUS_CIDRS: %w", err)
	}
	if c.TrustedProxies, err = ParseCIDRs(getenv("TRUSTED_PROXY", DefaultTrustedProxy)); err != nil {
		return nil, fmt.Errorf("TRUSTED_PROXY: %w", err)
	}
	if v := os.Getenv("REGISTER_PER_IP_HOUR"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &c.RegisterPerIPHour); err != nil {
			return nil, fmt.Errorf("REGISTER_PER_IP_HOUR: %w", err)
		}
	}
	if c.SemesterName != "" || c.SemesterStartsOn != "" || c.SemesterEndsOn != "" {
		if c.SemesterName == "" || c.SemesterStartsOn == "" || c.SemesterEndsOn == "" {
			return nil, fmt.Errorf("SEMESTER_NAME / SEMESTER_STARTS_ON / SEMESTER_ENDS_ON 须同时设置")
		}
		a, err1 := time.Parse(time.DateOnly, c.SemesterStartsOn)
		b, err2 := time.Parse(time.DateOnly, c.SemesterEndsOn)
		if err1 != nil || err2 != nil || !a.Before(b) {
			return nil, fmt.Errorf("SEMESTER_STARTS_ON / SEMESTER_ENDS_ON 须为 YYYY-MM-DD 且开始早于结束")
		}
	}
	return c, nil
}

// ParseCIDRs 解析逗号分隔的 CIDR 或单个 IP，空串返回空列表。
func ParseCIDRs(s string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if !strings.Contains(part, "/") {
			a, err := netip.ParseAddr(part)
			if err != nil {
				return nil, err
			}
			out = append(out, netip.PrefixFrom(a, a.BitLen()))
			continue
		}
		p, err := netip.ParsePrefix(part)
		if err != nil {
			return nil, err
		}
		out = append(out, p.Masked())
	}
	return out, nil
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
