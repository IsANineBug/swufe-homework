package auth

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type Claims struct {
	Role string `json:"role"`
	jwt.RegisteredClaims
}

type Issuer struct {
	secret []byte
	ttl    time.Duration
	now    func() time.Time
}

func NewIssuer(secret []byte, ttl time.Duration) *Issuer {
	return &Issuer{secret: secret, ttl: ttl, now: time.Now}
}

func (i *Issuer) TTL() time.Duration { return i.ttl }

func (i *Issuer) Sign(userID int64, role string) (string, error) {
	now := i.now()
	c := Claims{Role: role, RegisteredClaims: jwt.RegisteredClaims{
		Subject:   strconv.FormatInt(userID, 10),
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(now.Add(i.ttl)),
		Issuer:    "swufe-homework",
	}}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString(i.secret)
}

func (i *Issuer) Parse(tok string) (userID int64, role string, err error) {
	var c Claims
	_, err = jwt.ParseWithClaims(tok, &c, func(t *jwt.Token) (any, error) { return i.secret, nil },
		jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer("swufe-homework"), jwt.WithTimeFunc(i.now))
	if err != nil {
		return 0, "", err
	}
	userID, err = strconv.ParseInt(c.Subject, 10, 64)
	if err != nil {
		return 0, "", errors.New("bad subject")
	}
	return userID, c.Role, nil
}

// User 是已认证请求的身份。
type User struct {
	ID   int64
	Role string
}

func (u User) IsAdmin() bool { return u.Role == "admin" }

type ctxKey struct{}

func WithUser(ctx context.Context, u User) context.Context {
	return context.WithValue(ctx, ctxKey{}, u)
}

func FromContext(ctx context.Context) (User, bool) {
	u, ok := ctx.Value(ctxKey{}).(User)
	return u, ok
}

// BearerToken 取 Authorization: Bearer xxx。
func BearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if len(h) > 7 && strings.EqualFold(h[:7], "bearer ") {
		return strings.TrimSpace(h[7:])
	}
	return ""
}
