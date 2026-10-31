package api

// 游客浏览：不登录也能看一小批随机作业，用来展示 App 能做什么。
// 只读、只给安全字段：不返回用户 id、真实昵称（按本次结果换成「同学A」「同学B」）、附件文件、
// 完成状态、谢谢/投票、举报与管理数据。不提供按 id 取详情的游客接口，避免被逐条遍历。

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"time"
	"unicode/utf8"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
)

const (
	// GuestSampleSize 每次随机抽取的作业条数。
	GuestSampleSize = 12
	// GuestPerIPHour 同一 IP 每小时最多请求次数。
	GuestPerIPHour = 120
	// guestBodyMax 每条来源正文最多返回的字数（超出截断并加「…」）。
	guestBodyMax = 600
	// guestSourcesMax 每条作业最多返回的来源条数。
	guestSourcesMax = 3
)

type guestSource struct {
	Kind            string  `json:"kind"`
	Author          string  `json:"author"`
	Body            string  `json:"body"`
	StatedDDL       *string `json:"stated_ddl"`
	CreatedAt       string  `json:"created_at"`
	AttachmentCount int     `json:"attachment_count"`
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
}

// guestLabels 把作者 id 映射成本次结果内的代号（同学A…同学Z、同学27…），不跨请求保持。
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
	if !s.GuestLimiter.Allow(clientIP(r)) {
		httpx.Fail(w, r, errRateLimited)
		return
	}
	out, err := s.guestSample(r.Context(), GuestSampleSize)
	if err != nil {
		httpx.Fail(w, r, err)
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	httpx.JSON(w, http.StatusOK, out)
}

func (s *Server) guestSample(ctx context.Context, n int) ([]guestAssignment, error) {
	rows, err := s.DB.Query(ctx, `SELECT a.id, c.name, a.title, a.ddl, a.ddl_uncertain, a.ddl_conflict, a.created_by,
		(SELECT count(*) FROM assignment_sources x WHERE x.assignment_id = a.id AND x.deleted_at IS NULL)
		FROM assignments a JOIN classes c ON c.id = a.class_id JOIN users u ON u.id = a.created_by
		WHERE a.deleted_at IS NULL AND a.merged_into IS NULL AND c.hidden_at IS NULL AND u.disabled_at IS NULL
		ORDER BY (a.ddl >= $1) DESC, random() LIMIT $2`, s.Now(), n)
	if err != nil {
		return nil, err
	}
	labels := guestLabels{}
	out := []guestAssignment{}
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
		g.FirstPoster = labels.of(creator)
		g.Sources = []guestSource{}
		idx[g.ID] = len(out)
		ids = append(ids, g.ID)
		out = append(out, g)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err = s.DB.Query(ctx, `SELECT s.assignment_id, s.kind, s.author_id, s.body, s.stated_ddl, s.created_at,
		(SELECT count(*) FROM attachments t WHERE t.source_id = s.id AND t.purged_at IS NULL)
		FROM assignment_sources s JOIN users u ON u.id = s.author_id
		WHERE s.assignment_id = ANY($1) AND s.deleted_at IS NULL AND u.disabled_at IS NULL
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
		if err := rows.Scan(&aid, &src.Kind, &author, &src.Body, &stated, &created, &src.AttachmentCount); err != nil {
			return nil, err
		}
		g := &out[idx[aid]]
		if len(g.Sources) >= guestSourcesMax {
			continue
		}
		src.Author = labels.of(author)
		src.Body = truncRunes(src.Body, guestBodyMax)
		src.StatedDDL, src.CreatedAt = httpx.TimePtr(stated), httpx.Time(created)
		g.Sources = append(g.Sources, src)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].DDL < out[j].DDL })
	return out, nil
}
