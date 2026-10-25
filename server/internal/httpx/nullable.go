package httpx

import (
	"bytes"
	"encoding/json"
)

// Nullable 区分请求体里「没给该字段」「显式 null」「给了值」三种情况，用于 PATCH 的部分更新。
// 字段须用值类型（不要用 *Nullable）：encoding/json 只在字段出现时调用 UnmarshalJSON（含 null），
// 没出现时保持零值 Set=false。
type Nullable[T any] struct {
	Set   bool // 请求体里出现了该字段（值或 null）
	Value *T   // Set 且非 null 时为给出的值；显式 null 时为 nil
}

func (n *Nullable[T]) UnmarshalJSON(b []byte) error {
	n.Set = true
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		n.Value = nil
		return nil
	}
	var v T
	if err := json.Unmarshal(b, &v); err != nil {
		return err
	}
	n.Value = &v
	return nil
}

// Ptr 返回给出的值，没给或 null 都是 nil。
func (n Nullable[T]) Ptr() *T { return n.Value }
