# 审计记录

| 日期 | PR | 结论 | 主要问题 |
|---|---|---|---|
| 2026-10-16 | #1 [WS6] | 需修改 | release.yml 签名/pin 变量名与 WS4 不一致（会发出未签名、无 pin 的 APK）；HTTP_ADDR 与 WS1 的 ADDR 不一致，.env.example 缺 WS1 变量且与 #2 冲突；uploads 卷属主 root，非 root server 无法写入；文档问题见 #4 |
| 2026-10-16 | #2 [WS1] | 需修改 | 截断课名前缀匹配可致不同名班级被自动合并且不可逆（import.go:237）；建议：argon2 并发内存、登录限流可被锁号、notices/invite_codes/auth_tokens 缺索引、契约补错误响应；DB 集成测试审计环境未跑；文档问题见 #4 |
| 2026-10-16 | #3 [WS4] | 需修改 | 签名/pin/地址变量名与 WS6 不一致；pin 为空时静默退回系统 CA（未 fail-closed）；建议：续期网络失败误登出、logout 未带 refresh_token、恢复码确认可被杀进程绕过、缺 pin/续期单测；文档问题见 #4 |
| 2026-10-16 | #1 [WS6] 复审 e2c746a | 需修改 | 上轮阻塞与建议均已落实；新阻塞：uploads-init chmod 750 致 nginx(uid 101) 读不到上传文件，/files 全部 403（deploy/docker-compose.yml:53） |
| 2026-10-16 | #2 [WS1] 复审 0d26930 | 需修改 | 截断课名误合并已修，集成测试全过；新阻塞：退班后再导入仍补时段/改班名/触发合并（import.go:342），裁决 11 未完全落实 |
| 2026-10-16 | #3 [WS4] 复审 1f1124f | 通过 | 变量名与 WS6 一致，release fail-closed；非阻塞：logout 遇续期发旧 token、merged_from/skipped 未用 |
| 2026-10-16 | #1 [WS6] 复审 9a8581e | 通过 | 上传目录改 755，nginx 可读；SEMESTER_* 默认留空、actionlint 已处理；提醒：WS2 须按目录 0755/文件 0644 创建 |
| 2026-10-16 | #2 [WS1] 复审 fe3e71c | 通过 | 退班的班整组跳过、不再改写或合并（裁决 11 落实），集成测试全过；非阻塞：同组其他同名班无提示、Reserve 结算非同一临界区 |
| 2026-10-16 | #5 [WS2] 作业、合并、删除、积分（07f33c4） | 通过 | 无阻塞；建议：签名 URL exp 未按 10 分钟取整（files.go:46）、自动合并班级未刷新 updated_at（import.go:504）、恢复积分与日上限（points.go:73/204）、ai-contract/workstreams 仍写 pg_trgm |
| 2026-10-16 | #6 [WS5] Android 作业、社区、提醒（0d38ebf） | 需修改 | ReminderSync.kt:55/97 本地班级列表过期时误删作业与提醒；DetailViewModel.kt:69/106/113 打开已合并作业仍用旧 id；AppNavHost.kt:129 首启提醒引导系统返回后反复弹出 |
| 2026-10-16 | #7 [WS3] AI worker 与 /ai、/worker（ffaabeb） | 需修改 | 单独对 main 测试全过；与已通过的 #5 合并后冲突：迁移同号 0003 且重复加 reminder_text（0003_ai_jobs_links.up.sql:15）、两套 files 签名不兼容（files.go:51）、写 AI 结果不刷新 updated_at 致 /sync 收不到（jobs.go:526/538）；另 WS5 dedupe_review 须改发 candidate_ids |
| 2026-10-16 | #6 [WS5] 复审 0101e88 | 需修改 | 上轮 4 项已修；新引入：DetailViewModel.kt:49 curId 声明在 init{load()} 之后，load 时为 0，所有详情页显示「已被删除或合并」，onSuccess 未重置 gone |
| 2026-10-16 | #7 [WS3] 复审 ecf6563（变基到 95a81c3） | 通过 | 迁移改 0004、共用 WS2 files.Signer、写回刷新 updated_at 并由 WS2 写路径触发，测试全过；非阻塞：jobs.go:539 DDL 毫秒比较、改标题未清 reminder_text |
| 2026-10-16 | #6 [WS5] 复审 b689e6d | 通过 | curId 移到 init 之前、成功时重置 gone，合并后立即取消旧 id 提醒；非阻塞：清空 stated_ddl 不下发、isMine 按昵称 |
| 2026-10-23 | #11 [WS9] | 需修改 | 第 8 条未做：worker/README.md 仍写电脑开机自启（:55-121）与 12:00–24:00（:123-130），缺服务器 systemd/docker 运行、崩溃重启、≤64MB、服务器代理；:130「电脑未在线」；.env.example:48；模型 id 未经 /models 核对（作者确认） |
| 2026-10-23 | #13 [WS8] | 通过 | 测试全过（PG17）；隐藏班导入时让出时段与 api.yaml「数据全部保留」有出入，待作者裁决；须在 #9 后 rebase |
| 2026-10-23 | #14 [WS10] ① | 通过 | 主题/组件齐全，越界改动已说明；未本地编译（无 Android SDK）；建议 ConfirmDialog 加原因输入槽 |
2026-10-23 | PR #11 (r2) | 通过 | 第 8 条已补；.env.example:3,15 仍写电脑端 worker（非阻塞）
2026-10-23 | PR #16 [WS10②] | 通过 (d6d7e41) | 无阻塞；后台识图/截图需真机验证；ClassBody.kt:204「积分」留给 WS11；2 个待作者裁决点
2026-10-23 | PR #15 [WS11①] | 通过 (0c69d8b) | 无阻塞；清空 stated_ddl 下发 null 但服务器 PATCH /sources 区分不了 null（待 WS8 补）；voted_by_me 待作者裁决
2026-10-23 | PR #15 [WS11①] (r2) | 通过 (8028d7a) | 合并 main 的 merge commit，WS10② 部分未被覆盖，无冲突标记；ClassBody.kt:204「积分」仍待 WS11 第 7 条
