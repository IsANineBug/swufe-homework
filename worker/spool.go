package main

// 图片落盘 + 流式请求体：图片下载时直接写临时文件，调用模型时边读文件边 base64 编码写进请求体，
// 不把整批图片（或它们的 base64）读进内存。worker 和后端同在 2C2G 服务器上，内存要控制在 64 MB 以内。

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
)

// ImageSpooler 把图片下载到 dir 下的临时文件。实现了它的 ImageFetcher 走落盘 + 流式上传；
// 没实现的（测试里的假图片）退回 FetchImage 返回 data URL。
type ImageSpooler interface {
	SpoolImage(ctx context.Context, link, dir string) (*SpooledImage, error)
}

// SpooledImage 落盘的一张图片。
type SpooledImage struct {
	Path string
	Mime string
	Size int64
}

func (s *SpooledImage) prefix() string { return "data:" + s.Mime + ";base64," }

// encodedLen 写进请求体的 data URL 长度（不含引号）。
func (s *SpooledImage) encodedLen() int64 {
	return int64(len(s.prefix())) + int64(base64.StdEncoding.EncodedLen(int(s.Size)))
}

// imageType 按文件头判断图片类型；不支持返回空串。
func imageType(head []byte) string {
	ct := http.DetectContentType(head)
	if ct == "application/octet-stream" && len(head) > 12 && string(head[0:4]) == "RIFF" && string(head[8:12]) == "WEBP" {
		ct = "image/webp"
	}
	if !allowedImageTypes[ct] {
		return ""
	}
	return ct
}

// spoolBody 把 r 写进 dir 下的临时文件（最多 MaxImageBytes），只用固定大小的缓冲区。
func spoolBody(r io.Reader, dir string) (*SpooledImage, error) {
	head := make([]byte, 512)
	n, err := io.ReadFull(r, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return nil, err
	}
	head = head[:n]
	ct := imageType(head)
	if ct == "" {
		return nil, fmt.Errorf("不支持的图片类型 %s", http.DetectContentType(head))
	}
	f, err := os.CreateTemp(dir, "img-*")
	if err != nil {
		return nil, err
	}
	ok := false
	defer func() {
		if !ok {
			f.Close()
			os.Remove(f.Name())
		}
	}()
	if _, err := f.Write(head); err != nil {
		return nil, err
	}
	rest, err := io.Copy(f, io.LimitReader(r, MaxImageBytes+1-int64(n)))
	if err != nil {
		return nil, err
	}
	size := int64(n) + rest
	if size > MaxImageBytes {
		return nil, fmt.Errorf("图片超过 10MB")
	}
	if err := f.Close(); err != nil {
		return nil, err
	}
	ok = true
	return &SpooledImage{Path: f.Name(), Mime: ct, Size: size}, nil
}

// b64File 边读文件边做 base64 编码。每次读 48 KiB（3 的倍数），只有最后一块会带填充。
type b64File struct {
	path string
	f    *os.File
	buf  []byte
	enc  []byte
	out  []byte
	done bool
}

const b64Chunk = 48 << 10

func (r *b64File) Read(p []byte) (int, error) {
	for len(r.out) == 0 {
		if r.done {
			return 0, io.EOF
		}
		if r.f == nil {
			f, err := os.Open(r.path)
			if err != nil {
				return 0, err
			}
			r.f = f
			r.buf = make([]byte, b64Chunk)
			r.enc = make([]byte, base64.StdEncoding.EncodedLen(b64Chunk))
		}
		n, err := io.ReadFull(r.f, r.buf)
		if n > 0 {
			m := base64.StdEncoding.EncodedLen(n)
			base64.StdEncoding.Encode(r.enc[:m], r.buf[:n])
			r.out = r.enc[:m]
		}
		if err == io.EOF || err == io.ErrUnexpectedEOF {
			r.done = true
			r.Close()
		} else if err != nil {
			r.Close()
			return 0, err
		}
	}
	n := copy(p, r.out)
	r.out = r.out[n:]
	return n, nil
}

func (r *b64File) Close() error {
	if r.f == nil {
		return nil
	}
	err := r.f.Close()
	r.f = nil
	return err
}

// multiCloser 顺序读各段，Close 时关掉还开着的文件（请求中途取消时由 http 传输层调用）。
type multiCloser struct {
	io.Reader
	files []*b64File
}

func (m *multiCloser) Close() error {
	for _, f := range m.files {
		f.Close()
	}
	return nil
}

var spoolNonce = func() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}()

// streamBody 把请求体编码成 JSON：落盘图片先换成占位串，编码后在占位处流式写入 data URL。
// 返回体长度和每次都能重新生成请求体的函数（供 http.Request.GetBody 使用）。
func streamBody(body map[string]any, msgs []Message) (int64, func() (io.ReadCloser, error), error) {
	var imgs []*SpooledImage
	out := make([]Message, len(msgs))
	for i, m := range msgs {
		out[i] = m
		parts, ok := m.Content.([]ContentPart)
		if !ok {
			continue
		}
		cp := make([]ContentPart, len(parts))
		for j, part := range parts {
			cp[j] = part
			if part.ImageURL != nil && part.ImageURL.spool != nil {
				cp[j].ImageURL = &ImageURL{URL: fmt.Sprintf("swufe-img-%s-%d", spoolNonce, len(imgs))}
				imgs = append(imgs, part.ImageURL.spool)
			}
		}
		out[i].Content = cp
	}
	body["messages"] = out
	b, err := json.Marshal(body)
	if err != nil {
		return 0, nil, err
	}
	// 按顺序切开占位串
	var segs [][]byte
	rest := b
	size := int64(0)
	for i, img := range imgs {
		tok := []byte(fmt.Sprintf("swufe-img-%s-%d", spoolNonce, i))
		k := bytes.Index(rest, tok)
		if k < 0 {
			return 0, nil, fmt.Errorf("组装请求体失败")
		}
		segs = append(segs, rest[:k])
		size += int64(k) + img.encodedLen()
		rest = rest[k+len(tok):]
	}
	segs = append(segs, rest)
	size += int64(len(rest))
	open := func() (io.ReadCloser, error) {
		mc := &multiCloser{}
		var rs []io.Reader
		for i, seg := range segs {
			rs = append(rs, bytes.NewReader(seg))
			if i < len(imgs) {
				f := &b64File{path: imgs[i].Path}
				mc.files = append(mc.files, f)
				rs = append(rs, bytes.NewReader([]byte(imgs[i].prefix())), f)
			}
		}
		mc.Reader = io.MultiReader(rs...)
		return mc, nil
	}
	return size, open, nil
}
