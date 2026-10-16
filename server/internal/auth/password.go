// Package auth 密码哈希（argon2id）、恢复码、邀请码、JWT 与 refresh token。
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"strings"

	"golang.org/x/crypto/argon2"
)

// argon2id 参数：OWASP 建议的 m=19MiB, t=2, p=1，适合 2C2G。
const (
	argonTime    = 2
	argonMemory  = 19 * 1024
	argonThreads = 1
	argonKeyLen  = 32
	argonSaltLen = 16
)

// argonSlots 限制 argon2 并发：每次约占 19MiB，server 容器限 512MB，最多同时算 4 个，其余排队。
var argonSlots = make(chan struct{}, 4)

func argonKey(pw, salt []byte, t, m uint32, p uint8, n uint32) []byte {
	argonSlots <- struct{}{}
	defer func() { <-argonSlots }()
	return argon2.IDKey(pw, salt, t, m, p, n)
}

// HashPassword 返回 PHC 格式：$argon2id$v=19$m=..,t=..,p=..$salt$hash
func HashPassword(pw string) (string, error) {
	salt := make([]byte, argonSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := argonKey([]byte(pw), salt, argonTime, argonMemory, argonThreads, argonKeyLen)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, argonMemory, argonTime, argonThreads,
		base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(key)), nil
}

var errBadHash = errors.New("bad argon2id hash")

// VerifyPassword 常数时间比较。
func VerifyPassword(pw, encoded string) (bool, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, errBadHash
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false, errBadHash
	}
	var m uint32
	var t uint32
	var p uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil {
		return false, errBadHash
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false, errBadHash
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false, errBadHash
	}
	got := argonKey([]byte(pw), salt, t, m, p, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

// 去掉易混字符 0/O、1/I/L
const codeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

func randomCode(n int) string {
	b := make([]byte, n)
	max := big.NewInt(int64(len(codeAlphabet)))
	for i := range b {
		v, err := rand.Int(rand.Reader, max)
		if err != nil {
			panic(err)
		}
		b[i] = codeAlphabet[v.Int64()]
	}
	return string(b)
}

// NewRecoveryCode 形如 XXXX-XXXX-XXXX-XXXX。
func NewRecoveryCode() string {
	s := randomCode(16)
	return s[0:4] + "-" + s[4:8] + "-" + s[8:12] + "-" + s[12:16]
}

// NormalizeRecoveryCode 去掉分隔符和空格、转大写，用于哈希与比较。
func NormalizeRecoveryCode(s string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(s) {
		if r == '-' || r == ' ' || r == '\u3000' {
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

// NewInviteCode 8 位邀请码。
func NewInviteCode() string { return randomCode(8) }

// NormalizeInviteCode 去空白、转大写。
func NormalizeInviteCode(s string) string { return NormalizeRecoveryCode(s) }

// NewRefreshToken 返回明文 token（只给客户端）和 sha256 十六进制（入库）。
func NewRefreshToken() (plain, hash string) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	plain = base64.RawURLEncoding.EncodeToString(b)
	return plain, HashToken(plain)
}

func HashToken(plain string) string {
	sum := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(sum[:])
}
