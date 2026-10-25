package httpx

import (
	"encoding/json"
	"testing"
	"time"
)

func TestNullable(t *testing.T) {
	type in struct {
		D Nullable[time.Time] `json:"d"`
	}
	var absent, null, val in
	if err := json.Unmarshal([]byte(`{}`), &absent); err != nil {
		t.Fatal(err)
	}
	if absent.D.Set || absent.D.Value != nil {
		t.Fatalf("absent = %+v", absent.D)
	}
	if err := json.Unmarshal([]byte(`{"d": null}`), &null); err != nil {
		t.Fatal(err)
	}
	if !null.D.Set || null.D.Value != nil {
		t.Fatalf("null = %+v", null.D)
	}
	if err := json.Unmarshal([]byte(`{"d": "2026-10-23T12:00:00+08:00"}`), &val); err != nil {
		t.Fatal(err)
	}
	if !val.D.Set || val.D.Value == nil || val.D.Value.Unix() != time.Date(2026, 10, 23, 4, 0, 0, 0, time.UTC).Unix() {
		t.Fatalf("val = %+v", val.D)
	}
	var bad in
	if err := json.Unmarshal([]byte(`{"d": "不是时间"}`), &bad); err == nil {
		t.Fatal("want error for bad time")
	}
}
