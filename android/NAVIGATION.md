# Android 导航约定（WS4 ⇄ WS5）

单 Activity（`MainActivity`）+ Navigation Compose 类型安全路由，全部路由在
`ui/nav/Routes.kt`，导航图在 `ui/nav/AppNavHost.kt`。

## 底部导航（`ui/main/MainScreen.kt`）
| Tab | 负责 | 说明 |
|---|---|---|
| 首页 `MainTab.Home` | **WS5** | `MainScreen(home = { HomeScreen(...) })`，/feed。启动默认打开首页。 |
| 课表 `MainTab.Timetable` | WS4 | 网格、导入、手动添加 |
| 我的 `MainTab.Me` | WS4 | 设置、邀请码、检查更新；WS5 加了「我的积分」「积分规则」「提醒设置」 |

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
| `MyPoints`、`PointsRules` | 我的积分、积分规则 |
| `ReminderGuide` | 提醒设置引导（首次进入主界面自动打开一次） |

## WS11 新增页面
| 路由 | 页面 |
|---|---|
| `EditAssignment(id)` | 首发者编辑作业（标题、DDL → `PATCH /assignments/{id}`；说明 = 首发来源正文与图片 → `PATCH /sources/{id}`）。入口：作业详情顶栏「编辑」，仅 `Assignment.is_first_poster` 为真时显示 |
