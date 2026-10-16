// Package timetable 课表导入与自动归班。
package timetable

import (
	"strings"
	"unicode"
)

// toHalfWidth 全角 ASCII（U+FF01–U+FF5E）转半角，全角空格转半角空格。
func toHalfWidth(r rune) rune {
	switch {
	case r == '\u3000':
		return ' '
	case r >= '\uFF01' && r <= '\uFF5E':
		return r - 0xFEE0
	}
	return r
}

// NormalizeRoom 去掉 @、全角转半角、去掉所有空白、字母转大写。
// 例：「@经世楼 Ｇ１０１」→「经世楼G101」。
func NormalizeRoom(s string) string {
	var b strings.Builder
	for _, r := range s {
		r = toHalfWidth(r)
		if r == '@' || unicode.IsSpace(r) {
			continue
		}
		b.WriteRune(unicode.ToUpper(r))
	}
	return b.String()
}

// CleanName 用于入库展示：全角 ASCII 转半角、首尾去空白、内部连续空白压成一个。
func CleanName(s string) string {
	var b strings.Builder
	for _, r := range s {
		b.WriteRune(toHalfWidth(r))
	}
	return strings.Join(strings.Fields(b.String()), " ")
}

// NameKey 用于比较课名：忽略所有空白、全半角、大小写；中文括号统一为英文括号。
func NameKey(s string) string {
	var b strings.Builder
	for _, r := range s {
		r = toHalfWidth(r)
		if unicode.IsSpace(r) {
			continue
		}
		switch r {
		case '（':
			r = '('
		case '）':
			r = ')'
		}
		b.WriteRune(unicode.ToLower(r))
	}
	return b.String()
}

// SameName 判断两个课名是否指同一门课。
// 截图截断时课名只剩前缀：任一方标记为截断且另一方以它为前缀，也算同名。
func SameName(a string, aTrunc bool, b string, bTrunc bool) bool {
	ka, kb := NameKey(a), NameKey(b)
	if ka == kb {
		return true
	}
	if ka == "" || kb == "" {
		return false
	}
	if aTrunc && strings.HasPrefix(kb, ka) {
		return true
	}
	if bTrunc && strings.HasPrefix(ka, kb) {
		return true
	}
	return false
}

// StripEllipsis 去掉截断课名末尾的省略号（…、...、。。。）。
func StripEllipsis(s string) string {
	s = strings.TrimSpace(s)
	for {
		t := strings.TrimRight(s, "….。 ")
		t = strings.TrimSpace(t)
		if t == s {
			return s
		}
		s = t
	}
}
