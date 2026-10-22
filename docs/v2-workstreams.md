# v2 分工与开工指令

> 2026-10-23 定稿。需求见 `docs/requirements.md` 第十三节。接口与表结构的 v2 部分已经写进 `docs/api.yaml`（标 v2 的路径和字段）和 `server/db/schema.sql`（标 v2 的列和表），**照契约实现，不要自己另起字段**。

4 条开发线 + 1 条审计线。通用约定沿用 `docs/workstreams.md`（分支命名、PR 前缀、密钥不进仓库、Asia/Shanghai、额度交接写 `docs/handoff/WS<编号>.md`、改契约要同时改文档并 @ 受影响的线），只是编号从 8 开始。

| 线 | 范围 | 目录 | 分支 | 依赖 |
|---|---|---|---|---|
| WS8 | 后端 v2：频道、举报、管理员、小字段、issue #8 后端项 | `/server` | `ws8-server-v2` | 无，立即开工 |
| WS9 | AI worker 保险与识图质量 | `/worker` | `ws9-worker-guard` | 无，立即开工 |
| WS10 | Android 底座：视觉规范、通用组件、改名、提醒页、后台识图、课表页 | `/android` | `ws10-design-system`、`ws10-android-base` | 无，立即开工 |
| WS11 | Android 功能：首页、作业编辑、频道、举报、管理、issue #8 Android 项 | `/android` | `ws11-android-features` | WS10 第一个 PR 合并后开工；接口按 api.yaml 先写，WS8 合并后联调 |
| WS7 | 审计（只审不写） | — | — | 每个 PR |

### 开工顺序与调度（由 WS7 安排）
WS7 既审计也排班。**调度板是 issue「v2 调度板」**（标题以此开头），WS7 每 30 分钟更新一次，写明每条线现在该做什么、能不能开工、等谁。各开发 Hark 每 30 分钟先看调度板，再看自己 PR 的 review，按调度板执行，不要抢跑。

| 阶段 | 开工条件 | 谁做什么 |
|---|---|---|
| 1 | 立即 | WS8、WS9、WS10 ① 同时开工。PR #9 合并前从分支 `v2-contract` 拉分支（PR 的 base 仍是 `main`），#9 合并后 rebase 到 main。 |
| 2 | WS10 ① 「通过」并合并 | WS11 开工（WS8 未合并前先用假数据）；WS10 继续做 ②。 |
| 3 | 各自「通过」 | 合并顺序：WS9（随时）→ WS8 → WS10 ② → WS11。后合并的先 rebase 到最新 main 再请求复审。 |
| 4 | 四条线全部合并 | 作者发 `v0.0.1-rc2` 并重测。 |

- 每条线内部**按自己指令里的编号顺序做**（1 → 2 → 3…），做完一条在 PR 描述里勾一条；前一条没做完不跳到后面，除非调度板另有安排。
- WS11 在 WS10 ② 合并前不要改 `PublishViewModel.kt` 里调用 parse_assignment 的那一处。
- WS8 合并后，WS11 把假数据换成真接口联调；接口和 api.yaml 对不上时在 PR 里 @ WS8，不要自己改契约。
- 卡住超过 1 小时（等裁决、等别的线）就在调度板留言「[WS编号] 卡住：原因」，WS7 会转给作者。

**Android 两条线的文件归属**（避免冲突）：
- WS10 负责：`ui/theme/`、`ui/common/`、`ui/timetable/`、`ui/auth/`、`ui/me/`（不含管理入口里的页面）、`ui/main/MainScreen.kt`、`ui/homework/ReminderGuideScreen.kt`、`ui/homework/PointsScreens.kt`、`reminder/`、`data/repo/AiJobTracker.kt`（新）、`assets/points-rules.md`。
- WS11 负责：`ui/homework/` 其余文件、`ui/chat/`（新）、`ui/admin/`（新）、`ui/nav/` 里新增的路由、`data/api/` 里新增的接口与模型。
- 需要动对方的文件时，在 PR 里写明并 @ 对方，改动尽量小。

**全体共同规则（审计会逐条查）**：
- 用户看得到的字：「积分」一律写「贡献值」；「闹钟」一律写「提醒」；不出现「超管」。接口字段名、表名、代码变量名**不改**（如 `points`、`/me/points`、`points_ledger` 保持原样）。
- 管理员隐私底线（requirements.md 十三.8）：任何接口、日志、管理页面都不返回密码、恢复码、token、某人加入了哪些班、个人课表、完成情况、提醒、IP，也不能浏览未被举报的频道消息。
- 2 核 2 GB 预算：新接口无 N+1、热点查询走索引；频道不用 WebSocket/SSE/长轮询。

---

## WS8 后端 v2
```
你负责 swufe-homework 的 /server v2 改动。仓库 IsANineBug/swufe-homework，从 main 拉分支 ws8-server-v2，PR 标题前缀 [WS8]。
开工前和之后每 30 分钟先看 issue「v2 调度板」。先读 docs/requirements.md（重点第十三节）、docs/api.yaml（所有标 v2 的路径和字段）、server/db/schema.sql（标 v2 的部分）、docs/points-rules.md、issue #8。

1. 迁移 0005_v2（up/down）：classes.hidden_at；points_ledger reason 加 admin_adjust、加 note 列；
   新表 class_messages、reports、user_sanctions、admin_actions，以及 schema.sql 里写的索引。schema.sql 已是目标状态，迁移要和它一致。
2. 班级频道（tag chat）：GET/POST /classes/{id}/messages、DELETE /messages/{id}，严格按 api.yaml：
   - 仅成员；管理员也不能浏览频道；被禁言 403 muted；隐藏班级 409 class_archived。
   - 限频：每人每 10 秒 1 条、每小时 30 条（所有班合计），用 class_messages_user_time 索引数，超出 429 chat_rate_limited + Retry-After。
   - 定时任务（同进程 cron，每天 04:00）：硬删 30 天前的消息，以及每班超出最近 500 条的部分。
   - /me/classes 每个班返回 last_message_id（一条带索引的子查询，不要 N+1）。
3. 举报：POST /reports（可见性校验、未处理去重 409 already_reported、每人每天 20 条）、
   GET /admin/reports（同一对象合并一行；消息附前后各 5 条上下文；只给 api.yaml 列出的公开字段）、POST /admin/reports/{id}/resolve。
4. 管理员接口，全部要求 role=admin，每个写操作在同一事务里写一行 admin_actions（payload 存改前/改后，不存任何秘密）：
   - 班级：GET /admin/classes（含 suspicious 过滤）、PATCH /admin/classes/{id}、hide/unhide、POST /admin/classes/merge（复用自动合并的代码和 class_merges 快照，能被现有 revert 撤销）。
     隐藏的班：/me/classes、/classes/search、/timetable/import 归班、/feed、/sync 都看不到（作业进 removed_ids），成员收 class_hidden 通知；unhide 全部恢复。
   - DELETE /admin/sources/{id}（deleted_reason=admin，penalize 默认 true 时与投票删除同样收回并 -10、计入 30 天禁发次数）；
     /admin/sources/{id}/restore 放开到 admin 删除的内容。
   - DELETE /admin/messages/{id}。
   - 用户：GET /admin/users?q=、GET/PATCH /admin/users/{id}（只返回 AdminUser 里的字段）；
     POST /admin/users/{id}/sanctions（mute/post_ban/disable，规则见 api.yaml）、POST /admin/sanctions/{id}/lift。
     post_ban 生效时所有班发作业、补充都 403 post_banned；disable 作废所有 refresh token，登录与续期返回 403 account_disabled，现有 access token 在中间件里也要拦（查 users.disabled_at，可做 30 秒内存缓存）。
   - POST /admin/users/{id}/points：admin_adjust，settled，不计每日 30 上限。
   - GET /admin/actions。
   - /me 返回 restrictions；新通知 kind：sanction、nickname_reset、points_adjusted、class_hidden。
5. 小字段：Assignment.is_first_poster、Source.is_mine（按用户 id）；PointsEntry.note。
6. 「积分」→「贡献值」：所有返回给用户的 message、通知文案、公告模板里的「积分」改成「贡献值」。字段名、表名、变量名不改。
   worker 已改在服务器上跑：ai_offline 的提示「电脑未在线，请手动录入」（ai/jobs.go:22）改为「AI 暂时不可用，请手动录入」；注释里的「电脑端」可顺手改。
7. issue #8 后端项全部修掉并在 issue 里勾选：WS1 两项、WS2 五项（含 docs/ai-contract.md:66、docs/workstreams.md:68 的 pg_trgm 文字）、
   WS3 的 jobs.go:539（date_trunc 秒级比较）和 hw_assignments.go:435（只改标题也清空 reminder_text）。
   TLS VerifyConnection 那项只在代码里加注释说明，不用改。
8. 单测：频道限频边界（第 2 条在 10 秒内被拒、第 31 条被拒）、保留策略、成员/非成员/管理员可见性；隐藏班在 search/import/feed/sync 的表现；
   管理员删除 penalize 真/假的贡献值变化与恢复；disable 后 access token 被拒；每个 admin 写操作都写了 admin_actions；
   AdminUser 与 AdminReport 的 JSON 不含 api.yaml 之外的字段（用快照测试）。
不要改：AI 任务 2 分钟过期、已有接口的字段名。
交付：PR 描述列出已实现接口和 issue #8 勾选项。
```

## WS9 AI worker 保险与识图质量
```
你负责 /worker。仓库 IsANineBug/swufe-homework，从 main 拉分支 ws9-worker-guard，PR 标题前缀 [WS9]。只改 /worker、.env.example 的 worker 段和 worker/README.md。
先读 docs/ai-contract.md、docs/requirements.md 第三节和第十三节第 2 条、worker/ 全部代码。

背景：worker 现在部署在服务器上（与后端同一台 2 核 2 GB，不再在作者电脑上跑），用的是 gemini-3.8-flash-high（经 Command Code，思考等级固定 high）。2026-10-21 用同一张第 6 周课表实测 6 次：
5 次成功（计时两次 37 秒、51 秒，12 节课全对），1 次「超时（90 秒）」失败；顶部被裁掉的两节课（周四、周五第 3 节起）没有一次标 start_visible=false；第一次把一门 5–6 节的课识别成只有第 5 节。

1. 模型：config.go 默认 LLM_MODEL 改为 Command Code 上 gemini-3.8-flash-high 的真实 id——先 GET {LLM_BASE_URL}/models 核对（可能带厂商前缀），
   核对不了就在 PR 里写明并保留可配置。.env.example、worker/README.md、docs/requirements.md 第三节同步。
2. 输出上限：新增 LLM_MAX_TOKENS（默认 32768，允许 4096–65536，非法值启动报配置错误）；请求体带 max_tokens；
   parse_timetable、parse_assignment 用满，spam_check、reminder_text、ddl_conflict、dedupe_review 用 min(LLM_MAX_TOKENS, 16384)。
3. 截断识别：读 finish_reason，等于 length 时返回 ErrTruncated，上报失败原因「输出被截断」，不重试。
4. 重试守卫：JSON Schema 不合格仍重试 1 次；但剩余时间不足 30 秒、或上次是截断/超时时不重试。整个任务仍 90 秒上限（服务器 2 分钟过期不变）。
5. 用量日志：每次调用记录 kind、耗时、usage.prompt_tokens、usage.completion_tokens（含思考）、finish_reason；不记录图片和正文。
6. temperature：现在固定传 0。改为可配置 LLM_TEMPERATURE，留空时不传该字段（让模型用默认值）；默认留空。
7. 识图质量（只改提示词和后处理，不改输出 Schema）：
   - 提示词明确：课块顶边贴着截图顶部（或被表头遮住）时必须 start_visible=false；底边贴着截图底部时同理在 room/name 截断处标 truncated。
   - 后处理：按课块在图里的位置对齐节次刻度（左侧时间轴），课块跨两行以上不得只给一节；不确定时宁可标 start_visible=false 让用户核对。
   - 课名末尾的「▲」等符号原样保留与否按 ai-contract.md 的规定执行（若契约没写，开 issue 问作者，不自行决定）。
8. 部署在服务器上：worker/README.md 把「电脑开机自启 / 只在 12:00–24:00 运行」改为服务器上的运行方式（systemd 或 docker，崩溃自动重启）；
   内存控制在 64 MB 以内（Go 默认即可，WORKER_CONCURRENCY 默认 2 不变），图片处理不要整批读进内存；连 Command Code 是否需要 HTTPS_PROXY 以服务器实际网络为准，README 两种都写。
9. 回归测试：docs/seed-timetable/ 目前没有样例，TestTimetableRegression 跑不了。作者提供脱敏截图后放进去并写期望值；
   在拿到截图前，用假模型测：截断不重试（模型只被调 1 次）、<30 秒不重试、Schema 不合格重试 1 次、请求体含 max_tokens、不传 temperature。
交付：PR 描述写清新变量、默认值和测试结果（不贴密钥、不贴课表原图）。
```

## WS10 Android 底座与体验
```
你负责 /android 的视觉规范、通用组件和 v2 底座改动。仓库 IsANineBug/swufe-homework。分两个 PR：
  PR① 分支 ws10-design-system，标题 [WS10] 视觉规范与通用组件 ——要尽快合并，WS11 等它开工；
  PR② 分支 ws10-android-base，标题 [WS10] 改名、提醒页、后台识图、课表页打磨。
先读 docs/requirements.md 第十三节、docs/v2-workstreams.md 的「文件归属」和「共同规则」、android/README.md、android/NAVIGATION.md。

现状：Theme.kt 只定了 primary（西财红 0xFF8B1A1A）、secondary、tertiary 三个颜色，字体、圆角、间距全是 Material 默认，所以整体像样板。

PR① 视觉规范与组件：
1. ui/theme/：完整的浅色/深色 colorScheme（以西财红为主色，补齐 surface/container/outline 等角色，深色模式对比度达标）；
   Typography（标题、正文、辅助文字三级，中文字号不小于 12sp）；Shapes（卡片 16dp、按钮 12dp、标签 8dp）；
   间距常量 Spacing（4/8/12/16/24）；语义色 Urgent（24 小时内截止，红）、Warning（待确认，琥珀）、Success；课表 CourseColors 调成与主题协调的一套，深色模式下也清楚。
2. ui/common/：EmptyState（图标 + 一句话 + 可选按钮）、LoadingState（骨架屏或居中进度）、ErrorState（原因 + 重试）、
   StatusBanner（顶部状态条，给后台识图用）、SectionHeader、ConfirmDialog（含「倒数 N 秒后可点」变体，替代现有删除投票弹窗的实现）。
3. 在 android/README.md 加「视觉规范」一节：颜色角色、字号、圆角、间距怎么用，什么时候用哪个组件。附截图（浅色、深色各一）。
4. PR① 不改业务逻辑，只加主题与组件，并把 Theme.kt 调用处改到新主题。

PR② 底座改动（使用 PR① 的组件，把你负责的页面都按规范重排）：
5. 改名：界面上「闹钟」→「提醒」（ReminderGuideScreen.kt:94/102/104、MeScreens.kt:79，具体文案见 docs/requirements.md 十三.5 和下面）；
   「积分」→「贡献值」（PointsScreens.kt、MeScreens.kt、assets/points-rules.md 从 docs/points-rules.md 重新复制、RulesMarkdown.kt 标题）。
   ReminderGuideScreen：「作业提醒由手机本地定时发出」；「准时提醒」+ 副标题「准点提醒不被推迟（系统里叫「闹钟和提醒」）」。
   代码注释里 AlarmManager 的「闹钟」可以保留。
6. 去掉自启动引导：删除那一步、openAutoStart() 和按品牌跳转的代码；MeScreens.kt 副标题改「通知、准时提醒、电池白名单」；
   android/README.md:68、docs/workstreams.md:140 同步。开机广播重排提醒保留。
7. 后台识图（AiJobTracker）：
   - 新建 data/repo/AiJobTracker.kt（@Singleton，应用级 CoroutineScope(SupervisorJob()+Dispatchers.IO)）。
   - start(kind, attachmentIds, context)：建任务后把 {jobId, kind, context, createdAt} 存 DataStore 键 pending_ai_jobs，应用级 scope 里轮询到 createdAt+150 秒。
   - 暴露 StateFlow<List<AiJobState>>（识别中 / 完成+结果 / 失败+原因 / 超时）；同 kind 已有进行中的任务时返回已有任务，不新建。
   - App 启动时恢复未过期的任务，过期的标超时并移除；确认页取走结果或用户「放弃」后删除。
   - 只有 parse_timetable、parse_assignment 走 tracker；dedupe_review、spam_check 保持同步等待。
   - MainScreen 顶部用 StatusBanner：「课表识别中…」/「课表识别好了，去确认 ›」/失败原因 +「手动添加」，点击进对应确认页。
   - 完成时 App 在前台弹 Snackbar「去确认」，在后台发「AI 识别」渠道的系统通知，点通知经 DeepLinks 进确认页（ProcessLifecycleOwner 判断前后台）。
   - 识别页建好任务后显示「已在后台识别，可以先去做别的」+「留在这里等」「先去别的页面」。
   - 确认页从 tracker 读结果，不依赖 ViewModel 内存。作业草稿页需要的 classId 等放 context。
   - 不用 WorkManager/前台服务；不改服务器接口和 2 分钟过期。
   - WS11 的 PublishViewModel 改用 tracker 时，由你在 PR② 里一并改那一处调用（PublishViewModel.kt 的 parse_assignment），并 @ WS11。
8. 课表导入确认页：任一课 start_visible=false 或截断时，页顶提示「截图上方/下方可能被裁掉，建议补截另一半一起识别」+「再加一张」按钮（最多 2 张，与现有一致）。
9. 文案：「电脑未在线，请手动添加」等提到「电脑」的 AI 离线提示，统一改为「AI 暂时不可用，请手动添加」，「电脑正在识别…」改为「正在识别…」（worker 已改在服务器上跑；PublishScreen/PublishViewModel 里的两处也由你改，@ WS11）。
10. 「我的」页加处罚提示：/me 的 restrictions 非空时显示「你被禁言/禁发到 X，原因：…」。
11. 你负责的页面全部用 EmptyState/LoadingState/ErrorState，不再出现空白屏或只有一行灰字。
测试（PR 描述逐条打勾）：浅色/深色截图；后台识图 5 个场景（去别的页面、退到桌面收通知、划掉 App 2 分钟内重开、超过 2 分钟重开显示超时、重复点识别不新建）；
单测：AiJobTracker 恢复逻辑与去重。全仓 grep 用户可见字符串不得再有「闹钟」「积分」「自启动」。
```

## WS11 Android 功能
```
你负责 /android 的 v2 功能。仓库 IsANineBug/swufe-homework，等 [WS10] 视觉规范 PR 合并后，从 main 拉分支 ws11-android-features，PR 标题前缀 [WS11]。
可以拆成多个 PR（如 首页+编辑 / 频道+举报 / 管理）。全部使用 WS10 的主题与 ui/common 组件。
先读 docs/requirements.md 第十三节、docs/api.yaml 所有 v2 部分、docs/v2-workstreams.md 的「文件归属」和「共同规则」、issue #8。
WS8 未合并前：按 api.yaml 写接口层，用假数据/假服务器把界面做完；WS8 合并后联调。

1. 首页（HomeScreen）：顶部切换「今天 / 明天 / 本周 / 全部」，默认「全部」，记住上次选择；仍按 DDL 升序；
   24 小时内截止的卡片用 Urgent 色标出（DDL 行变红 + 左侧色条）；「本周」= 今天到本周日 23:59（Asia/Shanghai）；切到空的分组用 EmptyState。
   保留「显示已完成」和左滑完成。
2. 首发者编辑：Assignment.is_first_poster 为真时详情页显示「编辑」，进入编辑页改标题、DDL（PATCH /assignments/{id}）和说明（首发来源正文、附件，PATCH /sources/{id}）；
   清空「你看到的 DDL」时要显式下发 null（修 issue #8 PublishViewModel.kt:191）。
3. issue #8 Android 项：isMine/iAmFirstPoster 改用 Source.is_mine / Assignment.is_first_poster；合并提议者自己的提议「赞同」按钮禁用。修完在 issue 里勾选。
4. 班级频道（ui/chat/）：班级页加「交流」Tab。
   - 进入时 GET 最新 50 条，之后只在页面可见时每 12 秒用 after_id 拉新消息，离开/切后台立即停；往上滑用 before_id 翻页。
   - 输入框 300 字计数；发送后乐观显示，失败回滚并提示；429 时按 Retry-After 显示「x 秒后可再发」并禁用发送；muted 时输入框换成禁言说明。
   - 自己的消息可长按删除；别人的消息长按「举报」（填理由 1–200 字）。
   - 课表/班级列表用 /me/classes 的 last_message_id 与本地已读 id 比较显示小红点。
   - 不用 WebSocket、前台服务或后台轮询。
5. 举报入口：作业来源、频道消息、班级页菜单、用户昵称处，统一的举报弹窗（POST /reports），409 already_reported 提示「你已经举报过，管理员处理中」。
6. 管理（ui/admin/）：只有 /me.role=admin 时「我的」页出现「管理」入口，普通用户看不到也进不去（路由层也要拦）。页面：
   - 举报：未处理/已处理列表，点开看对象内容（消息带上下文），直接操作「删除内容 / 隐藏班级 / 处罚用户 / 驳回」，操作后自动 resolve。
   - 班级：搜索 + 「可疑」筛选，隐藏/恢复、改名、合并两个班（二次确认，提示可在合并记录里撤销）；已有的班级自动合并记录与撤销也放这里。
   - 用户：按昵称搜，详情只展示 AdminUser 字段；禁言/禁发/封号（选天数、必填原因）、解除、改昵称、调贡献值（选班、正负、必填原因）。
   - 已删除内容（现有 /admin/deleted）与恢复、公告发布、操作日志。
   - 所有破坏性操作用 ConfirmDialog，必须填原因。
7. 文案：「贡献值」「提醒」「管理员」，不出现「积分」「闹钟」「超管」。
测试（PR 描述逐条打勾）：首页四个切换与标红边界（23:59 与 24:01）；首发者/非首发者看到/看不到编辑；频道轮询只在可见时发生（日志或单测证明）；
限频与禁言提示；非管理员访问管理路由被拦；单测：日期分组函数、频道分页合并去重。
```

## WS7 审计 v2 补充
```
沿用 docs/workstreams.md 的 WS7 指令，基准文档加上 docs/requirements.md 第十三节和本文件。v2 额外检查：
- 契约：v2 接口、字段、错误码与 api.yaml 一致；迁移 0005 与 schema.sql 一致。
- 隐私：AdminUser/AdminReport/管理页面不含共同规则里禁止的数据；管理员不能 GET 频道；admin_actions.payload 不含秘密。
- 资源：频道无长连接、轮询只在可见时；last_message_id 无 N+1；保留策略定时任务存在。
- 文案：用户可见字符串无「积分」「闹钟」「自启动」「超管」。
- 文件归属：WS10/WS11 越界改动有说明并 @ 对方。
- issue #8：每个修复的 PR 在 issue 里勾选对应项；全部勾完后关闭 issue #8。
```

## 发布 v0.0.1-rc2（作者或作者本地的 agent）
全部 PR 合并后：按 `docs/handoff/RELEASE.md` 的步骤部署服务器（会跑迁移 0005）、更新服务器上的 worker 和它的 `.env`（新增 LLM_MAX_TOKENS，LLM_TEMPERATURE 留空），
打 tag `v0.0.1-rc2`，按发布清单重测，并加测：后台识图 5 个场景、频道限频与禁言、管理员隐藏班级/删除/处罚/恢复、首页四个切换、首发者编辑。
