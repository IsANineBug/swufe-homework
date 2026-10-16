package db

import (
	"context"
	"os"
	"sort"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// schema.sql 是表结构的契约文本，必须等于依次执行全部 up 迁移后的结果。
// 做法：在同一事务里分别建两个 schema，一个跑迁移、一个跑 schema.sql，比对表、列、约束、索引，最后回滚。
// 需要 TEST_DATABASE_URL；未设置时只检查迁移文件成对存在。
func TestSchemaMatchesMigrations(t *testing.T) {
	ups := upFiles(t)
	if len(ups) == 0 {
		t.Fatal("no migrations")
	}
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL 未设置，跳过 schema.sql 与迁移一致性检查")
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	tx, err := conn.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)

	// 扩展建在 public，两个 schema 共用
	if _, err := tx.Exec(ctx, `CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public; CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA public`); err != nil {
		t.Fatal(err)
	}
	run := func(schema string, sqls ...string) {
		if _, err := tx.Exec(ctx, "CREATE SCHEMA "+schema+"; SET LOCAL search_path = "+schema+", public"); err != nil {
			t.Fatal(err)
		}
		for _, s := range sqls {
			if _, err := tx.Exec(ctx, s); err != nil {
				t.Fatalf("%s: %v", schema, err)
			}
		}
	}
	var mig []string
	for _, f := range ups {
		b, _ := Migrations.ReadFile("migrations/" + f)
		mig = append(mig, string(b))
	}
	run("chk_mig", mig...)
	want, err := os.ReadFile("schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	run("chk_schema", string(want))

	a, b := describe(ctx, t, tx, "chk_mig"), describe(ctx, t, tx, "chk_schema")
	if strings.Join(a, "\n") != strings.Join(b, "\n") {
		t.Fatalf("db/schema.sql 与迁移结果不一致\n迁移:\n%s\n\nschema.sql:\n%s", diff(a, b), diff(b, a))
	}
}

func upFiles(t *testing.T) []string {
	ents, err := Migrations.ReadDir("migrations")
	if err != nil {
		t.Fatal(err)
	}
	var ups []string
	have := map[string]bool{}
	for _, e := range ents {
		have[e.Name()] = true
	}
	for _, e := range ents {
		if n := e.Name(); strings.HasSuffix(n, ".up.sql") {
			if !have[strings.TrimSuffix(n, ".up.sql")+".down.sql"] {
				t.Errorf("%s 缺少 down 文件", n)
			}
			ups = append(ups, n)
		}
	}
	sort.Strings(ups)
	return ups
}

func describe(ctx context.Context, t *testing.T, tx pgx.Tx, schema string) []string {
	rows, err := tx.Query(ctx, `
		SELECT 'col ' || c.relname || '.' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
		       || CASE WHEN a.attnotnull THEN ' notnull' ELSE '' END
		       || coalesce(' default ' || pg_get_expr(d.adbin, d.adrelid), '')
		FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
		LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
		WHERE n.nspname = $1 AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
		UNION ALL
		SELECT 'con ' || c.relname || ' ' || pg_get_constraintdef(k.oid)
		FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname = $1
		UNION ALL
		SELECT 'idx ' || pg_get_indexdef(i.indexrelid)
		FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
		WHERE n.nspname = $1
		UNION ALL
		SELECT 'row periods ' || p::text FROM `+schema+`.periods p`, schema)
	if err != nil {
		t.Fatal(err)
	}
	out, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		t.Fatal(err)
	}
	for i := range out {
		out[i] = strings.ReplaceAll(out[i], schema+".", "")
	}
	sort.Strings(out)
	return out
}

func diff(a, b []string) string {
	in := map[string]bool{}
	for _, s := range b {
		in[s] = true
	}
	var d []string
	for _, s := range a {
		if !in[s] {
			d = append(d, s)
		}
	}
	return strings.Join(d, "\n")
}
