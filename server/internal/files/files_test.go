package files

import (
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestSignVerify(t *testing.T) {
	s := NewSigner(nil, []byte(strings.Repeat("j", 40)), time.Hour)
	now := time.Unix(1_800_000_000, 0)
	u, err := url.Parse(s.URL("2026-2027-1/2026-10/abc.jpg", now))
	if err != nil {
		t.Fatal(err)
	}
	rel := strings.TrimPrefix(u.Path, "/files/")
	q := u.Query()
	if !s.Verify(rel, q.Get("exp"), q.Get("sig"), now) {
		t.Fatal("valid url rejected")
	}
	if !s.Verify(rel, q.Get("exp"), q.Get("sig"), now.Add(59*time.Minute)) {
		t.Fatal("should be valid within 1h")
	}
	if s.Verify(rel, q.Get("exp"), q.Get("sig"), now.Add(61*time.Minute)) {
		t.Fatal("expired url accepted")
	}
	if s.Verify("2026-2027-1/2026-10/other.jpg", q.Get("exp"), q.Get("sig"), now) {
		t.Fatal("signature reused for another path")
	}
	exp2 := now.Add(5 * time.Hour).Unix()
	if s.Verify(rel, itoa(exp2), s.mac(rel, exp2), now) {
		t.Fatal("exp beyond ttl accepted")
	}
	other := NewSigner([]byte("another-secret-0123456789"), nil, time.Hour)
	if other.Verify(rel, q.Get("exp"), q.Get("sig"), now) {
		t.Fatal("other secret accepted")
	}
}

// issue #8：同一 10 分钟内生成的链接相同（App 缓存可命中），且不超过 1 小时有效期。
func TestURLStableWithin10Minutes(t *testing.T) {
	s := NewSigner(nil, []byte(strings.Repeat("j", 40)), time.Hour)
	base := time.Unix(1_800_000_000, 0).Truncate(10 * time.Minute)
	a := s.URL("x/y/z.jpg", base.Add(time.Second))
	b := s.URL("x/y/z.jpg", base.Add(9*time.Minute+59*time.Second))
	if a != b {
		t.Fatalf("url changed within 10 minutes:\n%s\n%s", a, b)
	}
	if c := s.URL("x/y/z.jpg", base.Add(10*time.Minute)); c == a {
		t.Fatal("url should roll over at the next 10-minute boundary")
	}
	u, _ := url.Parse(a)
	exp, _ := strconv.ParseInt(u.Query().Get("exp"), 10, 64)
	now := base.Add(time.Second)
	if exp > now.Add(time.Hour).Unix() || exp < now.Add(50*time.Minute).Unix() {
		t.Fatalf("exp %d out of range", exp)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func TestValidRel(t *testing.T) {
	for _, ok := range []string{"a/b/c.jpg", "2026-2027-1/2026-10/x_thumb.jpg"} {
		if !ValidRel(ok) {
			t.Errorf("%q should be valid", ok)
		}
	}
	for _, bad := range []string{"", "/etc/passwd", "../x", "a/../../x", "a//b", "a/./b", "a/.hidden", `a\b`} {
		if ValidRel(bad) {
			t.Errorf("%q should be invalid", bad)
		}
	}
}
