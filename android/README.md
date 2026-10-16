# swufe-homework Android

Kotlin + Jetpack Compose + Material 3，minSdk 26，只打 `arm64-v8a`。

## 结构
```
app/src/main/java/com/swufe/homework/
  data/api      Retrofit 接口（对照 docs/api.yaml）、模型、证书固定、鉴权与 401 续期
  data/local    DataStore（token、设置）、Room（课表缓存）
  data/repo     Auth / Account / Timetable / Update 仓库
  di            Hilt 模块
  ui/auth       登录、注册（校园网检测 + 邀请码）、恢复码展示、找回
  ui/timetable  课表网格、截图导入与确认、手动添加、班级页外壳
  ui/me         我的、设置、邀请码
  ui/homework   WS5：首页 feed、班级页内容（按周浏览作业、榜单）、发布/补充/编辑、作业详情、积分、提醒引导
  reminder      WS5：本地提醒（AlarmManager 闹钟、通知、标记完成、开机重建、WorkManager 同步）
  ui/nav        路由与导航图（与 WS5 的约定见 NAVIGATION.md）
```

## 构建
需要 JDK 17、Android SDK 35。
```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

构建时注入的配置（**不要提交真实值**），变量名与 WS6 release workflow 统一，见 docs/workstreams.md「Android 构建环境变量」：

| 环境变量（或同名 Gradle 属性） | 用途 |
|---|---|
| `SERVER_BASE_URL` | `https://<IP>:8443/api/v1` |
| `SERVER_CERT_PIN` | `sha256/<base64>`，可逗号分隔多个 |
| `SIGNING_STORE_FILE` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` | release 签名 |
| `VERSION_NAME` / `VERSION_CODE` | 版本（与 tag `v<VERSION_NAME>` 对应，检查更新靠它比较） |

- **release fail-closed**：缺 pin、地址仍为默认 `127.0.0.1`、或缺签名配置时 `assembleRelease` 直接失败；运行时 release 下 pin 为空也会拒绝建立连接。
- debug 可以不配：pin 为空时退回系统 CA（只用于本地对接有正规证书的测试服务器）。
- 有 pin 时只接受叶子证书公钥匹配的证书，主机名用 OkHttp 默认校验（证书带 IP SAN）。

## 已实现（WS4）
- 注册：先 `/auth/campus-check`，非校园网要求邀请码；成功后强制展示恢复码，勾选「我已妥善保存」才能继续，屏蔽返回键。
- 注册后的 token 先放内存，确认已保存恢复码后才落盘（恢复码页被杀不会绕过确认）。
- 登录、用恢复码找回（展示新恢复码）、退出登录（带本机 refresh_token 只注销本机，并清本地缓存）。
- 401 自动续期：只有 refresh 返回 401 才登出，网络错误保留登录态。
- 设置：昵称、榜单匿名开关；我的邀请码：列表、复制、剩余名额、生成。
- 课表：周一到周五 × 12 节网格，作息取 `/meta`（离线用内置西财作息）。
- 导入：选 1–2 张截图 → 压缩上传 `/uploads` → `/ai/status` 在线才发 `parse_timetable`（只传 `attachment_ids`） → 1.5 秒轮询最多 2 分钟
  → 确认页逐条可改（课名、教室、星期、起止节；截断课名和被裁掉的起节标黄）→ `/timetable/import`，
  conflicts 留在确认页标红让用户改名重提。
- AI 离线时导入按钮置灰并提示「电脑未在线，请手动添加」；手动添加可用 `/classes/search` 搜已有课一键填入。
- 班级页「不是我的班」→ `DELETE /me/classes/{id}`（服务端记住退出，重新导入不自动加回，可搜索手动加入）。
- 启动时检查 GitHub Releases latest（自动检查最多 6 小时一次，避免匿名 API 限额），版本更高则提示下载 APK。

## 已实现（WS5）
- 首页：`/feed` 跨课程按 DDL 升序；DDL 不一致（`ddl_conflict`）标红、`ddl_uncertain` 显示「待确认」；勾选或左滑标记完成；可切换显示已完成；下拉刷新。
- 班级页（填进 WS4 的 `ClassPageScreen` body）：按周浏览作业（`from`/`to` 为周一到周日，按 DDL 日期分组）；周榜 / 学期榜（匿名显示服务端给的「匿名同学」）、课代表；「发布作业」入口。
- 发布：粘贴文字、选图（最多 6 张，单张 ≤ 10 MB，上传前压到约 1 MB）。AI 在线时「智能识别」→ `parse_assignment` 草稿回填，`uncertain` 字段标黄；离线按钮置灰。
  新建必须填 DDL（api.yaml `AssignmentCreate` 要求 ddl 必填，有图片时同样必填）。
  发布前 `candidates` 查重，在线时再跑 `dedupe_review`（AI 认为同一份的排前面），用户选「作为补充」或「仍然新建」；在线时先跑 `spam_check`，可疑只提示本人。
- 作业详情：来源列表（首发/补充、昵称、时间、TA 看到的 DDL、缩略图点开全屏缩放）、谢谢、补充、编辑/删除自己的来源、提议合并（选同班另一条作业）、合并提议的赞同 / 首发者同意；
  删除投票：红色按钮 → 固定文案弹窗 → 确认按钮倒数 3 秒。
- 积分：「我的积分」（学期总分、今日已得 / 30、明细分页）、「积分规则」（`app/src/main/assets/points-rules.md` 的 `---` 之前部分；该文件是 `docs/points-rules.md` 的副本，单测校验两者一致，改规则时一起复制）。
- 提醒：`/sync` 增量同步写本地 Room 表 `assignment`（v2，只存标题、班级、DDL、完成、提醒文案）；每条未完成作业用 `setExactAndAllowWhileIdle` 设三次闹钟：
  DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时（Asia/Shanghai），已过的跳过，相隔不足 1 小时的合并（保留较晚那次）。
  通知文案优先 `reminder_text[2d|1d|12h]`，否则内置模板；点击进入作业详情；「标记完成」本地立即停止提醒，接口由 WorkManager 调（断网自动重试）。
  同步时机：每 4 小时（WorkManager，联网时）、打开 App、开机 / 升级 / 改时间或时区 / 精确闹钟权限变化（同时重建闹钟）。退出登录清空闹钟、通知和同步游标。
- 首次进入主界面打开「提醒设置」引导：通知权限、精确闹钟、电池优化白名单、自启动（小米、华为、荣耀、OPPO/realme、一加、vivo/iQOO、魅族、三星，打不开就进应用详情页）；之后在「我的 → 提醒设置」。
- 图片用带证书固定的 OkHttp 加载（Coil `ImageLoaderFactory`），相对 URL（`/files/...`）补成服务器地址。
