package db

import (
	"os"
	"testing"
)

// schema.sql 是契约文本，0001 迁移必须与之一致；后续改动走新的迁移文件。
func TestSchemaMatchesInitialMigration(t *testing.T) {
	want, err := os.ReadFile("schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	got, err := Migrations.ReadFile("migrations/0001_init.up.sql")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(want) {
		t.Fatal("db/migrations/0001_init.up.sql 与 db/schema.sql 不一致")
	}
}
