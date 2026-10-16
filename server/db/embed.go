// Package db 内嵌数据库迁移文件。
// db/schema.sql 是结构的「契约」文本；db/migrations/0001_init.up.sql 与其内容一致。
// 之后的表结构改动：新增 000N_xxx.up/down.sql，并同步修改 schema.sql。
package db

import "embed"

//go:embed migrations/*.sql
var Migrations embed.FS
