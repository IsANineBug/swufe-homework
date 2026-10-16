package timetable

import "testing"

func TestNormalizeRoom(t *testing.T) {
	cases := map[string]string{
		"@经世楼 Ｇ１０１":    "经世楼G101",
		"经世楼g101":      "经世楼G101",
		" 通博楼　B 2 0 3": "通博楼B203",
		"＠弘远楼A101":     "弘远楼A101",
	}
	for in, want := range cases {
		if got := NormalizeRoom(in); got != want {
			t.Errorf("NormalizeRoom(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSameName(t *testing.T) {
	cases := []struct {
		a      string
		at     bool
		b      string
		bt     bool
		expect bool
	}{
		{"概率论与数理统计", false, "概率论与数理 统计", false, true},
		{"概率论与数理统计", false, "概率论与数理统计（Ａ）", false, false},
		{"大学英语（Ａ）", false, "大学英语(A)", false, true},
		{"概率论与数", true, "概率论与数理统计", false, true},
		{"概率论与数理统计", false, "概率论与数", true, true},
		{"概率论与数", false, "概率论与数理统计", false, false}, // 没标截断不做前缀匹配
		{"高等数学", true, "概率论", false, false},
	}
	for _, c := range cases {
		if got := SameName(c.a, c.at, c.b, c.bt); got != c.expect {
			t.Errorf("SameName(%q,%v,%q,%v) = %v", c.a, c.at, c.b, c.bt, got)
		}
	}
}

func TestStripEllipsis(t *testing.T) {
	for in, want := range map[string]string{"概率论与数…": "概率论与数", "微观经济学...": "微观经济学", "会计学": "会计学"} {
		if got := StripEllipsis(in); got != want {
			t.Errorf("StripEllipsis(%q) = %q", in, got)
		}
	}
}

func TestGroupSameNameAndTruncated(t *testing.T) {
	in, err := Validate([]SlotInput{
		{Name: "概率论与数理统计", Room: "经世楼G101", Weekday: 1, Start: 1, End: 2},
		{Name: "概率论与数…", Room: "经世楼G102", Weekday: 5, Start: 3, End: 4, Truncated: true},
		{Name: "微观经济学", Room: "通博楼B203", Weekday: 2, Start: 5, End: 6},
	})
	if err != nil {
		t.Fatal(err)
	}
	gs := Group(in)
	if len(gs) != 2 {
		t.Fatalf("groups = %d, want 2", len(gs))
	}
	if gs[0].name != "概率论与数理统计" || gs[0].truncated || len(gs[0].slots) != 2 {
		t.Fatalf("group0 = %+v", gs[0])
	}
}

func TestValidate(t *testing.T) {
	bad := []SlotInput{
		{Name: "", Room: "A", Weekday: 1, Start: 1, End: 1},
		{Name: "x", Room: "@ ", Weekday: 1, Start: 1, End: 1},
		{Name: "x", Room: "A", Weekday: 6, Start: 1, End: 1},
		{Name: "x", Room: "A", Weekday: 1, Start: 3, End: 2},
		{Name: "x", Room: "A", Weekday: 1, Start: 1, End: 13},
	}
	for _, s := range bad {
		if _, err := Validate([]SlotInput{s}); err == nil {
			t.Errorf("Validate(%+v) expected error", s)
		}
	}
	if _, err := Validate(nil); err == nil {
		t.Error("empty should fail")
	}
	many := make([]SlotInput, MaxSlots+1)
	for i := range many {
		many[i] = SlotInput{Name: "x", Room: "A", Weekday: 1, Start: 1, End: 1}
	}
	if _, err := Validate(many); err == nil {
		t.Error("over max should fail")
	}
}
