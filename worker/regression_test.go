package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"golang.org/x/text/width"
)

// 课表识别回归测试：用 docs/seed-timetable 里的易校园截图调真实模型，与期望值比对。
//
// 样例格式（每组一份期望值，一或两张截图）：
//
//	docs/seed-timetable/<名字>.png            单张
//	docs/seed-timetable/<名字>-1.png、-2.png   上下两截
//	docs/seed-timetable/<名字>.expected.json  {"slots":[{name,room,weekday,start,end,truncated,start_visible}...]}
//
// 默认跳过（要花模型额度）。在有模型 Key 的机器上跑：
//
//	WORKER_REGRESSION=1 WORKER_ENV=./.env go test -run TestTimetableRegression -v .
const seedDir = "../docs/seed-timetable"

type slotT struct {
	Name         string  `json:"name"`
	Room         *string `json:"room"`
	Weekday      int     `json:"weekday"`
	Start        int     `json:"start"`
	End          int     `json:"end"`
	Truncated    bool    `json:"truncated"`
	StartVisible bool    `json:"start_visible"`
}

func normName(s string) string {
	return strings.Join(strings.Fields(width.Narrow.String(width.Fold.String(s))), "")
}

func slotKey(s slotT) string {
	room := ""
	if s.Room != nil {
		room = normName(*s.Room)
	}
	return fmt.Sprintf("%d|%d-%d|%s", s.Weekday, s.Start, s.End, room)
}

// compareSlots 按「星期+节次+教室」配对，再比课名（截断的课名按前缀比）。返回差异描述，空表示一致。
func compareSlots(want, got []slotT) []string {
	var diffs []string
	byKey := map[string][]slotT{}
	for _, g := range got {
		byKey[slotKey(g)] = append(byKey[slotKey(g)], g)
	}
	for _, w := range want {
		k := slotKey(w)
		cands := byKey[k]
		if len(cands) == 0 {
			diffs = append(diffs, fmt.Sprintf("缺少：%s %s", k, w.Name))
			continue
		}
		g := cands[0]
		byKey[k] = cands[1:]
		wn, gn := normName(w.Name), normName(g.Name)
		nameOK := wn == gn || (w.Truncated && strings.HasPrefix(wn, strings.TrimRight(gn, "…."))) ||
			(g.Truncated && strings.HasPrefix(wn, strings.TrimRight(gn, "….")))
		if !nameOK {
			diffs = append(diffs, fmt.Sprintf("课名不同：%s 期望「%s」实际「%s」", k, w.Name, g.Name))
		}
		if w.StartVisible != g.StartVisible {
			diffs = append(diffs, fmt.Sprintf("start_visible 不同：%s", k))
		}
	}
	var extra []string
	for k, rest := range byKey {
		for _, g := range rest {
			extra = append(extra, fmt.Sprintf("多出：%s %s", k, g.Name))
		}
	}
	sort.Strings(extra)
	return append(diffs, extra...)
}

func TestCompareSlots(t *testing.T) {
	r := func(s string) *string { return &s }
	want := []slotT{
		{Name: "概率论与数理统计B", Room: r("经世楼G101"), Weekday: 1, Start: 6, End: 7, StartVisible: true},
		{Name: "形势与政策III", Room: nil, Weekday: 3, Start: 4, End: 4, Truncated: true, StartVisible: true},
	}
	got := []slotT{
		{Name: "概率论与数理统计Ｂ", Room: r("经世楼 G101"), Weekday: 1, Start: 6, End: 7, StartVisible: true},
		{Name: "形势与政...", Room: nil, Weekday: 3, Start: 4, End: 4, Truncated: true, StartVisible: true},
	}
	if d := compareSlots(want, got); len(d) != 0 {
		t.Fatalf("diffs = %v", d)
	}
	got[0].End = 8
	got = append(got, slotT{Name: "体育", Room: r("操场"), Weekday: 2, Start: 3, End: 4, StartVisible: true})
	if d := compareSlots(want, got); len(d) != 3 {
		t.Fatalf("diffs = %v", d)
	}
}

func TestTimetableRegression(t *testing.T) {
	if os.Getenv("WORKER_REGRESSION") != "1" {
		t.Skip("设置 WORKER_REGRESSION=1 才跑（会调用真实模型）")
	}
	expects, _ := filepath.Glob(filepath.Join(seedDir, "*.expected.json"))
	if len(expects) == 0 {
		t.Fatalf("%s 里没有样例（<名字>.png + <名字>.expected.json）", seedDir)
	}
	dotenv := map[string]string{}
	if p := os.Getenv("WORKER_ENV"); p != "" {
		b, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		if dotenv, err = ParseDotEnv(string(b)); err != nil {
			t.Fatal(err)
		}
	}
	// 只用到模型配置；服务器相关的项给占位值通过校验
	dotenv["SERVER_URL"] = "http://127.0.0.1:1/api/v1"
	dotenv["ALLOW_INSECURE_HTTP"] = "1"
	dotenv["WORKER_TOKEN"] = strings.Repeat("x", 24)
	cfg, err := LoadConfig(dotenv, os.LookupEnv)
	if err != nil {
		t.Fatal(err)
	}
	ctxJSON := `{"today":"2026-10-16","weekday":5,"timezone":"Asia/Shanghai","periods":[` +
		`{"no":1,"start":"08:30","end":"09:15"},{"no":2,"start":"09:25","end":"10:10"},{"no":3,"start":"10:20","end":"11:05"},` +
		`{"no":4,"start":"11:15","end":"12:00"},{"no":5,"start":"13:00","end":"13:45"},{"no":6,"start":"13:55","end":"14:40"},` +
		`{"no":7,"start":"14:50","end":"15:35"},{"no":8,"start":"15:45","end":"16:30"},{"no":9,"start":"16:40","end":"17:25"},` +
		`{"no":10,"start":"18:30","end":"19:15"},{"no":11,"start":"19:25","end":"20:10"},{"no":12,"start":"20:20","end":"21:05"}]}`
	for _, exp := range expects {
		name := strings.TrimSuffix(filepath.Base(exp), ".expected.json")
		t.Run(name, func(t *testing.T) {
			var imgs []string
			for _, pat := range []string{name + ".*", name + "-[0-9].*"} {
				m, _ := filepath.Glob(filepath.Join(seedDir, pat))
				for _, f := range m {
					if !strings.HasSuffix(f, ".json") {
						imgs = append(imgs, f)
					}
				}
			}
			sort.Strings(imgs)
			if len(imgs) == 0 || len(imgs) > 2 {
				t.Fatalf("截图应为 1–2 张，找到 %d 张", len(imgs))
			}
			local := fakeImages{}
			var links []string
			for i, f := range imgs {
				b, err := os.ReadFile(f)
				if err != nil {
					t.Fatal(err)
				}
				link := fmt.Sprintf("/files/seed/%d", i)
				local[link] = "data:" + http.DetectContentType(b) + ";base64," + base64.StdEncoding.EncodeToString(b)
				links = append(links, link)
			}
			linksJSON, _ := json.Marshal(links)
			input := json.RawMessage(`{"context":` + ctxJSON + `,"image_urls":` + string(linksJSON) + `}`)
			p := &Processor{LLM: NewLLM(cfg), Images: local}
			ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
			defer cancel()
			out, err := p.Process(ctx, "parse_timetable", input)
			if err != nil {
				t.Fatal(err)
			}
			var got, want struct {
				Slots []slotT `json:"slots"`
			}
			_ = json.Unmarshal(out, &got)
			b, _ := os.ReadFile(exp)
			if err := json.Unmarshal(b, &want); err != nil {
				t.Fatalf("期望值格式不对：%v", err)
			}
			if d := compareSlots(want.Slots, got.Slots); len(d) > 0 {
				t.Errorf("%d 处不一致：\n  %s\n实际输出：%s", len(d), strings.Join(d, "\n  "), out)
			}
		})
	}
}
