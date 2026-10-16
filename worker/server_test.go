package main

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func spkiPin(t *testing.T, srv *httptest.Server) string {
	sum := sha256.Sum256(srv.Certificate().RawSubjectPublicKeyInfo)
	return "sha256/" + base64.StdEncoding.EncodeToString(sum[:])
}

func testConfig(t *testing.T, srv *httptest.Server, pins ...string) *Config {
	u, _ := url.Parse(srv.URL + "/api/v1")
	return &Config{ServerURL: u, WorkerToken: "tok-0123456789abcdef01234", CertPins: pins, WorkerID: "pc",
		Concurrency: 1, JobTimeout: 5 * time.Second, HeartbeatEach: 50 * time.Millisecond, LLMModel: "m"}
}

func TestCertPinning(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok-0123456789abcdef01234" || r.Header.Get("X-Worker-Id") != "pc" {
			w.WriteHeader(401)
			return
		}
		w.WriteHeader(204)
	}))
	defer srv.Close()
	ok := NewServerClient(testConfig(t, srv, spkiPin(t, srv)))
	if err := ok.Heartbeat(context.Background(), "m"); err != nil {
		t.Fatalf("pinned: %v", err)
	}
	bad := NewServerClient(testConfig(t, srv, "sha256/"+base64.StdEncoding.EncodeToString(make([]byte, 32))))
	err := bad.Heartbeat(context.Background(), "m")
	if err == nil || !strings.Contains(err.Error(), "公钥不匹配") {
		t.Fatalf("wrong pin: %v", err)
	}
}

func TestFetchImageOnlyFromServerFiles(t *testing.T) {
	png := []byte("\x89PNG\r\n\x1a\n" + strings.Repeat("\x00", 32))
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/files/a b.png":
			if r.URL.Query().Get("sig") != "abc" {
				w.WriteHeader(403)
				return
			}
			_, _ = w.Write(png)
		case "/files/x.html":
			_, _ = w.Write([]byte("<html>hi</html>"))
		default:
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	s := NewServerClient(testConfig(t, srv, spkiPin(t, srv)))
	ctx := context.Background()
	data, err := s.FetchImage(ctx, "/files/a%20b.png?exp=1&sig=abc")
	if err != nil || data != "data:image/png;base64,"+base64.StdEncoding.EncodeToString(png) {
		t.Fatalf("fetch = %q %v", data, err)
	}
	for _, link := range []string{
		"http://169.254.169.254/latest/meta-data",
		"https://evil.example.com/files/a.png",
		"/api/v1/me",
		"/files/../api/v1/me",
	} {
		if _, err := s.FetchImage(ctx, link); err == nil {
			t.Errorf("%s accepted", link)
		}
	}
	if _, err := s.FetchImage(ctx, "/files/x.html"); err == nil || !strings.Contains(err.Error(), "不支持") {
		t.Errorf("html accepted: %v", err)
	}
}

// fakeServer 模拟 /worker/*：发一个任务，收结果。
type fakeServer struct {
	mu      sync.Mutex
	jobs    []Job
	results map[int64]map[string]any
	beats   int
}

func (f *fakeServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case r.URL.Path == "/api/v1/worker/heartbeat":
		f.beats++
		w.WriteHeader(204)
	case r.URL.Path == "/api/v1/worker/jobs/claim":
		if len(f.jobs) == 0 {
			w.WriteHeader(204)
			return
		}
		j := f.jobs[0]
		f.jobs = f.jobs[1:]
		_ = json.NewEncoder(w).Encode(j)
	case strings.HasPrefix(r.URL.Path, "/api/v1/worker/jobs/"):
		var body map[string]any
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &body)
		parts := strings.Split(r.URL.Path, "/")
		id, _ := strconv.ParseInt(parts[len(parts)-2], 10, 64)
		f.results[id] = body
		w.WriteHeader(204)
	default:
		w.WriteHeader(404)
	}
}

func TestWorkerEndToEnd(t *testing.T) {
	f := &fakeServer{results: map[int64]map[string]any{}, jobs: []Job{
		{ID: 7, Kind: "spam_check", Input: json.RawMessage(`{"context":{"today":"2026-10-16"},"title":"第三章习题","body":"周五交"}`)},
		{ID: 8, Kind: "spam_check", Input: json.RawMessage(`{"title":"x","body":"y"}`)},
	}}
	srv := httptest.NewTLSServer(f)
	defer srv.Close()
	cfg := testConfig(t, srv, spkiPin(t, srv))
	w := &Worker{cfg: cfg, srv: NewServerClient(cfg)}
	calls := 0
	w.proc = &Processor{Images: w.srv, LLM: chatFunc(func(ctx context.Context, msgs []Message) (string, error) {
		calls++
		if strings.Contains(msgs[1].Content.(string), `"title":"x"`) {
			return "不是 JSON", nil // 两次都不合格 → failed
		}
		return "```json\n{\"suspicious\":false,\"category\":null,\"reason\":\"\"}\n```", nil
	})}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	go w.Run(ctx)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		f.mu.Lock()
		n := len(f.results)
		f.mu.Unlock()
		if n == 2 {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	r7, r8 := f.results[7], f.results[8]
	if r7["status"] != "done" || r7["output"].(map[string]any)["suspicious"] != false {
		t.Fatalf("job 7 = %v", r7)
	}
	if r8["status"] != "failed" || r8["output"] != nil || !strings.Contains(r8["error"].(string), "JSON") {
		t.Fatalf("job 8 = %v", r8)
	}
	if calls != 3 || f.beats == 0 {
		t.Fatalf("calls=%d beats=%d", calls, f.beats)
	}
}

type chatFunc func(ctx context.Context, msgs []Message) (string, error)

func (f chatFunc) Chat(ctx context.Context, msgs []Message) (string, error) { return f(ctx, msgs) }
