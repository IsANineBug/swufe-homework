// Package testdb 集成测试用数据库。设置 TEST_DATABASE_URL 才运行，否则跳过。
// 注意：会清空该库全部业务表，务必指向专用测试库。
package testdb

import (
	"context"
	"os"
	"sync"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/internal/store"
)

var migrateOnce sync.Once
var migrateErr error

// New 返回已迁移、已清空、带一个当前学期的连接池。
func New(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL 未设置，跳过集成测试")
	}
	migrateOnce.Do(func() { migrateErr = store.Migrate(url) })
	if migrateErr != nil {
		t.Fatalf("migrate: %v", migrateErr)
	}
	ctx := context.Background()
	pool, err := store.Connect(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	_, err = pool.Exec(ctx, `TRUNCATE class_merges, ai_jobs, ai_workers, announcements, points_ledger, thanks, notices,
		post_bans, delete_votes, merge_votes, merge_proposals, completions, attachments,
		assignment_sources, assignments, class_members, class_slots, classes, auth_tokens,
		invite_codes, users, semesters RESTART IDENTITY CASCADE`)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO semesters (name, starts_on, ends_on, is_current)
		VALUES ('2026-2027-1', '2026-09-07', '2027-01-17', true)`); err != nil {
		t.Fatal(err)
	}
	return pool
}

// User 直接插入一个用户，返回 id。
func User(t *testing.T, pool *pgxpool.Pool, nick string) int64 {
	t.Helper()
	var id int64
	err := pool.QueryRow(context.Background(), `INSERT INTO users (nickname, password_hash, recovery_code_hash, registered_via)
		VALUES ($1, 'x', 'x', 'campus') RETURNING id`, nick).Scan(&id)
	if err != nil {
		t.Fatal(err)
	}
	return id
}
