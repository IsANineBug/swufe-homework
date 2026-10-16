package api_test

import (
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/api"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
)

func TestTitleSimilarity(t *testing.T) {
	if api.TitleSimilarity("第三章课后习题", "第三章 课后习题！") != 1 {
		t.Fatal("punctuation/space should be ignored")
	}
	if s := api.TitleSimilarity("第三章习题", "第四章习题"); s < 0.3 || s >= 1 {
		t.Fatalf("partial = %v", s)
	}
	if s := api.TitleSimilarity("期中论文", "第三章习题"); s != 0 {
		t.Fatalf("unrelated = %v", s)
	}
	if api.TitleSimilarity("ＨＷ３", "hw3") != 1 || api.TitleSimilarity("", "x") != 0 {
		t.Fatal("fullwidth/case")
	}
}

func TestWeekStart(t *testing.T) {
	// 2026-10-18 是周日，2026-10-19 周一
	sun := time.Date(2026, 10, 18, 23, 59, 0, 0, httpx.Shanghai)
	mon := time.Date(2026, 10, 19, 0, 0, 0, 0, httpx.Shanghai)
	if got := api.WeekStart(sun); !got.Equal(time.Date(2026, 10, 12, 0, 0, 0, 0, httpx.Shanghai)) {
		t.Fatalf("sunday -> %v", got)
	}
	if got := api.WeekStart(mon); !got.Equal(mon) {
		t.Fatalf("monday -> %v", got)
	}
	// UTC 周日 16:30 = 上海周一 00:30
	if got := api.WeekStart(time.Date(2026, 10, 18, 16, 30, 0, 0, time.UTC)); !got.Equal(mon) {
		t.Fatalf("utc -> %v", got)
	}
}
