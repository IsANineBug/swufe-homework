package api

// 游客浏览：不登录也能看一小批随机作业，用来展示 App 能做什么。
// 只读、只给安全字段：不返回用户 id、真实昵称（按每条作业换成「同学A」「同学B」，不同作业之间不能对上同一个人）、
// 附件文件、完成状态、谢谢/投票、举报与管理数据；带附件的来源整条不展示。不提供按 id 取详情的游客接口，避免被逐条遍历。
// 只抽当前未归档学期的作业。服务器内存里缓存一个随机池（guestPoolTTL），每次请求从池里再随机抽，数据库压力与请求量无关。

import (
	"context"
	"fmt"
	"math/rand/v2"
	"net/http"
	"net/netip"
	"sort"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/netx"
)

const (
	// GuestSampleSize 每次随机抽取的作业条数。
	GuestSampleSize = 12
	// GuestPerIPHour 同一 IP（IPv6 按 /64）每小时最多请求次数。
	GuestPerIPHour = 120
	// GuestCampusPerIPHour 校园网（CAMPUS_CIDRS）出口每小时最多请求次数：全校共用少数 NAT 出口，放宽很多；
	// 请求只读内存里的缓存池，不打数据库。
	GuestCampusPerIPHour = 3000
	// guestPoolSize 缓存池大小（每次请求从池里随机取 GuestSampleSize 条，「换一批」才有变化）。
	guestPoolSize = 4 * GuestSampleSize
	// DefaultGuestPoolTTL 缓存池多久重抽一次。
	DefaultGuestPoolTTL = time.Minute
	// guestBodyMax 每条来源正文最多返回的字数（超出截断并加「…」）。
	guestBodyMax = 600
	// guestSourcesMax 每条作业最多返回的来源条数。
	guestSourcesMax = 3
)

type guestSource struct {
	Kind      string  `json:"kind"`
	Author    string  `json:"author"`
	Body      string  `json:"body"`
	StatedDDL *string `json:"stated_ddl"`
	CreatedAt string  `json:"created_at"`
	// AttachmentCount 保留字段兼容旧客户端；带附件的来源不展示，所以恒为 0
	AttachmentCount int `json:"attachment_count"`
}

type guestAssignment struct {
	ID           int64         `json:"id"`
	ClassName    string        `json:"class_name"`
	Title        string        `json:"title"`
	DDL          string        `json:"ddl"`
	DDLUncertain bool          `json:"ddl_uncertain"`
	DDLConflict  bool          `json:"ddl_conflict"`
	SourceCount  int           `json:"source_count"`
	FirstPoster  string        `json:"first_poster"`
	Sources      []guestSource `json:"sources"`

	upcoming bool // 抽池时 DDL 未过
}

// guestPool 游客样例的内存缓存池，并发安全；过期后由第一个请求在锁内重抽（其余请求等它，不会一起打库）。
type guestPool struct {
	mu    sync.Mutex
	items []guestAssignment
	at    time.Time
}

// guestLabels 把作者 id 映射成单条作业内的代号（同学A…同学Z、同学27…）。每条作业各用一张表，
// 同一个人在不同作业里的代号互不相关。
type guestLabels map[int64]string

func (g guestLabels) of(uid int64) string {
	if v, ok := g[uid]; ok {
		return v
	}
	n := len(g)
	var v string
	if n < 26 {
		v = "同学" + string(rune('A'+n))
	} else {
		v = fmt.Sprintf("同学%d", n+1)
	}
	g[uid] = v
	return v
}

func truncRunes(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	return string([]rune(s)[:max]) + "…"
}

// GET /guest/assignments 游客样例：随机抽取未删除、未合并、班级未隐藏、首发者未封号的作业，
// 优先未过期的，不足再用已过期的补齐；结果按 DDL 升序。
func (s *Server) guestAssignments(w http.ResponseWriter, r *http.Request) {
	if s.Cfg.GuestPreviewOff {
		httpx.Fail(w, r, httpx.ErrNotFound)
		return
	}
	ip, _ := netip.ParseAddr(clientIP(r))
	lim := s.GuestLimiter
	if netx.Contains(s.Cfg.CampusCIDRs, ip) {
		lim = s.GuestCampusLimiter
	}
	if !lim.Allow(netx.RateKey(ip)) {
		httpx.Fail(w, r, errRateLimited)
		return
	}
	out, err := s.guestPick(r.Context(), GuestSampleSize)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	httpx.JSON(w, http.StatusOK, out)
}

// guestPick 从缓存池随机取 n 条：先取 DDL 未过的，不够再用已过期的补，结果按 DDL 升序。
func (s *Server) guestPick(ctx context.Context, n int) ([]guestAssignment, error) {
	items, err := s.guestPoolItems(ctx)
	if err != nil {
		return nil, err
	}
	var up, past []guestAssignment
	for _, g := range items {
		if g.upcoming {
			up = append(up, g)
		} else {
			past = append(past, g)
		}
	}
	rand.Shuffle(len(up), func(i, j int) { up[i], up[j] = up[j], up[i] })
	rand.Shuffle(len(past), func(i, j int) { past[i], past[j] = past[j], past[i] })
	out := append(up, past...)
	if len(out) > n {
		out = out[:n]
	}
	if out == nil {
		out = []guestAssignment{}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].DDL < out[j].DDL })
	return out, nil
}

// guestPoolItems 返回缓存池（调用方只读，不改其中的 Sources）。GuestPoolTTL <= 0 时不缓存（测试用）。
func (s *Server) guestPoolItems(ctx context.Context) ([]guestAssignment, error) {
	p := s.guestPool
	p.mu.Lock()
	defer p.mu.Unlock()
	now := s.Now()
	if s.GuestPoolTTL > 0 && p.items != nil && now.Sub(p.at) < s.GuestPoolTTL && !now.Before(p.at) {
		return p.items, nil
	}
	items, err := s.guestSample(ctx, guestPoolSize)
	if err != nil {
		return nil, err
	}
	p.items, p.at = items, now
	return items, nil
}

// guestSample 随机抽取当前未归档学期里未删除、未合并、班级未隐藏、首发者未封号的作业，优先未过期的。
// 来源只给未删除、作者未封号、不带附件的，每条最多 guestSourcesMax 条；一条来源都不能展示的作业不要。
func (s *Server) guestSample(ctx context.Context, n int) ([]guestAssignment, error) {
	now := s.Now()
	rows, err := s.DB.Query(ctx, `SELECT a.id, c.name, a.title, a.ddl, a.ddl_uncertain, a.ddl_conflict, a.created_by,
		(SELECT count(*) FROM assignment_sources x JOIN users xu ON xu.id = x.author_id
			WHERE x.assignment_id = a.id AND x.deleted_at IS NULL AND xu.disabled_at IS NULL)
		FROM assignments a JOIN classes c ON c.id = a.class_id
		JOIN semesters se ON se.id = c.semester_id AND se.is_current AND NOT se.archived
		JOIN users u ON u.id = a.created_by
		WHERE a.deleted_at IS NULL AND a.merged_into IS NULL AND c.hidden_at IS NULL AND u.disabled_at IS NULL
		ORDER BY (a.ddl >= $1) DESC, random() LIMIT $2`, now, n)
	if err != nil {
		return nil, err
	}
	all := []guestAssignment{}
	labels := map[int64]guestLabels{}
	idx := map[int64]int{}
	var ids []int64
	for rows.Next() {
		var g guestAssignment
		var ddl time.Time
		var creator int64
		if err := rows.Scan(&g.ID, &g.ClassName, &g.Title, &ddl, &g.DDLUncertain, &g.DDLConflict, &creator, &g.SourceCount); err != nil {
			rows.Close()
			return nil, err
		}
		g.DDL = httpx.Time(ddl)
		g.upcoming = !ddl.Before(now)
		labels[g.ID] = guestLabels{}
		g.FirstPoster = labels[g.ID].of(creator)
		g.Sources = []guestSource{}
		idx[g.ID] = len(all)
		ids = append(ids, g.ID)
		all = append(all, g)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return all, nil
	}
	rows, err = s.DB.Query(ctx, `SELECT s.assignment_id, s.kind, s.author_id, s.body, s.stated_ddl, s.created_at
		FROM assignment_sources s JOIN users u ON u.id = s.author_id
		WHERE s.assignment_id = ANY($1) AND s.deleted_at IS NULL AND u.disabled_at IS NULL
			AND NOT EXISTS (SELECT 1 FROM attachments t WHERE t.source_id = s.id)
		ORDER BY s.assignment_id, (s.kind = 'first') DESC, s.created_at, s.id`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var aid, author int64
		var src guestSource
		var stated *time.Time
		var created time.Time
		if err := rows.Scan(&aid, &src.Kind, &author, &src.Body, &stated, &created); err != nil {
			return nil, err
		}
		g := &all[idx[aid]]
		if len(g.Sources) >= guestSourcesMax {
			continue
		}
		src.Author = labels[aid].of(author)
		src.Body = truncRunes(src.Body, guestBodyMax)
		src.StatedDDL, src.CreatedAt = httpx.TimePtr(stated), httpx.Time(created)
		g.Sources = append(g.Sources, src)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := all[:0]
	for _, g := range all {
		if len(g.Sources) > 0 {
			out = append(out, g)
		}
	}
	return out, nil
}
