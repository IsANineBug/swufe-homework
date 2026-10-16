# 分工与开工指令

6 条开发线并行开发，各自在独立分支提 PR 到 `main`；另有 1 条审计线（WS7）只审不写功能代码。每个 PR 须经 WS7 审计通过后才能合并。所有人以这四份文档为准，冲突时先改文档：
- `docs/requirements.md` 需求定稿
- `docs/api.yaml` 接口契约（OpenAPI 3.1）
- `server/db/schema.sql` 数据库结构
- `docs/ai-contract.md` AI 输入输出
- `docs/points-rules.md` 积分规则

通用约定：
- 仓库 `IsANineBug/swufe-homework`，目录 `/android`、`/server`、`/worker`、`/deploy`、`/docs`。
- 分支名 `ws<编号>-<简述>`，PR 标题前缀 `[WS<编号>]`。
- 密钥、代理、数据库密码只放 `.env`（参考 `.env.example`）或 GitHub Secrets，**不得提交**。
- 时间统一 Asia/Shanghai；接口时间带 `+08:00`。
- 全局角色叫「管理员」，代码里是 `role=admin`，任何界面和文案都不出现「超管」。
- 需要改接口或表结构时：在 PR 里同时改 `api.yaml` / `schema.sql`，并 @ 受影响的工作线。

---

## WS1 后端：账号与班级
```
你负责 swufe-homework 的 Go 后端基础和「账号与班级」模块，代码放 /server。
先读 docs/requirements.md、docs/api.yaml、server/db/schema.sql。

1. 搭骨架：Go 1.23、chi 路由、pgx/v5、sqlc 或手写 SQL、golang-migrate（把 schema.sql 拆成 0001 迁移）、
   slog 结构化日志、统一错误格式 {code,message}、/healthz。配置全部从环境变量读。
2. 认证：argon2id 存密码；access token（JWT，15 分钟）+ refresh token（30 天，库里存 sha256）；
   恢复码注册时生成一次（XXXX-XXXX-XXXX-XXXX），/auth/recover 重设密码并换新恢复码。
3. 注册：邀请码或校园网 IP 满足其一。校园网判断读 CAMPUS_CIDRS（逗号分隔 CIDR，可为空），
   取 Nginx 传来的 X-Real-IP（只信任 TRUSTED_PROXY）。校园网注册 inviter_id = NULL。
   每人邀请名额 5（含未使用的码）。首个用户可用 BOOTSTRAP_ADMIN_TOKEN 注册为管理员。
   同一 IP 注册做宽松限流（校园网 NAT 共用出口），如每 IP 每小时 30 次。
4. /meta、/me、/me/invites、/me/notices。
5. 课表导入 /timetable/import 的归班算法，严格按 api.yaml 描述：
   本次同名合一班 → 任一时段（教室+星期+节次重叠）命中则加入并补全时段 → 都不命中新建 →
   命中两个班自动合并（成员、时段、作业归到较早的班，旧班 merged_into）→ 时段重叠但课名不同返回 conflicts。
   全程一个事务，依赖 class_slots 的 EXCLUDE 约束防并发重复。
   教室名规范化（去 @、全半角、空格），课名比较忽略空格和全半角。
6. /classes/search（pg_trgm 前缀/相似度）、/classes/{id}、/me/classes、DELETE /me/classes/{id}。
7. 单测覆盖：归班算法（含概率论周一+周五、同教室周二另一班、截图裁掉一段、两班自动合并）、注册两条路径。
交付：可 docker build 的 /server，README 写清本地启动方式。完成后在 PR 描述里列出已实现的接口。
```

## WS2 后端：作业、合并、删除、积分
```
你负责 /server 的作业与社区模块，在 WS1 的骨架上开发（先等 WS1 合并骨架 PR，或基于其分支）。
先读 docs/requirements.md、docs/api.yaml、server/db/schema.sql、docs/points-rules.md。

1. 作业：/classes/{id}/assignments（GET 按日期、POST 首发）、candidates 查重（近 7 天、DDL ±1 天、
   pg_trgm similarity 排序取 3）、/assignments/{id}（GET/PATCH）、来源补充/编辑/删除、/feed、/sync 增量同步。
   校验：必须是班级成员；禁发期 403；每人每班每天 5 条（按 Asia/Shanghai 日期，被删除的不计）；有图片则 DDL 必填。
   补充的 stated_ddl 与作业 DDL 不同 → ddl_conflict = true。
2. 上传：/uploads 收 multipart，限 10MB，只接受 jpeg/png/webp/pdf；图片生成 400px 缩略图；
   文件存 UPLOAD_DIR/<semester>/<yyyy-mm>/<uuid>；URL 用带过期签名的 /files/...，由 Nginx 内部转发。
3. 完成状态 PUT/DELETE /assignments/{id}/done，只本人可见，任何接口都不返回他人完成情况。
4. 合并：提议、被合并作业首发者 accept 立即执行、或 3 名班级成员赞同执行。
   执行 = 来源 assignment_id 改为 to（保留 original_assignment_id）、from.merged_into = to、
   完成状态取并集、DDL 不一致标红、积分按 points-rules 调整、写 notices。管理员可 revert。提议 7 天未成则 expired。
5. 删除投票：门槛 clamp(ceil(成员数×0.1),3,6)；达到即软删除来源（作业无剩余来源时作业也软删除）；
   收回积分并 -10；30 天内第 3 次 → post_bans 7 天；作者是邀请注册的，给直接邀请人写 notice。
6. 谢谢、积分流水、每日 30 分上限、24h 结算定时任务、周榜/学期榜/课代表、/me/points。
7. 管理员接口：restore（返还积分、可解除禁发）、merge revert、/admin/deleted、/admin/announcements（生效≥7天后）。
8. 定时任务（同进程 cron）：积分结算（每 10 分钟）、合并提议过期、图片清理（只保留当前学期和上一学期，标记 purged_at 并删文件）。
单测重点：积分边界（每日上限、谢谢上限 10、删除后恢复）、合并后撤销能还原、删除门槛计算。
```

## WS3 AI worker（你电脑上运行）
```
你负责 /worker：运行在作者个人电脑上的 AI 任务拉取程序，以及服务器侧 /ai 和 /worker 接口（与 WS1 协调放在 /server/internal/ai）。
先读 docs/ai-contract.md、docs/api.yaml 的 ai/worker 部分。

1. worker 用 Go 写成单个可执行文件（Windows/macOS/Linux 都能跑），配置从同目录 .env 读：
   SERVER_URL、WORKER_TOKEN、SERVER_CERT_PIN（自签证书公钥 sha256）、LLM_BASE_URL、LLM_API_KEY、LLM_MODEL
   （默认 DeepSeek V4.1 Flash，经 Command Code）、HTTPS_PROXY（用户代理）。
2. 每 20 秒心跳；长轮询 /worker/jobs/claim；按 kind 组装提示词（骨架在 ai-contract.md），图片用服务器签名 URL 下载后以 base64 传给多模态接口。
3. 输出用 JSON Schema 严格校验，失败重试 1 次；仍失败上报 failed。单任务超时 90 秒。
4. 服务器侧：/ai/status（心跳 60 秒内算在线）、/ai/jobs（离线 409 ai_offline，不排队；2 分钟过期）、
   /ai/jobs/{id}、worker 三个接口；context 由服务器拼（today、periods、班级时段）。
   reminder_text 在作业创建/DDL 变更且 worker 在线时由服务器自动发起，结果写回作业供 /sync 下发。
5. 提供 worker/README.md：如何在电脑上设置开机自启（Windows 任务计划程序 / macOS launchd），以及如何只在 12:00–24:00 运行也不影响服务。
6. 用 docs/ 里的易校园截图样例（seed-timetable）写课表识别的回归测试：给定截图，输出 slots 与期望值比对。
不要在任何地方提交密钥或代理地址。
```

## WS4 Android：基础、账号、课表
```
你负责 /android 的工程骨架和「账号 + 课表」功能。Kotlin + Jetpack Compose + Material 3，minSdk 26，只打 arm64-v8a。
先读 docs/requirements.md、docs/api.yaml。

1. 骨架：单 Activity + Navigation Compose、Hilt、Retrofit + OkHttp + kotlinx.serialization、Room（本地缓存）、
   DataStore（token、设置）。OkHttp 做证书公钥固定（服务器自签证书，pin 由 BuildConfig 注入，来自 GitHub Secrets）。
   根据 api.yaml 生成或手写 API 层；401 自动用 refresh token 续期。
2. 账号：注册（先调 /auth/campus-check，非校园网则要求邀请码）、注册成功强制展示恢复码并要求用户确认已保存、
   登录、用恢复码找回、设置页（昵称、榜单匿名开关）、我的邀请码（复制、剩余名额）。
3. 课表：周一到周五 × 12 节网格（作息来自 /meta）；导入流程：选 1–2 张截图 → /ai/status 在线则走 parse_timetable
   → 确认页逐条可改（课名、教室、星期、起止节，截断的课名高亮）→ /timetable/import。
   AI 离线时导入按钮置灰并提示「电脑未在线，请手动添加」，手动添加支持 /classes/search 搜索已有课。
   课程页有「不是我的班」入口。
4. 检查更新：启动时请求 GitHub Releases latest，版本号高于本机则提示下载 APK。
5. 全部界面用中文，全局角色叫「管理员」。
交付：可安装的 debug APK；与 WS5 约定好导航入口（首页、班级页由 WS5 实现）。
```

## WS5 Android：作业、社区、提醒
```
你负责 /android 的作业相关界面和本地提醒，基于 WS4 的骨架。
先读 docs/requirements.md、docs/api.yaml、docs/points-rules.md。

1. 首页：/feed 按 DDL 升序跨课程列表；DDL 标红（ddl_conflict）和「待确认」标签；左滑或勾选标记完成；可切换显示已完成。
2. 班级页：按日期浏览作业；周榜/学期榜（匿名显示「匿名同学」）、课代表头衔。
3. 发布：从班级页进入。可粘贴文字/选图（最大 10MB，上传前自动压缩到约 1MB，用户无感知）。
   AI 在线时可「智能识别」→ parse_assignment 草稿回填表单（uncertain 字段标黄）；离线按钮置灰。
   发布前调 candidates（在线时再跑 dedupe_review），有疑似重复时让用户选「作为补充」或「仍然新建」。
   有图片必须填 DDL。可选 spam_check，只提示本人。
4. 作业详情：来源列表（首发/补充、发布者昵称、图片缩略图可放大）、谢谢按钮、补充按钮、提议合并、
   删除投票：红色警示按钮 → 弹窗文案「此功能用于删除错误或虚假的内容。达到人数后将删除该内容，并扣除发布者积分。请确认内容有误后再使用此功能。」→ 确认按钮倒数 3 秒后可点。
5. 积分：「我的积分」明细页、「积分规则」页（原样展示 points-rules.md 上半部分）。
6. 提醒：WorkManager 定期 + 前台打开时调 /sync；对每条未完成作业用 AlarmManager.setExactAndAllowWhileIdle 设三次提醒：
   DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时；已过的跳过，相隔不足 1 小时的合并为一次。
   通知栏消息点击进入作业详情，带「标记完成」操作按钮（直接调接口并取消剩余提醒）。
   文案优先用 reminder_text，没有就用内置模板。开机广播后重建闹钟。
   首次启动引导用户开启通知权限、精确闹钟权限、自启动和电池优化白名单（按品牌给跳转，通用兜底）。
```

## WS6 部署、备份、发布
```
你负责 /deploy 和 CI/CD。目标服务器：中国大陆 2 核 2 GB，无域名，用 IP + 端口 HTTPS。
先读 docs/requirements.md。

1. docker-compose：postgres:16（开启 pg_trgm、btree_gist）、server（WS1/2 的镜像）、nginx。
   内存预算：postgres shared_buffers 256MB，server 限 512MB。镜像优先用国内可拉取的源，并写明替代方案。
2. Nginx：自签证书（脚本生成，输出公钥 sha256 供 App 固定）、反代 /api、内部转发 /files（X-Accel-Redirect）、
   client_max_body_size 11m、按 IP 限流、传 X-Real-IP。
3. .env.example：列出所有变量（DATABASE_URL、JWT_SECRET、WORKER_TOKEN、CAMPUS_CIDRS、UPLOAD_DIR、
   BOOTSTRAP_ADMIN_TOKEN、TRUSTED_PROXY 等），不含真实值。
4. 备份：每日 03:30 pg_dump 压缩，保留 7 天；上传目录不备份（图片按学期清理）。写恢复步骤。
5. GitHub Actions：
   - server：PR 跑 go test + golangci-lint；main 构建镜像（可推 GHCR）。
   - android：PR 跑单测和 lint；打 tag v* 时构建 release APK（arm64-v8a），签名密钥与证书 pin 来自 Secrets，发布到 GitHub Releases。
   - worker：tag 时交叉编译 Windows/macOS/Linux 二进制附到同一个 Release。
6. deploy/README.md：从零部署步骤、更新步骤、如何填 CAMPUS_CIDRS、监控磁盘（预计每学期图片约 2GB）。
```

## WS7 审计（只审不写功能）
```
你是 swufe-homework 的审计员，不写功能代码，只负责审查。仓库 IsANineBug/swufe-homework。
基准文档：docs/requirements.md、docs/api.yaml、server/db/schema.sql、docs/ai-contract.md、docs/points-rules.md、docs/workstreams.md。

每当有新 PR（[WS1]–[WS6]）时，逐项检查并在 PR 上留 review，结论只能是「通过」「需修改」两种，需修改必须给出文件和行号：
1. 契约一致：接口路径、字段、错误码与 api.yaml 一致；表结构改动同步改了 schema.sql 和迁移；改契约的 PR 是否 @ 了受影响的工作线。
2. 需求一致：没有偏离 requirements.md。重点核对：
   - 完成状态只本人可见，任何接口、日志、榜单都不泄露他人完成情况；
   - 删除是软删除，只有管理员能恢复；删除门槛 clamp(ceil(成员数×10%),3,6)；30 天 3 次禁发 7 天；
   - 合并：首发者同意或 3 人赞同；只搬来源不删内容；可撤销；
   - 归班算法：同人同名合一班、任一时段命中即同班、都不中新建、命中两班自动合并；
   - 注册：邀请码或校园网 IP 二选一，每人 5 个邀请名额；
   - 提醒：DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时，带「标记完成」；
   - AI 只出草稿，离线不排队、按钮置灰；AI 不判断课程归属，不写业务表；
   - 界面和文案只用「管理员」，不出现「超管」。
3. 积分：逐条对照 points-rules.md（+10/+3/谢谢+1 上限 10/每日 30/删除收回并 -10/恢复返还/合并调整），要求有对应单测。
4. 安全：无密钥、token、代理地址、证书私钥进仓库或日志；密码 argon2id；refresh token 只存哈希；
   X-Real-IP 只信任 TRUSTED_PROXY；所有写接口校验班级成员身份；SQL 全部参数化；上传校验类型和大小；文件 URL 带过期签名。
5. 隐私：昵称之外不收集个人信息；榜单匿名开关生效；日志不记录作业正文和图片。
6. 质量：go test / Android 单测和 lint 通过；新逻辑有测试；2C2G 资源预算内（无 N+1 查询、无全表扫描的热点接口）。

另外：
- 维护 docs/audit-log.md：每次审计追加一行（日期、PR 号、结论、主要问题）。
- 每周一汇总一次：未关闭的问题、文档与实现的偏差，开 issue 标签 audit。
- 发现文档本身有矛盾或遗漏，开 issue 给作者（IsANineBug）裁决，不要自行改需求。
```
