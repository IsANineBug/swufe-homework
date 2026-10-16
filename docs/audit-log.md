# 审计记录

| 日期 | PR | 结论 | 主要问题 |
|---|---|---|---|
| 2026-10-16 | #1 [WS6] | 需修改 | release.yml 签名/pin 变量名与 WS4 不一致（会发出未签名、无 pin 的 APK）；HTTP_ADDR 与 WS1 的 ADDR 不一致，.env.example 缺 WS1 变量且与 #2 冲突；uploads 卷属主 root，非 root server 无法写入；文档问题见 #4 |
| 2026-10-16 | #2 [WS1] | 需修改 | 截断课名前缀匹配可致不同名班级被自动合并且不可逆（import.go:237）；建议：argon2 并发内存、登录限流可被锁号、notices/invite_codes/auth_tokens 缺索引、契约补错误响应；DB 集成测试审计环境未跑；文档问题见 #4 |
| 2026-10-16 | #3 [WS4] | 需修改 | 签名/pin/地址变量名与 WS6 不一致；pin 为空时静默退回系统 CA（未 fail-closed）；建议：续期网络失败误登出、logout 未带 refresh_token、恢复码确认可被杀进程绕过、缺 pin/续期单测；文档问题见 #4 |
