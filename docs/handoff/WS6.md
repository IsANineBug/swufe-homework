# WS6 交接（部署、备份、发布）

更新：2026-10-16 14:25 +08:00

## 做到哪
- PR #1 `[WS6]`，分支 `ws6-deploy`，未合并，等 WS7 审计（截至更新时无 review）。
- 已交付：`deploy/docker-compose.yml`、`deploy/nginx/nginx.conf`、`deploy/postgres/init/`、
  `deploy/scripts/{gen-cert,backup,restore}.sh`、`deploy/README.md`、`.env.example`、
  `.github/workflows/{server,android,deploy-check,release}.yml`。
- `deploy-check` 工作流已通过（compose config、nginx -t、shellcheck）。

## 下一步
1. 处理 WS7 的审计意见，修改后推同一分支。
2. WS1 骨架合入后：确认 `/server/Dockerfile` 存在、监听 `HTTP_ADDR`（:8080）、有 `/healthz`、启动跑迁移、测试读 `TEST_DATABASE_URL`；
   看 `server.yml` 首次跑通（go test + golangci-lint v1.61），main 推 GHCR 成功后提醒作者把包设为 Public。
3. WS2：文件下载按约定返回 `X-Accel-Redirect: /_protected/<相对 UPLOAD_DIR 的路径>`。
4. WS4 骨架合入后：确认 `app/build.gradle.kts` 读 `SIGNING_*`、`SERVER_CERT_PIN`、`SERVER_BASE_URL`、`VERSION_NAME/VERSION_CODE`，
   APK 输出在 `app/build/outputs/apk/release/`，`android.yml` 跑通。
5. WS3：worker 根包可 `go build .`，支持 `-X main.version`；长轮询 ≤60 秒。
6. 三端都有代码后打一个测试 tag（如 `v0.0.1-rc1`）验证 `release.yml`，确认后删掉该 Release。
7. 作者配置 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`SERVER_CERT_PIN`、`SERVER_BASE_URL`。
8. 10 月 17 日后作者填 `CAMPUS_CIDRS`（README 第六节）。

## 未解决的问题和坑
- server/release 工作流在对应目录出现前无法实测；server.yml/android.yml 的 paths 不含工作流文件本身，避免目录不存在时误触发失败。
- shellcheck：`# shellcheck source=/dev/null` 必须紧贴 `. ./.env` 那一行，不能放在 `set -a; . ./.env` 的复合行前。
- GHCR 在大陆可能拉不动，README 第三节列了替代方案（DaoCloud、南大镜像、docker save/scp、服务器本地构建）。
- Docker 端口映射需保留客户端源 IP（默认 iptables DNAT 可以；不要只走 userland-proxy），否则 nginx 拿到的 X-Real-IP 是网关地址，校园网判断和限流失效。
- 实际服务器部署未做（无服务器权限），按 deploy/README.md 第一节操作。
- 节省 GitHub API：读仓库用 `git clone`，写入用 git data API 一次性建 tree→commit→更新 ref。
