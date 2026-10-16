package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

// Chatter 便于测试替换模型。
type Chatter interface {
	Chat(ctx context.Context, msgs []Message) (string, error)
}

// ImageFetcher 便于测试替换图片下载。
type ImageFetcher interface {
	FetchImage(ctx context.Context, link string) (string, error)
}

// Processor 按 kind 组装提示词 → 调模型 → JSON Schema 校验（失败重试 1 次）。
type Processor struct {
	LLM    Chatter
	Images ImageFetcher

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
			Slots []struct {
				Name         string  `json:"name"`
				Room         *string `json:"room"`
				Weekday      int     `json:"weekday"`
				Start        int     `json:"start"`
				End          int     `json:"end"`
				Truncated    bool    `json:"truncated"`
				StartVisible bool    `json:"start_visible"`
			} `json:"slots"`
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

func mustJSON(v any) []byte {
	b, _ := json.Marshal(v)
	return b
}

// buildMessages 组装：system 骨架 + user（context、任务说明、Schema、输入 JSON、图片）。
func (p *Processor) buildMessages(ctx context.Context, kind string, input map[string]json.RawMessage) ([]Message, error) {
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
	for i, link := range images {
		data, err := p.Images.FetchImage(ctx, link)
		if err != nil {
			return nil, fmt.Errorf("第 %d 张图片：%w", i+1, err)
		}
		parts = append(parts, ContentPart{Type: "image_url", ImageURL: &ImageURL{URL: data}})
	}
	return []Message{{Role: "system", Content: systemPrompt}, {Role: "user", Content: parts}}, nil
}

// Process 执行一个任务：最多调用模型 2 次（首次 + 校验失败重试 1 次）。
func (p *Processor) Process(ctx context.Context, kind string, rawInput json.RawMessage) (json.RawMessage, error) {
	var input map[string]json.RawMessage
	if err := json.Unmarshal(rawInput, &input); err != nil {
		return nil, fmt.Errorf("任务输入不是 JSON 对象")
	}
	msgs, err := p.buildMessages(ctx, kind, input)
	if err != nil {
		return nil, err
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		text, err := p.LLM.Chat(ctx, msgs)
		if err != nil {
			lastErr = err
			if ctx.Err() != nil {
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
