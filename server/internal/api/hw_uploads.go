package api

// WS2 上传与文件下载。
// 上传：multipart 字段 file，≤10MB，只收 jpeg/png/webp/pdf（按文件头判断，不信扩展名和 Content-Type）；
// 图片生成 400px 宽的 JPEG 缩略图；存 UPLOAD_DIR/<学期>/<yyyy-mm>/<随机名>，目录 0755、文件 0644（nginx uid 101 要能读）。
// 下载：/files/<相对路径>?exp=&sig= 验签后 X-Accel-Redirect 到 /_protected/，由 nginx 读盘。

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"image"
	"image/jpeg"
	_ "image/png" // 解码
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"

	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp" // 解码

	"github.com/IsANineBug/swufe-homework/server/internal/files"
	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

const (
	maxUploadBytes = 10 << 20
	thumbWidth     = 400
	// maxThumbPixels 超过的图片不生成缩略图（避免 2G 内存的机器解码超大图）
	maxThumbPixels = 40_000_000
)

var errTooLarge = httpx.E(http.StatusRequestEntityTooLarge, "too_large", "文件不能超过 10MB")

// thumbSem 同时最多 2 个缩略图解码，控制内存峰值。
var thumbSem = make(chan struct{}, 2)

func sniff(head []byte) (mime, ext, kind string, ok bool) {
	switch {
	case bytes.HasPrefix(head, []byte{0xFF, 0xD8, 0xFF}):
		return "image/jpeg", ".jpg", "image", true
	case bytes.HasPrefix(head, []byte("\x89PNG\r\n\x1a\n")):
		return "image/png", ".png", "image", true
	case len(head) >= 12 && string(head[:4]) == "RIFF" && string(head[8:12]) == "WEBP":
		return "image/webp", ".webp", "image", true
	case bytes.HasPrefix(head, []byte("%PDF-")):
		return "application/pdf", ".pdf", "file", true
	}
	return "", "", "", false
}

func randomName() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// POST /uploads
func (s *Server) upload(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	uid := currentUser(r).ID
	if !s.UploadLimiter.Allow(strconv.FormatInt(uid, 10)) {
		httpx.Fail(w, r, httpx.E(http.StatusTooManyRequests, "rate_limited", "上传太频繁，请稍后再试"))
		return
	}
	sem, err := store.CurrentSemester(ctx, s.DB)
	if errors.Is(err, store.ErrNoSemester) {
		httpx.Fail(w, r, errNoSemester)
		return
	}
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	if sem.Archived {
		httpx.Fail(w, r, errClassArchived)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBytes+64<<10)
	mr, err := r.MultipartReader()
	if err != nil {
		httpx.Fail(w, r, httpx.ErrBadRequest)
		return
	}
	var part io.Reader
	for {
		p, err := mr.NextPart()
		if err != nil {
			var mbe *http.MaxBytesError
			if errors.As(err, &mbe) {
				httpx.Fail(w, r, errTooLarge)
				return
			}
			httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "缺少 file 字段"))
			return
		}
		if p.FormName() == "file" {
			part = p
			break
		}
	}
	data, err := io.ReadAll(io.LimitReader(part, maxUploadBytes+1))
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			httpx.Fail(w, r, errTooLarge)
			return
		}
		httpx.Fail(w, r, httpx.ErrBadRequest)
		return
	}
	if len(data) > maxUploadBytes {
		httpx.Fail(w, r, errTooLarge)
		return
	}
	if len(data) == 0 {
		httpx.Fail(w, r, httpx.E(http.StatusBadRequest, "bad_request", "文件为空"))
		return
	}
	mime, ext, kind, ok := sniff(data[:min(len(data), 16)])
	if !ok {
		httpx.Fail(w, r, httpx.E(http.StatusUnsupportedMediaType, "bad_file_type", "只支持 JPG、PNG、WebP 图片和 PDF"))
		return
	}
	name, err := randomName()
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	dir := path.Join(sem.Name, s.Now().In(httpx.Shanghai).Format("2006-01"))
	if !files.ValidRel(dir) {
		httpx.Fail(w, r, errors.New("bad semester name for upload path"))
		return
	}
	rel := path.Join(dir, name+ext)
	if err := writePublicFile(s.Cfg.UploadDir, rel, data); err != nil {
		httpx.Fail(w, r, err)
		return
	}
	var thumbRel *string
	if kind == "image" {
		if t, err := makeThumb(data); err == nil && t != nil {
			tr := path.Join(dir, name+"_thumb.jpg")
			if err := writePublicFile(s.Cfg.UploadDir, tr, t); err == nil {
				thumbRel = &tr
			}
		}
	}
	var id int64
	err = s.DB.QueryRow(ctx, `INSERT INTO attachments (uploader_id, semester_id, kind, path, thumb_path, bytes, mime)
		VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id`, uid, sem.ID, kind, rel, thumbRel, len(data), mime).Scan(&id)
	if err != nil {
		_ = os.Remove(filepath.Join(s.Cfg.UploadDir, filepath.FromSlash(rel)))
		if thumbRel != nil {
			_ = os.Remove(filepath.Join(s.Cfg.UploadDir, filepath.FromSlash(*thumbRel)))
		}
		httpx.Fail(w, r, err)
		return
	}
	now := s.Now()
	out := attachmentView{ID: id, Kind: kind, URL: s.Files.URL(rel, now), Bytes: len(data)}
	if thumbRel != nil {
		t := s.Files.URL(*thumbRel, now)
		out.ThumbURL = &t
	}
	httpx.JSON(w, http.StatusCreated, out)
}

// writePublicFile 写到 base/rel：目录 0755、文件 0644（不受 umask 影响），先写临时文件再改名。
func writePublicFile(base, rel string, data []byte) error {
	full := filepath.Join(base, filepath.FromSlash(rel))
	dir := filepath.Dir(full)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	// MkdirAll 受 umask 影响，逐级补成 0755（只动 base 以下）
	for d := dir; strings.HasPrefix(d, filepath.Clean(base)+string(filepath.Separator)); d = filepath.Dir(d) {
		_ = os.Chmod(d, 0o755)
	}
	tmp, err := os.CreateTemp(dir, ".up-*")
	if err != nil {
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	if err := os.Chmod(tmp.Name(), 0o644); err != nil {
		os.Remove(tmp.Name())
		return err
	}
	return os.Rename(tmp.Name(), full)
}

// makeThumb 生成宽 400px 的 JPEG 缩略图；本身不超过 400 宽也重新编码（统一格式、去掉 EXIF）。
func makeThumb(data []byte) ([]byte, error) {
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || cfg.Width*cfg.Height > maxThumbPixels {
		return nil, nil
	}
	thumbSem <- struct{}{}
	defer func() { <-thumbSem }()
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	b := src.Bounds()
	tw, th := b.Dx(), b.Dy()
	if tw > thumbWidth {
		th = max(1, th*thumbWidth/tw)
		tw = thumbWidth
	}
	dst := image.NewRGBA(image.Rect(0, 0, tw, th))
	draw.ApproxBiLinear.Scale(dst, dst.Bounds(), src, b, draw.Src, nil)
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 80}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// GET /files/* 验签后交给 nginx（X-Accel-Redirect: /_protected/<相对路径>）。签名错误或过期 403。
func (s *Server) serveFile(w http.ResponseWriter, r *http.Request) {
	rel := strings.TrimPrefix(r.URL.Path, "/files/")
	q := r.URL.Query()
	if !s.Files.Verify(rel, q.Get("exp"), q.Get("sig"), s.Now()) {
		httpx.Fail(w, r, httpx.E(http.StatusForbidden, "forbidden", "链接无效或已过期"))
		return
	}
	w.Header().Set("Content-Type", files.MimeByExt(rel))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if files.MimeByExt(rel) == "application/pdf" {
		w.Header().Set("Content-Disposition", "inline")
	}
	w.Header().Set("X-Accel-Redirect", "/_protected/"+(&url.URL{Path: rel}).EscapedPath())
	w.WriteHeader(http.StatusOK)
}
