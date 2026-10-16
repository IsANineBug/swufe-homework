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
