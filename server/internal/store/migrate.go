package store

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/pgx/v5"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/IsANineBug/swufe-homework/server/db"
)

// Migrate 执行 db/migrations 下全部 up 迁移。
func Migrate(databaseURL string) error {
	src, err := iofs.New(db.Migrations, "migrations")
	if err != nil {
		return err
	}
	// golang-migrate 的 pgx v5 驱动使用 pgx5:// 协议名
	u := databaseURL
	for _, p := range []string{"postgres://", "postgresql://"} {
		if strings.HasPrefix(u, p) {
			u = "pgx5://" + strings.TrimPrefix(u, p)
		}
	}
	m, err := migrate.NewWithSourceInstance("iofs", src, u)
	if err != nil {
		return err
	}
	defer m.Close()
	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return err
	}
	return nil
}

// Connect 建连接池，会话时区固定 Asia/Shanghai。
func Connect(ctx context.Context, databaseURL string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, err
	}
	cfg.ConnConfig.RuntimeParams["timezone"] = "Asia/Shanghai"
	if cfg.MaxConns > 20 {
		cfg.MaxConns = 20
	}
	return pgxpool.NewWithConfig(ctx, cfg)
}

// EnsureSemester 库里没有当前学期、且给了 SEMESTER_* 时创建并设为当前学期。
func EnsureSemester(ctx context.Context, pool *pgxpool.Pool, name, startsOn, endsOn string) (bool, error) {
	if name == "" {
		return false, nil
	}
	if startsOn == "" || endsOn == "" {
		return false, fmt.Errorf("SEMESTER_NAME 已设置，但缺少 SEMESTER_STARTS_ON / SEMESTER_ENDS_ON")
	}
	tag, err := pool.Exec(ctx, `INSERT INTO semesters (name, starts_on, ends_on, is_current)
		SELECT $1, $2::date, $3::date, true
		WHERE NOT EXISTS (SELECT 1 FROM semesters WHERE is_current)
		ON CONFLICT (name) DO NOTHING`, name, startsOn, endsOn)
	if err != nil {
		return false, err
	}
	return tag.RowsAffected() == 1, nil
}
