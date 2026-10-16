package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

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
	URL string `json:"url"`
}

// LLM 调用 OpenAI 兼容的 /chat/completions（Command Code 的 DeepSeek）。只有这里走 HTTPS_PROXY。
type LLM struct {
	baseURL  string
	apiKey   string
	model    string
	jsonMode bool
	hc       *http.Client
}

func NewLLM(c *Config) *LLM {
	tr := &http.Transport{Proxy: nil, TLSHandshakeTimeout: 15 * time.Second, IdleConnTimeout: 90 * time.Second}
	if c.Proxy != nil {
		tr.Proxy = http.ProxyURL(c.Proxy)
	}
	return &LLM{baseURL: c.LLMBaseURL, apiKey: c.LLMAPIKey, model: c.LLMModel, jsonMode: c.LLMJSONMode,
		hc: &http.Client{Transport: tr}}
}

// Chat 返回第一条回复的文本。超时由 ctx 控制（单任务 90 秒）。
func (l *LLM) Chat(ctx context.Context, msgs []Message) (string, error) {
	body := map[string]any{"model": l.model, "messages": msgs, "temperature": 0, "stream": false}
	if l.jsonMode {
		body["response_format"] = map[string]string{"type": "json_object"}
	}
	b, err := json.Marshal(body)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, l.baseURL+"/chat/completions", bytes.NewReader(b))
	if err != nil {
		return "", err
	}
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
		} `json:"choices"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("模型响应不是 JSON：%w", err)
	}
	if len(out.Choices) == 0 {
		return "", fmt.Errorf("模型没有返回内容")
	}
	return out.Choices[0].Message.Content, nil
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
