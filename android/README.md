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

服务器地址和证书 pin 在构建时注入（**不要提交真实值**）：

| 来源 | 地址 | pin |
|---|---|---|
| CI（GitHub Secrets → 环境变量） | `SWUFE_SERVER_URL` | `SWUFE_CERT_PIN` |
| 本地 `~/.gradle/gradle.properties` | `swufe.serverUrl` | `swufe.certPin` |

- `SWUFE_SERVER_URL` 形如 `https://<IP>:8443/api/v1/`。
- `SWUFE_CERT_PIN` 是 deploy 脚本输出的 `sha256/<base64>`，换证书时可逗号分隔多个。
- pin 为空时退回系统 CA 校验（只用于本地对接有正规证书的测试服务器）；有 pin 时只接受公钥匹配的证书。
- release 签名读 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；
  版本号读 `VERSION_NAME`（如 `0.2.0`，与 tag `v0.2.0` 对应，检查更新靠它比较）和 `VERSION_CODE`。

## 已实现（WS4）
- 注册：先 `/auth/campus-check`，非校园网要求邀请码；成功后强制展示恢复码，勾选「我已妥善保存」才能继续，屏蔽返回键。
- 登录、用恢复码找回（展示新恢复码）、退出登录（清本地缓存）。
- 设置：昵称、榜单匿名开关；我的邀请码：列表、复制、剩余名额、生成。
- 课表：周一到周五 × 12 节网格，作息取 `/meta`（离线用内置西财作息）。
- 导入：选 1–2 张截图 → 压缩上传 `/uploads` → `/ai/status` 在线才发 `parse_timetable` → 1.5 秒轮询最多 2 分钟
  → 确认页逐条可改（课名、教室、星期、起止节；截断课名和被裁掉的起节标黄）→ `/timetable/import`，
  conflicts 留在确认页标红让用户改名重提。
- AI 离线时导入按钮置灰并提示「电脑未在线，请手动添加」；手动添加可用 `/classes/search` 搜已有课一键填入。
- 班级页「不是我的班」→ `DELETE /me/classes/{id}`。
- 启动时检查 GitHub Releases latest（自动检查最多 6 小时一次，避免匿名 API 限额），版本更高则提示下载 APK。
