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
