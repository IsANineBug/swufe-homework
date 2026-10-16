package store_test

import (
	"context"
	"testing"
	"time"

	"github.com/IsANineBug/swufe-homework/server/internal/httpx"
	"github.com/IsANineBug/swufe-homework/server/internal/store"
	"github.com/IsANineBug/swufe-homework/server/internal/testdb"
)

func TestEnsureCurrentSemesterSwitches(t *testing.T) {
	pool := testdb.New(t) // 预置 2026-2027-1 为当前学期
	ctx := context.Background()
	// 2026-10-16：仍是 2026-2027-1，不切换
	if sw, err := store.EnsureCurrentSemester(ctx, pool, store.SemesterFor(time.Date(2026, 10, 16, 12, 0, 0, 0, httpx.Shanghai))); err != nil || sw {
		t.Fatalf("switched=%v err=%v", sw, err)
	}
	// 北京时间 2027-02-01 00:30 切到春季，秋季归档
	if sw, err := store.EnsureCurrentSemester(ctx, pool, store.SemesterFor(time.Date(2027, 2, 1, 0, 30, 0, 0, httpx.Shanghai))); err != nil || !sw {
		t.Fatalf("switched=%v err=%v", sw, err)
	}
	cur, err := store.CurrentSemester(ctx, pool)
	if err != nil || cur.Name != "2026-2027-2" || cur.StartsOn != "2027-03-01" || cur.EndsOn != "2027-08-01" {
		t.Fatalf("current = %+v err %v", cur, err)
	}
	var archived bool
	_ = pool.QueryRow(ctx, `SELECT archived FROM semesters WHERE name = '2026-2027-1'`).Scan(&archived)
	if !archived {
		t.Fatal("old semester should be archived")
	}
	// 再调一次幂等
	if sw, _ := store.EnsureCurrentSemester(ctx, pool, store.SemesterFor(time.Date(2027, 2, 2, 0, 0, 0, 0, httpx.Shanghai))); sw {
		t.Fatal("should be idempotent")
	}
}

func TestEnsureCurrentSemesterFromEnvSpec(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	spec := store.SemesterSpec{Name: "2026秋", StartsOn: time.Date(2026, 9, 7, 0, 0, 0, 0, time.UTC), EndsOn: time.Date(2027, 1, 17, 0, 0, 0, 0, time.UTC)}
	if sw, err := store.EnsureCurrentSemester(ctx, pool, spec); err != nil || !sw {
		t.Fatalf("switched=%v err=%v", sw, err)
	}
	cur, err := store.CurrentSemester(ctx, pool)
	if err != nil || cur.Name != "2026秋" || cur.StartsOn != "2026-09-07" {
		t.Fatalf("%+v %v", cur, err)
	}
}

// 来自 SEMESTER_* 的同名学期覆盖按校历推算的日期；校历来源不覆盖已有日期。
func TestEnsureCurrentSemesterExplicitUpdatesDates(t *testing.T) {
	pool := testdb.New(t)
	ctx := context.Background()
	auto := store.SemesterFor(time.Date(2026, 10, 16, 12, 0, 0, 0, httpx.Shanghai)) // 2026-2027-1，testdb 已建（9/7）
	if _, err := store.EnsureCurrentSemester(ctx, pool, auto); err != nil {
		t.Fatal(err)
	}
	if cur, _ := store.CurrentSemester(ctx, pool); cur.StartsOn != "2026-09-07" {
		t.Fatalf("auto must not overwrite: %+v", cur)
	}
	exp := store.SemesterSpec{Name: auto.Name, StartsOn: time.Date(2026, 9, 8, 0, 0, 0, 0, time.UTC), EndsOn: time.Date(2027, 1, 20, 0, 0, 0, 0, time.UTC), Explicit: true}
	if _, err := store.EnsureCurrentSemester(ctx, pool, exp); err != nil {
		t.Fatal(err)
	}
	if cur, _ := store.CurrentSemester(ctx, pool); cur.StartsOn != "2026-09-08" || cur.EndsOn != "2027-01-20" {
		t.Fatalf("explicit dates not applied: %+v", cur)
	}
}
