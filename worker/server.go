package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// pinnedTLS 服务器是自签证书：不走 CA 校验，改为比对叶子证书公钥（SPKI）的 sha256。
func pinnedTLS(pins []string) *tls.Config {
	want := map[string]bool{}
	for _, p := range pins {
		want[strings.TrimPrefix(strings.TrimSpace(p), "sha256/")] = true
	}
	return &tls.Config{
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: true, //nolint:gosec // 自签证书，由下面的公钥固定校验代替 CA 校验
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 {
				return errors.New("服务器没有提供证书")
			}
			cert, err := x509.ParseCertificate(raw[0])
			if err != nil {
				return err
			}
			sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
			got := base64.StdEncoding.EncodeToString(sum[:])
			if !want[got] {
				return fmt.Errorf("服务器证书公钥不匹配（sha256/%s），检查 SERVER_CERT_PIN", got)
			}
			return nil
		},
	}
}

// ServerClient 访问 /api/v1/worker/* 与 /files/*。不走代理（服务器在国内）。
type ServerClient struct {
	base     *url.URL // .../api/v1
	origin   *url.URL // scheme://host:port
	token    string
	workerID string
	hc       *http.Client
}

func NewServerClient(c *Config) *ServerClient {
	tr := &http.Transport{
		Proxy:               nil,
		TLSHandshakeTimeout: 10 * time.Second,
		MaxIdleConnsPerHost: 4,
		IdleConnTimeout:     90 * time.Second,
	}
	if c.ServerURL.Scheme == "https" {
		tr.TLSClientConfig = pinnedTLS(c.CertPins)
	}
	origin := &url.URL{Scheme: c.ServerURL.Scheme, Host: c.ServerURL.Host}
	return &ServerClient{base: c.ServerURL, origin: origin, token: c.WorkerToken, workerID: c.WorkerID,
		hc: &http.Client{Transport: tr, Timeout: 40 * time.Second}}
}

// Job 领取到的任务。
type Job struct {
	ID    int64           `json:"id"`
	Kind  string          `json:"kind"`
	Input json.RawMessage `json:"input"`
}

type httpError struct {
	Status int
	Body   string
}

func (e *httpError) Error() string { return fmt.Sprintf("HTTP %d: %s", e.Status, e.Body) }

func (s *ServerClient) post(ctx context.Context, path string, body any) (*http.Response, error) {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.base.String()+path, rd)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+s.token)
	req.Header.Set("X-Worker-Id", s.workerID)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := s.hc.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		resp.Body.Close()
		return nil, &httpError{Status: resp.StatusCode, Body: strings.TrimSpace(string(b))}
	}
	return resp, nil
}

func (s *ServerClient) Heartbeat(ctx context.Context, model string) error {
	resp, err := s.post(ctx, "/worker/heartbeat", map[string]string{"worker_id": s.workerID, "model": model})
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// Claim 长轮询（服务器最长挂 25 秒）；无任务返回 nil。
func (s *ServerClient) Claim(ctx context.Context) (*Job, error) {
	resp, err := s.post(ctx, "/worker/jobs/claim", nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent {
		return nil, nil
	}
	var j Job
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2<<20)).Decode(&j); err != nil {
		return nil, err
	}
	return &j, nil
}

// Result 回传结果。output 为 nil 表示失败。
func (s *ServerClient) Result(ctx context.Context, id int64, output json.RawMessage, errMsg string) error {
	body := map[string]any{"status": "done", "output": output}
	if output == nil {
		body = map[string]any{"status": "failed", "output": nil, "error": errMsg}
	}
	resp, err := s.post(ctx, "/worker/jobs/"+strconv.FormatInt(id, 10)+"/result", body)
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// MaxImageBytes 单张图片上限（与上传上限一致）。
const MaxImageBytes = 10 << 20

var allowedImageTypes = map[string]bool{"image/jpeg": true, "image/png": true, "image/webp": true}

// FetchImage 下载服务器签名的图片，返回 data URL。只允许本服务器的 /files/ 链接，防止被诱导访问任意地址。
func (s *ServerClient) FetchImage(ctx context.Context, link string) (string, error) {
	u, err := url.Parse(link)
	if err != nil {
		return "", fmt.Errorf("图片链接格式不对")
	}
	if u.IsAbs() || u.Host != "" {
		if u.Scheme != s.origin.Scheme || u.Host != s.origin.Host {
			return "", fmt.Errorf("拒绝下载非本服务器的图片")
		}
	}
	if !strings.HasPrefix(u.Path, "/files/") || strings.Contains(u.Path, "..") {
		return "", fmt.Errorf("拒绝下载非 /files/ 的链接")
	}
	full := s.origin.ResolveReference(&url.URL{Path: u.Path, RawPath: u.RawPath, RawQuery: u.RawQuery})
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, full.String(), nil)
	if err != nil {
		return "", err
	}
	resp, err := s.hc.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("下载图片失败：HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, MaxImageBytes+1))
	if err != nil {
		return "", err
	}
	if len(b) > MaxImageBytes {
		return "", fmt.Errorf("图片超过 10MB")
	}
	ct := http.DetectContentType(b)
	if ct == "application/octet-stream" && len(b) > 12 && string(b[0:4]) == "RIFF" && string(b[8:12]) == "WEBP" {
		ct = "image/webp"
	}
	if !allowedImageTypes[ct] {
		return "", fmt.Errorf("不支持的图片类型 %s", ct)
	}
	return "data:" + ct + ";base64," + base64.StdEncoding.EncodeToString(b), nil
}
