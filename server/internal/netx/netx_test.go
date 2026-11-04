package netx

import (
	"fmt"
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

func TestLimiterReserveCountsInFlight(t *testing.T) {
	l := NewLimiter(3, time.Hour)
	var dones []func(bool)
	for i := 0; i < 3; i++ {
		d, ok := l.Reserve("k")
		if !ok {
			t.Fatal("should reserve")
		}
		dones = append(dones, d)
	}
	if _, ok := l.Reserve("k"); ok {
		t.Fatal("in-flight reservations must count")
	}
	dones[0](false) // 成功：释放
	d, ok := l.Reserve("k")
	if !ok {
		t.Fatal("released slot should be reusable")
	}
	d(true)
	dones[1](true)
	dones[2](true)
	dones[2](true) // 重复调用无效
	if _, ok := l.Reserve("k"); ok {
		t.Fatal("3 failures recorded, should be blocked")
	}
}

func TestRateKey(t *testing.T) {
	cases := map[string]string{
		"203.0.113.9":                "203.0.113.9",
		"::ffff:203.0.113.9":         "203.0.113.9",
		"2001:db8:1:2:aaaa::1":       "2001:db8:1:2::/64",
		"2001:db8:1:2:ffff:ffff::99": "2001:db8:1:2::/64",
		"2001:db8:1:3::1":            "2001:db8:1:3::/64",
	}
	for in, want := range cases {
		if got := RateKey(netip.MustParseAddr(in)); got != want {
			t.Errorf("RateKey(%s) = %s, want %s", in, got, want)
		}
	}
	if RateKey(netip.Addr{}) != "" {
		t.Error("invalid addr should give empty key")
	}
}

// key 很多时清理最多每 sweepEvery 一次，而不是每次 Allow 都扫全表；过期 key 最终会被清掉。
func TestLimiterSweepThrottled(t *testing.T) {
	l := NewLimiter(5, time.Minute)
	now := time.Unix(0, 0)
	l.now = func() time.Time { return now }
	for i := 0; i <= sweepAbove; i++ {
		l.Allow(fmt.Sprint("k", i))
	}
	// 刚超过阈值时做了一次清理（此时都没过期，什么也没删）
	if l.lastSweep != now || len(l.hits) != sweepAbove+1 {
		t.Fatalf("lastSweep=%v len=%d", l.lastSweep, len(l.hits))
	}
	// 窗口过去后、间隔内：不清理
	now = now.Add(30 * time.Second)
	first := l.lastSweep
	l.Allow("fresh1")
	if l.lastSweep != first {
		t.Fatal("swept again within sweepEvery")
	}
	// 间隔到了：清理掉过期的 key
	now = now.Add(31 * time.Second) // 距上次清理 61s ≥ sweepEvery
	l.Allow("fresh2")
	if l.lastSweep != now {
		t.Fatal("expected a sweep")
	}
	if len(l.hits) != 2 {
		t.Fatalf("expired keys not removed: %d left", len(l.hits))
	}
}
