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
