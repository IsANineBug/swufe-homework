package config

import "testing"

func TestParseCIDRs(t *testing.T) {
	ps, err := ParseCIDRs(" 10.0.0.0/8, 202.115.112.5 ,,")
	if err != nil || len(ps) != 2 || ps[1].Bits() != 32 {
		t.Fatalf("%v %v", ps, err)
	}
	if ps, err := ParseCIDRs(""); err != nil || len(ps) != 0 {
		t.Fatal("empty")
	}
	if _, err := ParseCIDRs("nope"); err == nil {
		t.Fatal("invalid should fail")
	}
}
