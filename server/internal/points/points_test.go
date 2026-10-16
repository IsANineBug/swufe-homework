package points

import (
	"testing"
	"time"
)

func TestCapDaily(t *testing.T) {
	cases := []struct{ earned, delta, want int }{
		{0, 10, 10}, {25, 10, 5}, {30, 1, 0}, {35, 3, 0}, {29, 1, 1}, {30, -10, -10}, {0, 0, 0},
	}
	for _, c := range cases {
		if got := CapDaily(c.earned, c.delta); got != c.want {
			t.Errorf("CapDaily(%d,%d)=%d want %d", c.earned, c.delta, got, c.want)
		}
	}
}

func TestCapThanks(t *testing.T) {
	if CapThanks(0) != 1 || CapThanks(9) != 1 || CapThanks(10) != 0 || CapThanks(12) != 0 {
		t.Fatal("thanks cap")
	}
}

func TestDeleteThreshold(t *testing.T) {
	cases := map[int]int{0: 3, 1: 3, 10: 3, 30: 3, 31: 4, 40: 4, 41: 5, 55: 6, 60: 6, 61: 6, 500: 6}
	for m, want := range cases {
		if got := DeleteThreshold(m); got != want {
			t.Errorf("DeleteThreshold(%d)=%d want %d", m, got, want)
		}
	}
}

func TestLocalDay(t *testing.T) {
	// 2026-10-16 23:30 +08:00 与 2026-10-16 15:30 UTC 同一天；16:30 UTC 是次日
	a := time.Date(2026, 10, 16, 15, 30, 0, 0, time.UTC)
	b := time.Date(2026, 10, 16, 16, 30, 0, 0, time.UTC)
	if LocalDay(a).Format("2006-01-02") != "2026-10-16" || LocalDay(b).Format("2006-01-02") != "2026-10-17" {
		t.Fatal(LocalDay(a), LocalDay(b))
	}
}
