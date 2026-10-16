// Package db 内嵌数据库迁移文件。
// db/schema.sql 是结构的「契约」文本，等于依次执行全部 up 迁移后的结果（embed_test.go 用数据库比对）。
// 之后的表结构改动：新增 000N_xxx.up/down.sql，并同步修改 schema.sql。
package db

import "embed"

//go:embed migrations/*.sql
var Migrations embed.FS
