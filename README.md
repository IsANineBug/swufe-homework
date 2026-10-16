# swufe-homework

西南财经大学同学共享作业和 DDL 的安卓 App。大家手动上传作业和课表截图，自动按教学班归类，按 DDL 提醒。

## 文档
| 文档 | 内容 |
|---|---|
| [docs/requirements.md](docs/requirements.md) | 需求定稿 |
| [docs/api.yaml](docs/api.yaml) | 接口契约（OpenAPI 3.1） |
| [server/db/schema.sql](server/db/schema.sql) | 数据库结构（PostgreSQL 16） |
| [docs/ai-contract.md](docs/ai-contract.md) | AI 任务的输入输出与提示词 |
| [docs/points-rules.md](docs/points-rules.md) | 积分规则（App 内展示） |
| [docs/audit-log.md](docs/audit-log.md) | 审计记录（WS7 维护） |
| [docs/workstreams.md](docs/workstreams.md) | 6 条开发分工 + 1 条审计的开工指令 |

## 目录
```
/android   Kotlin + Jetpack Compose 客户端（arm64-v8a）
/server    Go 后端
/worker    电脑端 AI 任务拉取程序
/deploy    Docker Compose、Nginx、备份脚本
/docs      文档
```

## 安全
密钥、代理凭据、数据库密码只放 `.env` 或 GitHub Secrets，不要提交到仓库。
