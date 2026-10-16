package auth

import (
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestPasswordRoundTrip(t *testing.T) {
	h, err := HashPassword("correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$") {
		t.Fatalf("not argon2id: %s", h)
	}
	if ok, _ := VerifyPassword("correct horse", h); !ok {
		t.Fatal("verify failed")
	}
	if ok, _ := VerifyPassword("wrong", h); ok {
		t.Fatal("wrong password accepted")
	}
	if _, err := VerifyPassword("x", "$bcrypt$xx"); err == nil {
		t.Fatal("bad hash should error")
	}
}

func TestRecoveryCodeFormat(t *testing.T) {
	re := regexp.MustCompile(`^[A-HJKMNP-Z2-9]{4}(-[A-HJKMNP-Z2-9]{4}){3}$`)
	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		c := NewRecoveryCode()
		if !re.MatchString(c) {
			t.Fatalf("bad format %q", c)
		}
		seen[c] = true
	}
	if len(seen) < 200 {
		t.Fatal("recovery codes repeat")
	}
	if NormalizeRecoveryCode("ab cd-EFGH") != "ABCDEFGH" {
		t.Fatal("normalize")
	}
	if len(NewInviteCode()) != 8 {
		t.Fatal("invite code length")
	}
}

func TestRefreshTokenHash(t *testing.T) {
	plain, hash := NewRefreshToken()
	if HashToken(plain) != hash || len(hash) != 64 || plain == hash {
		t.Fatal("refresh token hashing")
	}
}

func TestJWT(t *testing.T) {
	iss := NewIssuer([]byte(strings.Repeat("k", 32)), 15*time.Minute)
	tok, err := iss.Sign(42, "admin")
	if err != nil {
		t.Fatal(err)
	}
	id, role, err := iss.Parse(tok)
	if err != nil || id != 42 || role != "admin" {
		t.Fatalf("parse: %d %s %v", id, role, err)
	}
	other := NewIssuer([]byte(strings.Repeat("x", 32)), time.Minute)
	if _, _, err := other.Parse(tok); err == nil {
		t.Fatal("wrong secret accepted")
	}
	iss.now = func() time.Time { return time.Now().Add(16 * time.Minute) }
	if _, _, err := iss.Parse(tok); err == nil {
		t.Fatal("expired token accepted")
	}
}
