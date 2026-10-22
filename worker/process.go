package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

// Chatter 便于测试替换模型。
type Chatter interface {
	Chat(ctx context.Context, msgs []Message, opt CallOpts) (string, error)
}

// minRetryLeft 剩余时间不足这么多就不再重试（一次识图常要 40–50 秒）。
const minRetryLeft = 30 * time.Second

// ImageFetcher 便于测试替换图片下载。
type ImageFetcher interface {
	FetchImage(ctx context.Context, link string) (string, error)
}

// Processor 按 kind 组装提示词 → 调模型 → JSON Schema 校验（失败重试 1 次）。
type Processor struct {
	LLM       Chatter
	Images    ImageFetcher
	MaxTokens int // LLM_MAX_TOKENS；0 = DefaultMaxTokens

	once    sync.Once
	schemas map[string]*jsonschema.Schema
	initErr error
}

func (p *Processor) compile() {
	p.schemas = map[string]*jsonschema.Schema{}
	c := jsonschema.NewCompiler()
	for kind := range taskPrompts {
		text, err := loadSchemaText(kind)
		if err != nil {
			p.initErr = err
			return
		}
		doc, err := jsonschema.UnmarshalJSON(strings.NewReader(text))
		if err != nil {
			p.initErr = fmt.Errorf("%s schema: %w", kind, err)
			return
		}
		if err := c.AddResource(kind+".json", doc); err != nil {
			p.initErr = err
			return
		}
		s, err := c.Compile(kind + ".json")
		if err != nil {
			p.initErr = fmt.Errorf("%s schema: %w", kind, err)
			return
		}
		p.schemas[kind] = s
	}
}

// Validate 解析模型输出并做 Schema 与语义校验，返回规范化后的 JSON。
func (p *Processor) Validate(kind string, input map[string]json.RawMessage, text string) (json.RawMessage, error) {
	p.once.Do(p.compile)
	if p.initErr != nil {
		return nil, p.initErr
	}
	s := p.schemas[kind]
	if s == nil {
		return nil, fmt.Errorf("未知任务种类 %q", kind)
	}
	raw := extractJSON(text)
	doc, err := jsonschema.UnmarshalJSON(strings.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("输出不是合法 JSON：%v", err)
	}
	if err := s.Validate(doc); err != nil {
		return nil, fmt.Errorf("输出不符合 JSON Schema：%v", err)
	}
	return postCheck(kind, input, []byte(raw))
}

// postCheck Schema 表达不了的约束；顺手做无害的规范化。
func postCheck(kind string, input map[string]json.RawMessage, raw []byte) (json.RawMessage, error) {
	switch kind {
	case "parse_timetable":
		var out struct {
			Slots []Slot `json:"slots"`
		}
		if err := json.Unmarshal(raw, &out); err != nil {
			return nil, err
		}
		for i := range out.Slots {
			sl := &out.Slots[i]
			if sl.Start > sl.End {
				return nil, fmt.Errorf("slots[%d] 起节 %d 大于止节 %d", i, sl.Start, sl.End)
			}
			sl.Name = strings.TrimSpace(sl.Name)
			if sl.Room != nil {
				r := strings.NewReplacer("@", "", " ", "", "\u3000", "").Replace(*sl.Room)
				if r == "" {
					sl.Room = nil
				} else {
					sl.Room = &r
				}
			}
		}
		out.Slots = mergeSlots(out.Slots)
		return json.Marshal(out)
	case "parse_assignment":
		var out map[string]any
		if err := json.Unmarshal(raw, &out); err != nil {
			return nil, err
		}
		unc, _ := out["uncertain"].([]any)
		have := map[string]bool{}
		for _, u := range unc {
			have[u.(string)] = true
		}
		// 标题和 DDL 为空时一定要让 App 标「待确认」
		for _, f := range []string{"title", "ddl"} {
			if out[f] == nil && !have[f] {
				unc = append(unc, f)
			}
		}
		if unc == nil {
			unc = []any{}
		}
		out["uncertain"] = unc
		return json.Marshal(out)
	case "dedupe_review":
		var in struct {
			Candidates []struct {
				ID int64 `json:"id"`
			} `json:"candidates"`
		}
		_ = json.Unmarshal(mustJSON(input), &in)
		ok := map[int64]bool{}
		for _, c := range in.Candidates {
			ok[c.ID] = true
		}
		var out struct {
			Matches []struct {
				ID int64 `json:"id"`
			} `json:"matches"`
		}
		if err := json.Unmarshal(raw, &out); err != nil {
			return nil, err
		}
		seen := map[int64]bool{}
		for _, m := range out.Matches {
			if !ok[m.ID] {
				return nil, fmt.Errorf("matches 里的 id %d 不在 candidates 中", m.ID)
			}
			if seen[m.ID] {
				return nil, fmt.Errorf("matches 里的 id %d 重复", m.ID)
			}
			seen[m.ID] = true
		}
	case "ddl_conflict":
		var out struct {
			Conflict bool     `json:"conflict"`
			Values   []string `json:"values"`
		}
		if err := json.Unmarshal(raw, &out); err != nil {
			return nil, err
		}
		if out.Conflict && len(out.Values) < 2 {
			return nil, errors.New("conflict=true 时 values 至少要有两个不同时间")
		}
	}
	var buf bytes.Buffer
	if err := json.Compact(&buf, raw); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// Slot parse_timetable 输出的一个课块（字段同 schemas/parse_timetable.json）。
type Slot struct {
	Name         string  `json:"name"`
	Room         *string `json:"room"`
	Weekday      int     `json:"weekday"`
	Start        int     `json:"start"`
	End          int     `json:"end"`
	Truncated    bool    `json:"truncated"`
	StartVisible bool    `json:"start_visible"`
}

// mergeSlots 把同一天、同一教室、同一门课里节次相接或重叠的碎片合成一个课块。
// 这对应两种常见错误：上下两截截图拼接时同一课块输出两次；一个跨多行的课块被拆成单节。
// 合并后 start 取最早、end 取最晚；最早那段看不到顶边则 start_visible=false；
// 课名取未截断的那个（截断的课名须是它的前缀才算同一门）。顺序保持首次出现的位置。
func mergeSlots(in []Slot) []Slot {
	sameCourse := func(a, b Slot) (string, bool, bool) {
		switch {
		case a.Name == b.Name:
			return a.Name, a.Truncated && b.Truncated, true
		case a.Truncated && !b.Truncated && strings.HasPrefix(b.Name, a.Name):
			return b.Name, false, true
		case b.Truncated && !a.Truncated && strings.HasPrefix(a.Name, b.Name):
			return a.Name, false, true
		}
		return "", false, false
	}
	sameRoom := func(a, b *string) bool {
		return a == nil || b == nil || *a == *b
	}
	out := make([]Slot, 0, len(in))
	for _, s := range in {
		merged := false
		for i := range out {
			o := &out[i]
			if o.Weekday != s.Weekday || !sameRoom(o.Room, s.Room) || s.Start > o.End+1 || o.Start > s.End+1 {
				continue
			}
			name, trunc, ok := sameCourse(*o, s)
			if !ok {
				continue
			}
			switch {
			case s.Start < o.Start:
				o.StartVisible = s.StartVisible
			case s.Start == o.Start:
				o.StartVisible = o.StartVisible && s.StartVisible
			}
			o.Start, o.End = min(o.Start, s.Start), max(o.End, s.End)
			o.Name, o.Truncated = name, trunc
			if o.Room == nil {
				o.Room = s.Room
			}
			merged = true
			break
		}
		if !merged {
			out = append(out, s)
		}
	}
	return out
}

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

// buildMessages 组装：system 骨架 + user（context、任务说明、Schema、输入 JSON、图片）。
// 图片下载到 dir（落盘，见 spool.go）；dir 由调用方在任务结束后删除。
func (p *Processor) buildMessages(ctx context.Context, kind string, input map[string]json.RawMessage, dir string) ([]Message, error) {
	task, ok := taskPrompts[kind]
	if !ok {
		return nil, fmt.Errorf("未知任务种类 %q", kind)
	}
	schema, err := loadSchemaText(kind)
	if err != nil {
		return nil, err
	}
	var images []string
	if raw, ok := input["image_urls"]; ok {
		if err := json.Unmarshal(raw, &images); err != nil {
			return nil, fmt.Errorf("image_urls 格式不对")
		}
		if len(images) > 6 {
			return nil, fmt.Errorf("图片超过 6 张")
		}
	}
	rest := map[string]json.RawMessage{}
	for k, v := range input {
		if k != "context" && k != "image_urls" {
			rest[k] = v
		}
	}
	var sb strings.Builder
	if c, ok := input["context"]; ok {
		sb.WriteString("context:\n")
		sb.Write(c)
		sb.WriteString("\n\n")
	}
	sb.WriteString(task)
	sb.WriteString("\n\n输出必须符合这个 JSON Schema：\n")
	sb.WriteString(schema)
	if len(rest) > 0 {
		sb.WriteString("\n\n输入：\n")
		sb.Write(mustJSON(rest))
	}
	if len(images) > 0 {
		fmt.Fprintf(&sb, "\n\n附图 %d 张，按顺序排列。", len(images))
	}
	if len(images) == 0 {
		return []Message{{Role: "system", Content: systemPrompt}, {Role: "user", Content: sb.String()}}, nil
	}
	parts := []ContentPart{{Type: "text", Text: sb.String()}}
	spooler, _ := p.Images.(ImageSpooler)
	for i, link := range images {
		// 一张张下载：落盘的只占固定大小的缓冲区
		if spooler != nil && dir != "" {
			img, err := spooler.SpoolImage(ctx, link, dir)
			if err != nil {
				return nil, fmt.Errorf("第 %d 张图片：%w", i+1, err)
			}
			parts = append(parts, ContentPart{Type: "image_url", ImageURL: &ImageURL{spool: img}})
			continue
		}
		data, err := p.Images.FetchImage(ctx, link)
		if err != nil {
			return nil, fmt.Errorf("第 %d 张图片：%w", i+1, err)
		}
		parts = append(parts, ContentPart{Type: "image_url", ImageURL: &ImageURL{URL: data}})
	}
	return []Message{{Role: "system", Content: systemPrompt}, {Role: "user", Content: parts}}, nil
}

// maxTokensFor 识图/解析作业用满 LLM_MAX_TOKENS，其余文字任务取 min(LLM_MAX_TOKENS, 16384)。
func (p *Processor) maxTokensFor(kind string) int {
	n := p.MaxTokens
	if n <= 0 {
		n = DefaultMaxTokens
	}
	switch kind {
	case "parse_timetable", "parse_assignment":
		return n
	}
	return min(n, TextTaskMaxCap)
}

// canRetry 剩余时间够（≥30 秒，或 ctx 没有截止时间）才重试。
func canRetry(ctx context.Context) bool {
	if ctx.Err() != nil {
		return false
	}
	dl, ok := ctx.Deadline()
	return !ok || time.Until(dl) >= minRetryLeft
}

// Process 执行一个任务：最多调用模型 2 次（首次 + 重试 1 次）。
// 不重试的情况：输出被截断（finish_reason=length）、调用超时、剩余时间不足 30 秒。
func (p *Processor) Process(ctx context.Context, kind string, rawInput json.RawMessage) (json.RawMessage, error) {
	var input map[string]json.RawMessage
	if err := json.Unmarshal(rawInput, &input); err != nil {
		return nil, fmt.Errorf("任务输入不是 JSON 对象")
	}
	dir := ""
	if _, ok := input["image_urls"]; ok {
		if _, ok := p.Images.(ImageSpooler); ok {
			d, err := os.MkdirTemp("", "swufe-job-*")
			if err != nil {
				return nil, err
			}
			defer os.RemoveAll(d)
			dir = d
		}
	}
	msgs, err := p.buildMessages(ctx, kind, input, dir)
	if err != nil {
		return nil, err
	}
	opt := CallOpts{Kind: kind, MaxTokens: p.maxTokensFor(kind)}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		if attempt > 0 && !canRetry(ctx) {
			break
		}
		text, err := p.LLM.Chat(ctx, msgs, opt)
		if err != nil {
			lastErr = err
			if errors.Is(err, ErrTruncated) || errors.Is(err, context.DeadlineExceeded) || ctx.Err() != nil {
				break
			}
			continue
		}
		out, err := p.Validate(kind, input, text)
		if err == nil {
			return out, nil
		}
		lastErr = err
		// 把错误告诉模型再试一次
		msgs = append(msgs,
			Message{Role: "assistant", Content: text},
			Message{Role: "user", Content: "上面的输出不合格：" + err.Error() + "\n请只输出修正后的单个 JSON 对象。"})
	}
	return nil, lastErr
}
