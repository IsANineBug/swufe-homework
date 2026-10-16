package main

import (
	"strings"
	"testing"
	"time"
)

const goodEnv = `# 注释
SERVER_URL=https://1.2.3.4:8443/api/v1/
WORKER_TOKEN="0123456789abcdef0123456789"
SERVER_CERT_PIN=sha256/AAAA, sha256/BBBB
LLM_BASE_URL=https://llm.example.com/v1/
LLM_API_KEY=sk-test # 行尾注释
export LLM_MODEL='deepseek-v4.1-flash'
HTTPS_PROXY=http://127.0.0.1:7890
ACTIVE_HOURS=12-24
`

func noEnv(string) (string, bool) { return "", false }

func TestLoadConfig(t *testing.T) {
	m, err := ParseDotEnv(goodEnv)
	if err != nil {
		t.Fatal(err)
	}
	c, err := LoadConfig(m, noEnv)
	if err != nil {
		t.Fatal(err)
	}
	if c.ServerURL.String() != "https://1.2.3.4:8443/api/v1" || c.WorkerToken != "0123456789abcdef0123456789" ||
		len(c.CertPins) != 2 || c.LLMBaseURL != "https://llm.example.com/v1" || c.LLMAPIKey != "sk-test" ||
		c.LLMModel != "deepseek-v4.1-flash" || c.Proxy.Host != "127.0.0.1:7890" || !c.LLMJSONMode ||
		c.Concurrency != 2 || c.JobTimeout != 90*time.Second || c.HeartbeatEach != 20*time.Second {
		t.Fatalf("config = %+v", c)
	}
	// 系统环境变量优先
	c, _ = LoadConfig(m, func(k string) (string, bool) {
		if k == "LLM_MODEL" {
			return "other", true
		}
		return "", false
	})
	if c.LLMModel != "other" {
		t.Fatal("env override")
	}
}

func TestLoadConfigRejectsPlaceholders(t *testing.T) {
	m, _ := ParseDotEnv(`SERVER_URL=https://YOUR_SERVER_IP:8443/api/v1
SERVER_CERT_PIN=sha256/CHANGE_ME
LLM_BASE_URL=CHANGE_ME
LLM_API_KEY=CHANGE_ME
WORKER_TOKEN=CHANGE_ME_LONG_RANDOM`)
	_, err := LoadConfig(m, noEnv)
	if err == nil {
		t.Fatal("placeholders accepted")
	}
	for _, want := range []string{"WORKER_TOKEN", "SERVER_CERT_PIN", "LLM_BASE_URL", "LLM_API_KEY"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error lacks %s: %v", want, err)
		}
	}
	// http 只在显式允许时可用
	m2, _ := ParseDotEnv(strings.Replace(goodEnv, "https://1.2.3.4", "http://1.2.3.4", 1))
	if _, err := LoadConfig(m2, noEnv); err == nil {
		t.Fatal("http accepted")
	}
	m2["ALLOW_INSECURE_HTTP"] = "1"
	if _, err := LoadConfig(m2, noEnv); err != nil {
		t.Fatal(err)
	}
}

func TestWindow(t *testing.T) {
	at := func(h int) time.Time { return time.Date(2026, 10, 16, h, 30, 0, 0, time.Local) }
	w, _ := parseWindow("12-24")
	if w.Contains(at(11)) || !w.Contains(at(12)) || !w.Contains(at(23)) || w.Contains(at(0)) {
		t.Fatal("12-24")
	}
	w, _ = parseWindow("20-2")
	if !w.Contains(at(21)) || !w.Contains(at(1)) || w.Contains(at(2)) || w.Contains(at(12)) {
		t.Fatal("20-2")
	}
	for _, bad := range []string{"12", "12-12", "-1-5", "0-25", "a-b"} {
		if _, err := parseWindow(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
}
