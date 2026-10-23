// Package files 上传文件的存储路径与 /files 过期签名 URL（issue #4 裁决 4）。
//
// 外部 URL：/files/<相对 UPLOAD_DIR 的路径>?exp=<unix 秒>&sig=<base64url(HMAC-SHA256)>，有效期 1 小时。
// 服务器验签后返回 X-Accel-Redirect: /_protected/<相对路径>，由 nginx 直接从共享卷读文件。
package files

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"
)

type Signer struct {
	secret []byte
	ttl    time.Duration
}

// NewSigner secret 为空时由 fallback（JWT_SECRET）派生，两者互不相同。
func NewSigner(secret, fallback []byte, ttl time.Duration) *Signer {
	if len(secret) == 0 {
		m := hmac.New(sha256.New, fallback)
		m.Write([]byte("swufe-homework/file-url"))
		secret = m.Sum(nil)
	}
	if ttl <= 0 {
		ttl = time.Hour
	}
	return &Signer{secret: secret, ttl: ttl}
}

func (s *Signer) mac(rel string, exp int64) string {
	m := hmac.New(sha256.New, s.secret)
	m.Write([]byte(rel))
	m.Write([]byte{'\n'})
	m.Write([]byte(strconv.FormatInt(exp, 10)))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// URL 生成相对服务器根的签名地址（App 用服务器 origin 拼接）。过期时间取整到 10 分钟，便于 nginx/App 缓存。
func (s *Signer) URL(rel string, now time.Time) string {
	// 向下取整到 10 分钟（issue #8）：同一 10 分钟内生成的链接完全相同，App 图片缓存可命中；
	// 实际有效期在 ttl−10 分钟到 ttl 之间。ttl 不足 20 分钟时不取整，避免链接几乎立即过期。
	exp := now.Add(s.ttl).Unix()
	if s.ttl >= 20*time.Minute {
		exp = now.Add(s.ttl).Truncate(10 * time.Minute).Unix()
	}
	esc := (&url.URL{Path: rel}).EscapedPath()
	return "/files/" + esc + "?exp=" + strconv.FormatInt(exp, 10) + "&sig=" + s.mac(rel, exp)
}

// Verify 校验签名与有效期；rel 为已解码的相对路径。
func (s *Signer) Verify(rel, expStr, sig string, now time.Time) bool {
	if !ValidRel(rel) {
		return false
	}
	exp, err := strconv.ParseInt(expStr, 10, 64)
	if err != nil || now.Unix() > exp || exp > now.Add(s.ttl+time.Minute).Unix() {
		return false
	}
	return hmac.Equal([]byte(sig), []byte(s.mac(rel, exp)))
}

// ValidRel 相对路径只允许 <学期>/<yyyy-mm>/<文件名> 形式，不得含 .. 或绝对路径。
func ValidRel(rel string) bool {
	if rel == "" || strings.HasPrefix(rel, "/") || strings.Contains(rel, "\\") || path.Clean(rel) != rel {
		return false
	}
	for _, p := range strings.Split(rel, "/") {
		if p == "" || p == "." || p == ".." || strings.HasPrefix(p, ".") {
			return false
		}
	}
	return true
}

// MimeByExt /files 响应的 Content-Type（存储时扩展名由服务器决定，只有这几种）。
func MimeByExt(rel string) string {
	switch strings.ToLower(path.Ext(rel)) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".pdf":
		return "application/pdf"
	}
	return "application/octet-stream"
}
