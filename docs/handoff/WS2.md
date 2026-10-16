# WS2 交接（作业、合并、删除、积分）

更新：2026-10-16 17:00 +08:00

## 做到哪
- 分支 `ws2-assignments`，PR `[WS2]` 待 WS7 审计。WS2 指令 1–8 全部实现，另含 issue #4 归 WS2 的：
  裁决 4（`/files` 签名下载）、裁决 10（班级自动合并撤销接口 `/admin/class-merges`）、裁决 15（refresh token 清理定时任务）。
- 迁移 `0003_assignments_ws2`：`assignments.updated_at`（/sync 增量）、`assignments.reminder_text`（WS3 写入）、
  `merge_proposals.snapshot`（撤销合并用）、`attachments.mime`、若干热点索引；`schema.sql` 已同步（embed_test 比对通过）。
- 代码：`internal/api/hw_*.go`（处理器）、`internal/points`（积分规则）、`internal/files`（签名 URL）、`internal/jobs`（定时任务）。
- 测试：`internal/api/hw_test.go`（作业全流程、每日 5 条、积分 24h 结算与每日上限、谢谢、删除投票/禁发/邀请人提醒/恢复、
  合并投票/同意/撤销、上传与 /files 验签、班级合并撤销、公告）、`internal/jobs/jobs_test.go`、`internal/points`、`internal/files` 单测。
  本地 `go test -p 1 ./...`（PostgreSQL 17）与 golangci-lint v1.61 全过。
- 顺手修：`.github/workflows/server.yml` 的 `go test` 加 `-p 1`（多个包共用测试库会互相清表）；
  全仓 `defer tx.Rollback(ctx)` 改成显式忽略返回值（golangci-lint errcheck 之前在 main 上就会报）。

## 下一步
1. 等 WS7 审计，有意见在 `ws2-assignments` 上改。
2. WS3：作业创建 / DDL 变更时写 `assignments.reminder_text`（jsonb `{"2d","1d","12h"}`），写入时同时 `updated_at = now()` 让 /sync 下发；
   改 DDL 时 WS2 已把 reminder_text 清空。parse_assignment 的 `ai_job_id` 目前只接收不落库。
3. WS5：附件 URL 是相对服务器根的 `/files/...`，用服务器 origin 拼接；1 小时过期，过期后重新拉作业详情即可拿到新 URL。
   `/sync` 返回的 `now` 已往前留 30 秒余量，直接作下次 since。

## 未解决的问题和坑
- 标题查重没用 pg_trgm，改在 Go 里算字符二元组 Dice（中文标题在 C locale 下 pg_trgm 不出 trigram），阈值 0.3；api.yaml 已写明。
- 每日 5 条按「首发 + 补充」合计；补充不强制 DDL（作业本身有 DDL）。
- 跨设备「取消完成」不进增量 /sync（没有删除记录），全量同步时纠正；api.yaml 已写明。
- 缩略图不处理 EXIF 旋转；超过 4000 万像素不生成缩略图；同时最多 2 个解码。
- 班级合并撤销后，合并期间在保留班新建的作业留在保留班；合并期间新加入保留班的人不动。
- 作业合并撤销要求目标作业之后没再被合并（409 merge_chain，先撤后一次）。
- 节省 GitHub API：读仓库用 `git clone`，写入用 git data API 一次性建 tree→commit→更新 ref。
