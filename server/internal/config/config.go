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
}

func Load() (*Config, error) {
	c := &Config{
		Addr:                getenv("ADDR", ":8080"),
		DatabaseURL:         os.Getenv("DATABASE_URL"),
		JWTSecret:           []byte(os.Getenv("JWT_SECRET")),
		BootstrapAdminToken: os.Getenv("BOOTSTRAP_ADMIN_TOKEN"),
		RegisterPerIPHour:   30,
		AccessTTL:           15 * time.Minute,
		RefreshTTL:          30 * 24 * time.Hour,
		LogLevel:            getenv("LOG_LEVEL", "info"),
	}
	if c.DatabaseURL == "" {
		return nil, fmt.Errorf("DATABASE_URL 未设置")
	}
	if len(c.JWTSecret) < 32 {
		return nil, fmt.Errorf("JWT_SECRET 至少 32 个字符")
	}
	var err error
	if c.CampusCIDRs, err = ParseCIDRs(os.Getenv("CAMPUS_CIDRS")); err != nil {
		return nil, fmt.Errorf("CAMPUS_CIDRS: %w", err)
	}
	if c.TrustedProxies, err = ParseCIDRs(os.Getenv("TRUSTED_PROXY")); err != nil {
		return nil, fmt.Errorf("TRUSTED_PROXY: %w", err)
	}
	if v := os.Getenv("REGISTER_PER_IP_HOUR"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &c.RegisterPerIPHour); err != nil {
			return nil, fmt.Errorf("REGISTER_PER_IP_HOUR: %w", err)
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
