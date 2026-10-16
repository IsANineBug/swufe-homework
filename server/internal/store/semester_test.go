package store

import (
	"testing"
	"time"
)

func TestSemesterFor(t *testing.T) {
	cases := map[string]string{
		"2026-08-01": "2026-2027-1", // 8 月假期归秋季
		"2026-09-01": "2026-2027-1",
		"2026-10-16": "2026-2027-1",
		"2027-01-31": "2026-2027-1",
		"2027-02-01": "2026-2027-2", // 2 月假期归春季
		"2027-03-01": "2026-2027-2",
		"2027-07-31": "2026-2027-2",
	}
	for d, want := range cases {
		day, _ := time.Parse("2006-01-02", d)
		if got := SemesterFor(day); got.Name != want {
			t.Errorf("%s → %s, want %s", d, got.Name, want)
		}
	}
	s := SemesterFor(time.Date(2026, 10, 16, 0, 0, 0, 0, time.UTC))
	if s.StartsOn.Format("2006-01-02") != "2026-09-01" || s.EndsOn.Format("2006-01-02") != "2027-02-01" {
		t.Fatalf("autumn dates %v %v", s.StartsOn, s.EndsOn)
	}
	s = SemesterFor(time.Date(2027, 4, 1, 0, 0, 0, 0, time.UTC))
	if s.StartsOn.Format("2006-01-02") != "2027-03-01" || s.EndsOn.Format("2006-01-02") != "2027-08-01" {
		t.Fatalf("spring dates %v %v", s.StartsOn, s.EndsOn)
	}
}
