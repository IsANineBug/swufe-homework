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

func TestLoadDefaultsAndSemester(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("JWT_SECRET", "0123456789abcdef0123456789abcdef")
	t.Setenv("HTTP_ADDR", "")
	t.Setenv("TRUSTED_PROXY", "")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.Addr != ":8080" || len(c.TrustedProxies) != 1 || c.TrustedProxies[0].String() != "172.28.0.10/32" {
		t.Fatalf("defaults: %q %v", c.Addr, c.TrustedProxies)
	}
	t.Setenv("HTTP_ADDR", ":9090")
	t.Setenv("SEMESTER_NAME", "2026-2027-1")
	if _, err := Load(); err == nil {
		t.Fatal("partial SEMESTER_* should fail")
	}
	t.Setenv("SEMESTER_STARTS_ON", "2026-09-01")
	t.Setenv("SEMESTER_ENDS_ON", "2027-02-01")
	if c, err = Load(); err != nil || c.Addr != ":9090" || c.SemesterName != "2026-2027-1" {
		t.Fatalf("%+v %v", c, err)
	}
}
