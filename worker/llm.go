package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// ErrTruncated 模型因输出上限停止（finish_reason=length）。结果不完整，不重试。
var ErrTruncated = errors.New("输出被截断")

// CallOpts 单次调用的参数。
type CallOpts struct {
	Kind      string // 只用于用量日志
	MaxTokens int    // >0 时放进请求体 max_tokens
}

// Message 是 OpenAI 兼容的 chat 消息；Content 为字符串或多模态分段数组。
type Message struct {
	Role    string `json:"role"`
	Content any    `json:"content"`
}

type ContentPart struct {
	Type     string    `json:"type"`
	Text     string    `json:"text,omitempty"`
	ImageURL *ImageURL `json:"image_url,omitempty"`
}

type ImageURL struct {
	URL   string        `json:"url"`
	spool *SpooledImage // 落盘的图片：发请求时流式编码成 data URL，不整张读进内存
}

// LLM 调用 OpenAI 兼容的 /chat/completions（Command Code）。只有这里走 HTTPS_PROXY。
type LLM struct {
	baseURL     string
	apiKey      string
	model       string
	jsonMode    bool
	temperature *float64 // nil = 不传，让模型用默认值
	hc          *http.Client
}

func NewLLM(c *Config) *LLM {
	tr := &http.Transport{Proxy: nil, TLSHandshakeTimeout: 15 * time.Second, IdleConnTimeout: 90 * time.Second}
	if c.Proxy != nil {
		tr.Proxy = http.ProxyURL(c.Proxy)
	}
	return &LLM{baseURL: c.LLMBaseURL, apiKey: c.LLMAPIKey, model: c.LLMModel, jsonMode: c.LLMJSONMode,
		temperature: c.LLMTemperature, hc: &http.Client{Transport: tr}}
}

// Chat 返回第一条回复的文本。超时由 ctx 控制（单任务 90 秒）。
// finish_reason=length 时返回 ErrTruncated。每次调用记一行用量日志（不记图片和正文）。
func (l *LLM) Chat(ctx context.Context, msgs []Message, opt CallOpts) (text string, err error) {
	start := time.Now()
	var usage struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
		Details          *struct {
			ReasoningTokens int `json:"reasoning_tokens"`
		} `json:"completion_tokens_details"`
	}
	finish := ""
	defer func() {
		attrs := []any{"kind", opt.Kind, "ms", time.Since(start).Milliseconds(),
			"prompt_tokens", usage.PromptTokens, "completion_tokens", usage.CompletionTokens, "finish_reason", finish}
		if usage.Details != nil {
			attrs = append(attrs, "reasoning_tokens", usage.Details.ReasoningTokens)
		}
		if err != nil {
			attrs = append(attrs, "err", truncErr(err))
			slog.Warn("模型调用", attrs...)
		} else {
			slog.Info("模型调用", attrs...)
		}
	}()
	body := map[string]any{"model": l.model, "stream": false}
	if opt.MaxTokens > 0 {
		body["max_tokens"] = opt.MaxTokens
	}
	if l.temperature != nil {
		body["temperature"] = *l.temperature
	}
	if l.jsonMode {
		body["response_format"] = map[string]string{"type": "json_object"}
	}
	size, open, err := streamBody(body, msgs)
	if err != nil {
		return "", err
	}
	rc, _ := open()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, l.baseURL+"/chat/completions", rc)
	if err != nil {
		rc.Close()
		return "", err
	}
	req.ContentLength = size
	req.GetBody = open
	req.Header.Set("Authorization", "Bearer "+l.apiKey)
	req.Header.Set("Content-Type", "application/json")
	resp, err := l.hc.Do(req)
	if err != nil {
		return "", fmt.Errorf("调用模型失败：%w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		msg := strings.TrimSpace(string(raw))
		if len(msg) > 200 {
			msg = msg[:200]
		}
		return "", fmt.Errorf("模型返回 HTTP %d：%s", resp.StatusCode, msg)
	}
	var out struct {
		Choices []struct {
			Message struct {
				Content string `json:"content"`
			} `json:"message"`
			FinishReason string `json:"finish_reason"`
		} `json:"choices"`
		Usage json.RawMessage `json:"usage"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("模型响应不是 JSON：%w", err)
	}
	if len(out.Usage) > 0 {
		_ = json.Unmarshal(out.Usage, &usage)
	}
	if len(out.Choices) == 0 {
		return "", fmt.Errorf("模型没有返回内容")
	}
	finish = out.Choices[0].FinishReason
	if finish == "length" {
		return "", ErrTruncated
	}
	return out.Choices[0].Message.Content, nil
}

// truncErr 日志里的错误最多 200 字节，避免把模型回显的大段内容写进日志。
func truncErr(err error) string {
	s := err.Error()
	if len(s) > 200 {
		s = s[:200]
	}
	return s
}

// extractJSON 去掉 ```json 围栏等多余文字，取最外层的 {...}。
func extractJSON(s string) string {
	s = strings.TrimSpace(s)
	i, j := strings.Index(s, "{"), strings.LastIndex(s, "}")
	if i < 0 || j < i {
		return s
	}
	return s[i : j+1]
}
