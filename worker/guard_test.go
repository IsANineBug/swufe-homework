package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// WS9 保险：截断不重试、剩余不足 30 秒不重试、Schema 不合格重试 1 次、请求体含 max_tokens、默认不传 temperature。

type recChat struct {
	calls []CallOpts
	reply func(n int) (string, error)
}

func (r *recChat) Chat(_ context.Context, _ []Message, opt CallOpts) (string, error) {
	r.calls = append(r.calls, opt)
	return r.reply(len(r.calls))
}

const goodSpam = `{"suspicious":false,"category":null,"reason":""}`

func TestTruncatedNoRetry(t *testing.T) {
	r := &recChat{reply: func(int) (string, error) { return "", ErrTruncated }}
	p := &Processor{LLM: r}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	_, err := p.Process(ctx, "spam_check", json.RawMessage(`{"title":"a","body":"b"}`))
	if !errors.Is(err, ErrTruncated) || len(r.calls) != 1 {
		t.Fatalf("err = %v calls = %d", err, len(r.calls))
	}
}

func TestTimeoutNoRetry(t *testing.T) {
	r := &recChat{reply: func(int) (string, error) { return "", context.DeadlineExceeded }}
	p := &Processor{LLM: r}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	if _, err := p.Process(ctx, "spam_check", json.RawMessage(`{}`)); err == nil || len(r.calls) != 1 {
		t.Fatalf("err = %v calls = %d", err, len(r.calls))
	}
}

func TestLowTimeNoRetry(t *testing.T) {
	r := &recChat{reply: func(int) (string, error) { return `{"suspicious":"no"}`, nil }}
	p := &Processor{LLM: r}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second) // 剩余 < 30 秒
	defer cancel()
	_, err := p.Process(ctx, "spam_check", json.RawMessage(`{}`))
	if err == nil || !strings.Contains(err.Error(), "Schema") || len(r.calls) != 1 {
		t.Fatalf("err = %v calls = %d", err, len(r.calls))
	}
}

func TestSchemaRetryOnce(t *testing.T) {
	r := &recChat{reply: func(n int) (string, error) {
		if n == 1 {
			return `{"suspicious":"no"}`, nil
		}
		return goodSpam, nil
	}}
	p := &Processor{LLM: r}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	out, err := p.Process(ctx, "spam_check", json.RawMessage(`{}`))
	if err != nil || string(out) != goodSpam || len(r.calls) != 2 {
		t.Fatalf("out = %s err = %v calls = %d", out, err, len(r.calls))
	}
	// 两次都不合格：最多 2 次
	r = &recChat{reply: func(int) (string, error) { return "{}", nil }}
	p = &Processor{LLM: r}
	if _, err := p.Process(ctx, "spam_check", json.RawMessage(`{}`)); err == nil || len(r.calls) != 2 {
		t.Fatalf("err = %v calls = %d", err, len(r.calls))
	}
}

func TestMaxTokensPerKind(t *testing.T) {
	for _, c := range []struct {
		cfg  int
		kind string
		want int
	}{
		{0, "parse_timetable", 32768},
		{0, "parse_assignment", 32768},
		{0, "spam_check", 16384},
		{0, "reminder_text", 16384},
		{0, "ddl_conflict", 16384},
		{0, "dedupe_review", 16384},
		{65536, "parse_timetable", 65536},
		{65536, "dedupe_review", 16384},
		{4096, "parse_assignment", 4096},
		{4096, "spam_check", 4096},
	} {
		p := &Processor{MaxTokens: c.cfg}
		if got := p.maxTokensFor(c.kind); got != c.want {
			t.Errorf("cfg=%d %s: %d, want %d", c.cfg, c.kind, got, c.want)
		}
	}
	// Process 把它传给模型
	r := &recChat{reply: func(int) (string, error) { return goodSpam, nil }}
	p := &Processor{LLM: r, MaxTokens: 32768}
	if _, err := p.Process(context.Background(), "spam_check", json.RawMessage(`{}`)); err != nil {
		t.Fatal(err)
	}
	if r.calls[0].MaxTokens != 16384 || r.calls[0].Kind != "spam_check" {
		t.Fatalf("opts = %+v", r.calls[0])
	}
}

// fakeLLM 假的 OpenAI 兼容接口：记录请求体，按 finish 返回。
func fakeLLM(t *testing.T, finish string, seen *map[string]any) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		*seen = map[string]any{}
		if err := json.Unmarshal(b, seen); err != nil {
			t.Error(err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"{\"a\":1}"},"finish_reason":"`+finish+`"}],
			"usage":{"prompt_tokens":1200,"completion_tokens":800,"completion_tokens_details":{"reasoning_tokens":600}}}`)
	}))
}

func TestLLMRequestBody(t *testing.T) {
	var seen map[string]any
	srv := fakeLLM(t, "stop", &seen)
	defer srv.Close()
	l := NewLLM(&Config{LLMBaseURL: srv.URL, LLMAPIKey: "k", LLMModel: DefaultLLMModel, LLMJSONMode: true})
	text, err := l.Chat(context.Background(), []Message{{Role: "user", Content: "x"}}, CallOpts{Kind: "parse_timetable", MaxTokens: 32768})
	if err != nil || text != `{"a":1}` {
		t.Fatalf("text = %q err = %v", text, err)
	}
	if seen["max_tokens"] != float64(32768) || seen["model"] != "google/gemini-3.8-flash" {
		t.Fatalf("body = %v", seen)
	}
	if _, ok := seen["temperature"]; ok {
		t.Fatalf("temperature sent by default: %v", seen)
	}
	// 配置了 LLM_TEMPERATURE 才传
	tmp := 0.0
	l = NewLLM(&Config{LLMBaseURL: srv.URL, LLMAPIKey: "k", LLMModel: "m", LLMTemperature: &tmp})
	if _, err := l.Chat(context.Background(), nil, CallOpts{}); err != nil {
		t.Fatal(err)
	}
	if v, ok := seen["temperature"]; !ok || v != float64(0) {
		t.Fatalf("temperature = %v", seen)
	}
	if _, ok := seen["max_tokens"]; ok {
		t.Fatalf("max_tokens sent when 0: %v", seen)
	}
}

func TestLLMFinishLength(t *testing.T) {
	var seen map[string]any
	srv := fakeLLM(t, "length", &seen)
	defer srv.Close()
	l := NewLLM(&Config{LLMBaseURL: srv.URL, LLMAPIKey: "k", LLMModel: "m"})
	if _, err := l.Chat(context.Background(), nil, CallOpts{Kind: "parse_timetable", MaxTokens: 4096}); !errors.Is(err, ErrTruncated) {
		t.Fatalf("err = %v", err)
	}
}

func TestMergeSlots(t *testing.T) {
	p := &Processor{}
	// 一个 5–6 节的课块被拆成两段；上下两截重复输出；截断课名与完整课名合并
	out, err := p.Validate("parse_timetable", nil, `{"slots":[
		{"name":"概率论","room":"经世楼G101","weekday":1,"start":5,"end":5,"truncated":false,"start_visible":true},
		{"name":"概率论","room":"经世楼G101","weekday":1,"start":6,"end":6,"truncated":false,"start_visible":true},
		{"name":"大学英","room":null,"weekday":4,"start":3,"end":3,"truncated":true,"start_visible":false},
		{"name":"大学英语","room":"弘远楼201","weekday":4,"start":3,"end":4,"truncated":false,"start_visible":true},
		{"name":"概率论","room":"经世楼G101","weekday":2,"start":5,"end":6,"truncated":false,"start_visible":true},
		{"name":"体育","room":"操场","weekday":1,"start":1,"end":2,"truncated":false,"start_visible":true},
		{"name":"体育","room":"操场","weekday":1,"start":5,"end":6,"truncated":false,"start_visible":true}]}`)
	if err != nil {
		t.Fatal(err)
	}
	var got struct{ Slots []Slot }
	_ = json.Unmarshal(out, &got)
	if len(got.Slots) != 5 {
		t.Fatalf("slots = %s", out)
	}
	a, b := got.Slots[0], got.Slots[1]
	if a.Start != 5 || a.End != 6 || !a.StartVisible {
		t.Fatalf("概率论 = %+v", a)
	}
	if b.Name != "大学英语" || b.Truncated || b.StartVisible || b.Start != 3 || b.End != 4 || b.Room == nil || *b.Room != "弘远楼201" {
		t.Fatalf("大学英语 = %+v", b)
	}
	if got.Slots[3].End != 2 || got.Slots[4].Start != 5 { // 不相接的同名课不合并
		t.Fatalf("体育 = %s", out)
	}
}

func TestTimetablePromptRules(t *testing.T) {
	pr := taskPrompts["parse_timetable"]
	for _, want := range []string{"start_visible=false", "贴着截图最上沿", "不能只给一节", "底边贴着截图最下沿"} {
		if !strings.Contains(pr, want) {
			t.Errorf("prompt lacks %q", want)
		}
	}
}
