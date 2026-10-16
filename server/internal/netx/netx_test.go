package netx

import (
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"
)

func pfx(s string) []netip.Prefix { return []netip.Prefix{netip.MustParsePrefix(s)} }

func TestClientIP(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "172.18.0.5:5555"
	r.Header.Set("X-Real-IP", "10.9.8.7")
	if got := ClientIP(r, pfx("172.16.0.0/12")); got.String() != "10.9.8.7" {
		t.Fatalf("trusted proxy: %s", got)
	}
	if got := ClientIP(r, nil); got.String() != "172.18.0.5" {
		t.Fatalf("untrusted must ignore X-Real-IP: %s", got)
	}
	r.RemoteAddr = "8.8.8.8:1"
	if got := ClientIP(r, pfx("172.16.0.0/12")); got.String() != "8.8.8.8" {
		t.Fatalf("spoofed header from outside: %s", got)
	}
}

func TestLimiter(t *testing.T) {
	l := NewLimiter(3, time.Hour)
	now := time.Unix(0, 0)
	l.now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if !l.Allow("ip") {
			t.Fatal("should allow")
		}
	}
	if l.Allow("ip") {
		t.Fatal("should block 4th")
	}
	if !l.Allow("other") {
		t.Fatal("keys independent")
	}
	now = now.Add(61 * time.Minute)
	if !l.Allow("ip") {
		t.Fatal("window should slide")
	}
}
