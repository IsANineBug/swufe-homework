# Android 导航约定（WS4 ⇄ WS5）

单 Activity（`MainActivity`）+ Navigation Compose 类型安全路由，全部路由在
`ui/nav/Routes.kt`，导航图在 `ui/nav/AppNavHost.kt`。

## 底部导航（`ui/main/MainScreen.kt`）
| Tab | 负责 | 说明 |
|---|---|---|
| 首页 `MainTab.Home` | **WS5** | `MainScreen(home = { HomeScreen(...) })`，/feed。启动默认打开首页。 |
| 课表 `MainTab.Timetable` | WS4 | 网格、导入、手动添加 |
| 我的 `MainTab.Me` | WS4 | 设置、邀请码、检查更新；WS5 加了「我的贡献值」「贡献值规则」「提醒设置」（v2 改名，路由名不变） |

## 班级页 `Routes.ClassPage(classId)`
- 外壳 `ui/timetable/ClassPage.kt` 的 `ClassPageScreen`：WS4 提供顶部栏（课名）、时段/人数头部、「不是我的班」。
- **WS5** 通过 `body = { classId -> ... }` 填入按日期浏览作业、周榜/学期榜、发布入口。
  在 `AppNavHost` 的 `composable<Routes.ClassPage>` 里把 WS5 的内容传进去即可。
- 从课表点课程色块进入；首页卡片也可 `nav.navigate(Routes.ClassPage(id))`。

## WS5 新增页面
在 `Routes` 里加 `@Serializable` 目的地（如 `AssignmentDetail(id)`、`Publish(classId)`、`MyPoints`、`PointsRules`），
在 `AppNavHost` 里注册。通知点击进入作业详情：在 `MainActivity` 读 intent extra 后 `nav.navigate(Routes.AssignmentDetail(id))`。

## 可复用的基础设施
- 网络：注入 `Retrofit`（已带证书固定 + Bearer + 401 自动续期），`retrofit.create(YourApi::class.java)`。
- 错误：`apiCall { ... }` 返回 `Result<T>`，失败是 `ApiException(code, message)`，`message` 可直接展示。
- 本地库：`AppDatabase`（Room），加表时升 `version` 并写 Migration。
- 设置：`SettingsStore`（DataStore）。
- 图片压缩：`util/ImageCompressor.compress(uri)`，约 1 MB JPEG。
- 会话过期：`SessionEvents.expired` 会让导航回到登录页。

## WS5 已注册的页面
| 路由 | 页面 |
|---|---|
| `AssignmentDetail(id)` | 作业详情（通知点击也进这里：`MainActivity` 读 `assignment_id` extra → `DeepLinks` → 导航） |
| `Publish(classId, assignmentId = -1, sourceId = -1)` | 发布（只有 classId）/ 补充（带 assignmentId）/ 编辑自己的来源（再带 sourceId） |
| `MyPoints`、`PointsRules` | 我的贡献值、贡献值规则 |
| `ReminderGuide` | 提醒设置引导（首次进入主界面自动打开一次） |

## WS11 新增页面
| 路由 | 页面 |
|---|---|
| `EditAssignment(id)` | 首发者编辑作业（标题、DDL → `PATCH /assignments/{id}`；说明 = 首发来源正文与图片 → `PATCH /sources/{id}`）。入口：作业详情顶栏「编辑」，仅 `Assignment.is_first_poster` 为真时显示 |

## 班级频道与举报（WS11，v2，不加新路由）
- 班级页 `ClassBody` 的 Tab：「作业 / 交流 / 榜单」。「交流」= `ui/chat/ChatTab`：进入拉最新 50 条；只在这个 Tab 在组合里且 Lifecycle ≥ STARTED 时每 12 秒 `after_id` 拉新（`pollWhileVisible`），切 Tab、离开、切后台立即停；往上翻 `before_id`。没有 WebSocket、前台服务或后台任务。
- 小红点：`TimetableRepository.refreshClasses` 把 `/me/classes` 的 `last_message_id` 交给 `data/repo/ChatReadStore`（已读 id 存 DataStore `chat_prefs`）；课表色块与「交流」Tab 显示未读点。
- 举报弹窗 `ui/chat/ReportDialog(ReportTarget)`（`POST /reports`，409 already_reported →「你已经举报过，管理员处理中」）。入口：作业详情来源卡片「更多 → 举报」、频道消息长按「举报」、班级页顶栏「更多 → 举报这个班」（`ClassPageScreen(actions = …)`）。

## 后台识图（WS10，v2）
- `data/repo/AiJobTracker`（@Singleton）跟踪 `parse_timetable` / `parse_assignment`；`MainScreen` 顶部状态条、识别结束的 Snackbar、「AI 识别」通知都从它读。
- 点状态条 / Snackbar / 通知：课表 → `TimetableImport`（失败「手动添加」→ `TimetableManual`）；作业 → `Publish(class_id, assignment_id, source_id)`（取自任务 context）。逻辑在 `ui/main/MainScreen.kt` 的 `openAiJob`。
- 通知点击：`MainActivity` 读 `ai_job_kind` extra → `DeepLinks.openAi(kind)` → `AiJobSnackbarOverlay` 导航。
