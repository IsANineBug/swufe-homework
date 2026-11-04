// Package netx 客户端 IP、校园网判断与注册限流。
package netx

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// ClientIP 只有当直连地址属于可信代理时才采用 X-Real-IP。
func ClientIP(r *http.Request, trusted []netip.Prefix) netip.Addr {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	remote, err := netip.ParseAddr(host)
	if err != nil {
		return netip.Addr{}
	}
	remote = remote.Unmap()
	if Contains(trusted, remote) {
		if xr := strings.TrimSpace(r.Header.Get("X-Real-IP")); xr != "" {
			if a, err := netip.ParseAddr(xr); err == nil {
				return a.Unmap()
			}
		}
	}
	return remote
}

// RateKey 限流用的 IP key：IPv4 按单个地址；IPv6 按 /64（一个家庭 / 一台主机通常就分到一整个 /64，
// 按单个地址计数等于不限流）。无效地址原样返回空串。
func RateKey(a netip.Addr) string {
	if !a.IsValid() {
		return ""
	}
	a = a.Unmap()
	if a.Is4() {
		return a.String()
	}
	p, _ := a.Prefix(64)
	return p.String()
}

func Contains(prefixes []netip.Prefix, a netip.Addr) bool {
	if !a.IsValid() {
		return false
	}
	for _, p := range prefixes {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// Limiter 是按 key 的滑动窗口计数器（单进程内存实现，2C2G 单实例足够）。
type Limiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	hits   map[string][]time.Time
	flight map[string]int // Reserve 预占、尚未结算的名额
	now    func() time.Time
	// lastSweep 上次清理过期 key 的时间。清理是 O(n)，最多每 sweepEvery 做一次，
	// 避免 key 很多时（如公开的游客接口）每次 Allow 都在锁内扫全表。
	lastSweep time.Time
}

const (
	// sweepAbove key 数超过这个值才考虑清理
	sweepAbove = 10000
	// sweepEvery 两次清理的最小间隔
	sweepEvery = time.Minute
)

func NewLimiter(limit int, window time.Duration) *Limiter {
	return &Limiter{limit: limit, window: window, hits: map[string][]time.Time{}, flight: map[string]int{}, now: time.Now}
}

// Reserve 原子地预占一个名额（已记录次数 + 在途预占 < 上限才成功）。
// 返回的 done(count)：count=true 把预占转成一次记录（如登录失败），false 释放（如登录成功）。
func (l *Limiter) Reserve(key string) (done func(count bool), ok bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	cut := l.now().Add(-l.window)
	n := l.flight[key]
	for _, t := range l.hits[key] {
		if !t.Before(cut) {
			n++
		}
	}
	if n >= l.limit {
		return nil, false
	}
	l.flight[key]++
	var once sync.Once
	return func(count bool) {
		once.Do(func() {
			// 释放预占与记录一次在同一临界区内完成（issue #8），中间不会有别的 Reserve 插进来多拿名额
			l.mu.Lock()
			defer l.mu.Unlock()
			if l.flight[key]--; l.flight[key] <= 0 {
				delete(l.flight, key)
			}
			if count {
				l.allowLocked(key)
			}
		})
	}, true
}

// Blocked 只检查是否已达上限，不记录。
func (l *Limiter) Blocked(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	cut := l.now().Add(-l.window)
	n := 0
	for _, t := range l.hits[key] {
		if !t.Before(cut) {
			n++
		}
	}
	return n >= l.limit
}

// Allow 记录一次并返回是否未超限。
func (l *Limiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.allowLocked(key)
}

// allowLocked 同 Allow，调用方须已持有 l.mu。
func (l *Limiter) allowLocked(key string) bool {
	now := l.now()
	cut := now.Add(-l.window)
	h := l.hits[key]
	i := 0
	for i < len(h) && h[i].Before(cut) {
		i++
	}
	h = h[i:]
	if len(h) >= l.limit {
		l.hits[key] = h
		return false
	}
	l.hits[key] = append(h, now)
	if len(l.hits) > sweepAbove && now.Sub(l.lastSweep) >= sweepEvery { // 防止内存无限增长
		l.lastSweep = now
		for k, v := range l.hits {
			if len(v) == 0 || v[len(v)-1].Before(cut) {
				delete(l.hits, k)
			}
		}
	}
	return true
}
