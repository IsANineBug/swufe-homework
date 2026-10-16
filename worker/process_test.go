package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

type fakeImages map[string]string

func (f fakeImages) FetchImage(_ context.Context, link string) (string, error) {
	if d, ok := f[link]; ok {
		return d, nil
	}
	return "", errors.New("no such image")
}

func TestValidateAllKinds(t *testing.T) {
	p := &Processor{}
	cands := map[string]json.RawMessage{"candidates": json.RawMessage(`[{"id":123},{"id":124}]`)}
	cases := []struct {
		kind, out string
		in        map[string]json.RawMessage
		ok        bool
	}{
		{"parse_assignment", `{"title":"第三章习题 3.1–3.8","requirements":"手写拍照，写清步骤","ddl":"2026-10-23T13:55:00+08:00","ddl_source":"下周五上课前","submit_method":"学习通作业","uncertain":[]}`, nil, true},
		{"parse_assignment", `{"title":"x","requirements":null,"ddl":"2026-10-23T13:55:00Z","ddl_source":null,"submit_method":null,"uncertain":[]}`, nil, false},             // 时区不对
		{"parse_assignment", `{"title":"` + strings.Repeat("长", 31) + `","requirements":null,"ddl":null,"ddl_source":null,"submit_method":null,"uncertain":[]}`, nil, false}, // 标题超 30 字
		{"parse_assignment", `{"title":"x","requirements":null,"ddl":null,"ddl_source":null,"submit_method":null,"uncertain":[],"course":"概率论"}`, nil, false},                // 多余字段
		{"parse_timetable", `{"slots":[{"name":"概率论与数理统计B","room":"经世楼G101","weekday":1,"start":6,"end":7,"truncated":false,"start_visible":true},{"name":"形势与政策III","room":null,"weekday":3,"start":4,"end":4,"truncated":true,"start_visible":true}]}`, nil, true},
		{"parse_timetable", `{"slots":[{"name":"a","room":"b","weekday":6,"start":1,"end":2,"truncated":false,"start_visible":true}]}`, nil, false}, // 周六
		{"parse_timetable", `{"slots":[{"name":"a","room":"b","weekday":1,"start":5,"end":2,"truncated":false,"start_visible":true}]}`, nil, false}, // 起 > 止
		{"dedupe_review", `{"matches":[{"id":123,"same":true,"confidence":0.86,"reason":"同为第三章习题，DDL 相同"}]}`, cands, true},
		{"dedupe_review", `{"matches":[{"id":999,"same":true,"confidence":0.86,"reason":"x"}]}`, cands, false}, // 不在候选里
		{"ddl_conflict", `{"conflict":true,"values":["2026-10-23T13:55:00+08:00","2026-10-24T23:59:00+08:00"],"note":"补充里写的是周六晚上"}`, nil, true},
		{"ddl_conflict", `{"conflict":true,"values":["2026-10-23T13:55:00+08:00"],"note":""}`, nil, false},
		{"spam_check", `{"suspicious":false,"category":null,"reason":""}`, nil, true},
		{"spam_check", `{"suspicious":true,"category":"spam","reason":""}`, nil, false},
		{"reminder_text", `{"title":"概率论作业后天截止","body":"第三章习题，周五 13:55 前交到学习通"}`, nil, true},
		{"reminder_text", `{"title":"` + strings.Repeat("催", 41) + `","body":""}`, nil, false},
	}
	for i, c := range cases {
		_, err := p.Validate(c.kind, c.in, c.out)
		if (err == nil) != c.ok {
			t.Errorf("case %d %s: err = %v, want ok=%v", i, c.kind, err, c.ok)
		}
	}
}

func TestNormalization(t *testing.T) {
	p := &Processor{}
	out, err := p.Validate("parse_timetable", nil, `{"slots":[{"name":" 概率论 ","room":"经世楼 @G101","weekday":1,"start":6,"end":7,"truncated":false,"start_visible":true}]}`)
	if err != nil || !strings.Contains(string(out), `"room":"经世楼G101"`) || !strings.Contains(string(out), `"name":"概率论"`) {
		t.Fatalf("out = %s err = %v", out, err)
	}
	out, err = p.Validate("parse_assignment", nil, `{"title":null,"requirements":null,"ddl":null,"ddl_source":null,"submit_method":null,"uncertain":[]}`)
	if err != nil || !strings.Contains(string(out), `"uncertain":["title","ddl"]`) {
		t.Fatalf("out = %s err = %v", out, err)
	}
}

func TestProcessRetriesOnceWithError(t *testing.T) {
	var seen [][]Message
	p := &Processor{LLM: chatFunc(func(_ context.Context, msgs []Message) (string, error) {
		seen = append(seen, msgs)
		if len(seen) == 1 {
			return `{"suspicious":"no"}`, nil
		}
		return `{"suspicious":false,"category":null,"reason":""}`, nil
	})}
	out, err := p.Process(context.Background(), "spam_check", json.RawMessage(`{"title":"a","body":"b"}`))
	if err != nil || string(out) != `{"suspicious":false,"category":null,"reason":""}` {
		t.Fatalf("out = %s err = %v", out, err)
	}
	if len(seen) != 2 || len(seen[1]) != 4 || !strings.Contains(seen[1][3].Content.(string), "不合格") {
		t.Fatalf("retry messages = %v", seen)
	}
	// 两次都失败 → 报错，最多调 2 次
	calls := 0
	p = &Processor{LLM: chatFunc(func(context.Context, []Message) (string, error) { calls++; return "{}", nil })}
	if _, err := p.Process(context.Background(), "spam_check", json.RawMessage(`{}`)); err == nil || calls != 2 {
		t.Fatalf("err = %v calls = %d", err, calls)
	}
}

func TestBuildMessagesWithImages(t *testing.T) {
	p := &Processor{Images: fakeImages{"/files/a.png?sig=1": "data:image/png;base64,AAA", "/files/b.png?sig=2": "data:image/png;base64,BBB"}}
	in := map[string]json.RawMessage{
		"context":    json.RawMessage(`{"today":"2026-10-16","weekday":5}`),
		"image_urls": json.RawMessage(`["/files/a.png?sig=1","/files/b.png?sig=2"]`),
	}
	msgs, err := p.buildMessages(context.Background(), "parse_timetable", in)
	if err != nil {
		t.Fatal(err)
	}
	if msgs[0].Role != "system" || !strings.Contains(msgs[0].Content.(string), "不要猜测作业属于哪门课") {
		t.Fatalf("system = %v", msgs[0])
	}
	parts := msgs[1].Content.([]ContentPart)
	if len(parts) != 3 || parts[1].ImageURL.URL != "data:image/png;base64,AAA" || parts[2].ImageURL.URL != "data:image/png;base64,BBB" {
		t.Fatalf("parts = %+v", parts)
	}
	txt := parts[0].Text
	if !strings.Contains(txt, `"today":"2026-10-16"`) || !strings.Contains(txt, "易校园") || strings.Contains(txt, "/files/") {
		t.Fatalf("text = %s", txt) // 签名链接不发给模型
	}
	in["image_urls"] = json.RawMessage(`["/files/missing.png"]`)
	if _, err := p.buildMessages(context.Background(), "parse_timetable", in); err == nil {
		t.Fatal("missing image accepted")
	}
	if _, err := p.buildMessages(context.Background(), "unknown", in); err == nil {
		t.Fatal("unknown kind accepted")
	}
}

func TestExtractJSON(t *testing.T) {
	for in, want := range map[string]string{
		"```json\n{\"a\":1}\n```": `{"a":1}`,
		"好的：{\"a\":{\"b\":2}} 以上": `{"a":{"b":2}}`,
		"没有":                      "没有",
	} {
		if got := extractJSON(in); got != want {
			t.Errorf("extractJSON(%q) = %q", in, got)
		}
	}
}
