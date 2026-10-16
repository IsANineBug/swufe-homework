# WS3 交接（AI worker + 服务器 /ai、/worker）

更新：2026-10-16 18:10 +08:00

## 做到哪
- 分支 `ws3-ai-worker`（已变基到 WS2 合并后的 main 95a81c3），PR #7，WS7 首轮「需修改」已处理，等复审。
- 服务器（`server/internal/ai`）：`GET /ai/status`、`POST /ai/jobs`、`GET /ai/jobs/{id}`、`POST /worker/heartbeat`、
  `POST /worker/jobs/claim`（长轮询 25 秒，即时唤醒 + 3 秒兜底，`FOR UPDATE SKIP LOCKED`）、`POST /worker/jobs/{id}/result`。
  离线 409 不排队；2 分钟过期；结束 24 小时后删除；每人进行中 ≤ 3、每小时 ≤ 60（计数与入库同事务 + `pg_advisory_xact_lock`）。
- 裁决 6：App 只传 `attachment_ids`，服务器校验本人 + image + 未清理后用 **WS2 的 `files.Signer`**（`api.Server.Files` 同一实例）签名。
- 触发点（`server/internal/api/ai_hooks.go` 的 `aiAfterWrite`，均在事务提交后、离线时不做事）：
  创建作业 → 提醒文案；改 DDL 或标题 → 提醒文案；新增 / 编辑来源 → DDL 冲突。
- 写回：`reminder_text` 合并写入并刷新 `updated_at`（要求当前 DDL = 任务生成时的 DDL；DDL 变更时 WS2 已清空）；
  `ddl_conflict=true` 只标红并刷新 `updated_at`。
- 迁移 `0004_ai_jobs_links`：`ai_jobs.class_id / assignment_id / worker_id` 和两个索引（`assignments.reminder_text` 用 WS2 0003 的）。
- `WORKER_TOKEN` 仍为 `CHANGE_ME*` 占位时按未设置处理（AI 离线 + 警告），不阻止启动。
- worker（`/worker`，独立 go module）：未改动，见 worker/README.md。

## 下一步
1. WS7 复审；有意见继续在 `ws3-ai-worker` 上改（写入用 git data API，读用 git clone）。
2. **WS5 PR #6**：dedupe_review 须改传 `candidate_ids:[…]`，`draft.ddl` 带 +08:00（WS7 已在 #6 指出）。
3. **作者**：`docs/seed-timetable/` 截图样例 + `*.expected.json`；服务器 `.env` 设 `WORKER_TOKEN`（≥ 24 位）。

## 坑
- `.env.example` 的 `FILE_URL_SECRET=CHANGE_ME` 只有 9 位，WS2 的 config 要求 ≥ 16 位或留空，照抄会启动失败（WS2 的范围，已在 PR 里提）。
- 合并作业（搬来源）后目前不触发 DDL 冲突检查。
- 集成测试共用一个库，本地 `go test -p 1`。
