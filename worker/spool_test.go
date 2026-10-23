package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

var pngHead = []byte("\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR")

// fakePNG 写一个以 PNG 文件头开头、总长 size 的随机文件。
func fakePNG(t *testing.T, _ string, size int) []byte {
	t.Helper()
	b := make([]byte, size)
	_, _ = rand.Read(b)
	copy(b, pngHead)
	return b
}

func spoolBytes(t *testing.T, dir string, b []byte) *SpooledImage {
	t.Helper()
	img, err := spoolBody(bytes.NewReader(b), dir)
	if err != nil {
		t.Fatal(err)
	}
	return img
}

func okCompletion(w http.ResponseWriter) {
	_, _ = w.Write([]byte(`{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}`))
}

// 请求体里的 data URL 与原图逐字节一致，Content-Length 准确。
func TestStreamBodyMatchesImages(t *testing.T) {
	dir := t.TempDir()
	raw := [][]byte{fakePNG(t, dir, 3<<20+1), fakePNG(t, dir, 777), fakePNG(t, dir, b64Chunk*2)}
	parts := []ContentPart{{Type: "text", Text: "看图"}}
	for _, b := range raw {
		parts = append(parts, ContentPart{Type: "image_url", ImageURL: &ImageURL{spool: spoolBytes(t, dir, b)}})
	}
	var got struct {
		Messages []struct {
			Content json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		if r.ContentLength != int64(len(b)) {
			t.Errorf("Content-Length %d，实际 %d", r.ContentLength, len(b))
		}
		if err := json.Unmarshal(b, &got); err != nil {
			t.Errorf("请求体不是合法 JSON：%v", err)
		}
		okCompletion(w)
	}))
	defer srv.Close()
	l := NewLLM(&Config{LLMBaseURL: srv.URL, LLMAPIKey: "k", LLMModel: "m"})
	msgs := []Message{{Role: "system", Content: "s"}, {Role: "user", Content: parts}}
	if _, err := l.Chat(context.Background(), msgs, CallOpts{Kind: "parse_timetable"}); err != nil {
		t.Fatal(err)
	}
	var user []ContentPart
	if len(got.Messages) != 2 || json.Unmarshal(got.Messages[1].Content, &user) != nil || len(user) != 4 {
		t.Fatalf("消息结构不对：%d 条", len(got.Messages))
	}
	for i, b := range raw {
		want := "data:image/png;base64," + base64.StdEncoding.EncodeToString(b)
		if u := user[i+1].ImageURL.URL; u != want {
			t.Fatalf("第 %d 张图片编码不一致（长度 %d vs %d）", i+1, len(u), len(want))
		}
	}
	// 原消息不被改写，重试时还能再发一次
	if parts[1].ImageURL.URL != "" || parts[1].ImageURL.spool == nil {
		t.Fatal("原消息被改写")
	}
}

// 6 张 10MB 图片（请求体约 80MB）发送期间分配的内存远小于请求体。
func TestStreamBodyMemory(t *testing.T) {
	if testing.Short() {
		t.Skip()
	}
	dir := t.TempDir()
	parts := []ContentPart{{Type: "text", Text: "看图"}}
	for i := 0; i < 6; i++ {
		parts = append(parts, ContentPart{Type: "image_url", ImageURL: &ImageURL{spool: spoolBytes(t, dir, fakePNG(t, dir, MaxImageBytes))}})
	}
	var n int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n, _ = io.Copy(io.Discard, r.Body)
		okCompletion(w)
	}))
	defer srv.Close()
	l := NewLLM(&Config{LLMBaseURL: srv.URL, LLMAPIKey: "k", LLMModel: "m"})
	msgs := []Message{{Role: "user", Content: parts}}
	runtime.GC()
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	if _, err := l.Chat(context.Background(), msgs, CallOpts{Kind: "parse_assignment"}); err != nil {
		t.Fatal(err)
	}
	runtime.ReadMemStats(&after)
	alloc := after.TotalAlloc - before.TotalAlloc
	t.Logf("请求体 %d MB，期间分配 %.1f MB", n>>20, float64(alloc)/(1<<20))
	if n < 6*MaxImageBytes {
		t.Fatalf("请求体只有 %d 字节", n)
	}
	if alloc > 8<<20 {
		t.Fatalf("发送期间分配了 %d 字节，图片被整批读进内存了", alloc)
	}
}

func TestSpoolBodyLimits(t *testing.T) {
	dir := t.TempDir()
	if _, err := spoolBody(bytes.NewReader(fakePNG(t, dir, MaxImageBytes+1)), dir); err == nil || !strings.Contains(err.Error(), "10MB") {
		t.Fatalf("超过 10MB 应拒绝：%v", err)
	}
	if _, err := spoolBody(strings.NewReader("<html><body>x</body></html>"), dir); err == nil || !strings.Contains(err.Error(), "不支持") {
		t.Fatalf("非图片应拒绝：%v", err)
	}
	left, _ := filepath.Glob(filepath.Join(dir, "img-*"))
	if len(left) != 0 {
		t.Fatalf("失败后留下了临时文件：%v", left)
	}
	img := spoolBytes(t, dir, fakePNG(t, dir, 100))
	if st, err := os.Stat(img.Path); err != nil || st.Size() != 100 || img.Mime != "image/png" {
		t.Fatalf("落盘不对：%+v %v", img, err)
	}
}

type fakeSpooler struct {
	t    *testing.T
	dirs []string
}

func (f *fakeSpooler) FetchImage(context.Context, string) (string, error) {
	f.t.Fatal("实现了 SpoolImage 时不应走 FetchImage")
	return "", nil
}

func (f *fakeSpooler) SpoolImage(_ context.Context, _ string, dir string) (*SpooledImage, error) {
	f.dirs = append(f.dirs, dir)
	return spoolBody(bytes.NewReader(fakePNG(f.t, dir, 1000)), dir)
}

// 任务按张落盘，结束后临时目录被删除。
func TestProcessSpoolsAndCleansUp(t *testing.T) {
	sp := &fakeSpooler{t: t}
	p := &Processor{Images: sp, LLM: chatFunc(func(ctx context.Context, msgs []Message) (string, error) {
		parts := msgs[1].Content.([]ContentPart)
		if len(parts) != 3 || parts[1].ImageURL.spool == nil || parts[2].ImageURL.spool == nil {
			t.Fatalf("图片没有落盘：%+v", parts)
		}
		return `{"slots":[]}`, nil
	})}
	in := json.RawMessage(`{"context":{"today":"2026-10-16"},"image_urls":["/files/a.png","/files/b.png"]}`)
	if _, err := p.Process(context.Background(), "parse_timetable", in); err != nil {
		t.Fatal(err)
	}
	if len(sp.dirs) != 2 || sp.dirs[0] != sp.dirs[1] {
		t.Fatalf("临时目录不对：%v", sp.dirs)
	}
	if _, err := os.Stat(sp.dirs[0]); !os.IsNotExist(err) {
		t.Fatalf("任务结束后临时目录没删：%v", err)
	}
}
