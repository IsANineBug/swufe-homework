# WS6 交接（部署、备份、发布）

更新：2026-10-16 15:30 +08:00

## 做到哪
- PR #1 `[WS6]`，分支 `ws6-deploy`，未合并。WS7 首轮审计「需修改」，3 个阻塞 + 9 条建议已全部处理（逐条回复在 PR 里），等 WS7 复审。
- 已交付：`deploy/docker-compose.yml`、`deploy/nginx/nginx.conf`、`deploy/postgres/init/`、
  `deploy/scripts/{gen-cert,backup,restore}.sh`、`deploy/README.md`、`.env.example`、
  `.github/workflows/{server,android,deploy-check,release}.yml`。
- 作者裁决（issue #4）中归 WS6 的：第 1 条（Android 变量名用 WS6 那套，保持不变；release.yml 加 secrets 校验和 apksigner verify）、
  第 3 条（`TRUSTED_PROXY=172.28.0.10/32`）、第 4 条（`/_protected/` 缓存 `private, max-age=600`，不超过签名 1 小时有效期）——已完成。
  第 2 条 `HTTP_ADDR` 保持不变，由 WS1 改代码。

## 下一步
1. WS7 复审；有意见继续在 `ws6-deploy` 上改。
2. `.env.example` 和 PR #2（WS1）必冲突：后合并的一方取并集，`TRUSTED_PROXY` 以 `/32` 为准、监听变量以 `HTTP_ADDR` 为准。
3. WS1 合入后确认：Dockerfile `ENV HTTP_ADDR=:8080` 和 HEALTHCHECK、`/healthz`、启动迁移、`TEST_DATABASE_URL`；
   `server.yml` 首次跑通，GHCR 推送后提醒作者把包设为 Public。上传卷属主由 compose 的 `uploads-init` 处理（uid 10001），
   若 WS1 改了 uid 要同步改。
4. WS2：`/files/<相对路径>?exp=&sig=`，验签后 `X-Accel-Redirect: /_protected/<相对 UPLOAD_DIR 的路径>`（api.yaml 由 WS2 写）。
5. WS4 合入后确认 `app/build.gradle.kts` 读 `SIGNING_*`、`SERVER_CERT_PIN`、`SERVER_BASE_URL`、`VERSION_NAME/VERSION_CODE`（裁决 #1，WS4 写进 workstreams.md），
   APK 输出在 `app/build/outputs/apk/release/`，`android.yml` 跑通。
6. WS3：worker 根包可 `go build .`，支持 `-X main.version`；长轮询按 api.yaml 最长 25 秒（nginx 读超时 75 秒）。
7. 三端都有代码后打 `v0.0.1-rc1` 验证 `release.yml`（会发成 prerelease），确认后删掉。
8. 作者配置 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`SERVER_CERT_PIN`、`SERVER_BASE_URL`。
9. 10 月 17 日后作者填 `CAMPUS_CIDRS`（README 第六节）。

## 未解决的问题和坑
- server/release 工作流在对应目录出现前无法实测；server.yml/android.yml 的 paths 不含工作流文件本身，避免目录不存在时误触发失败。
- shellcheck：`# shellcheck disable/source` 指令要紧贴对应行；backup/restore 不再 source `.env`，改用容器内 `$POSTGRES_*`（单引号里，已 disable SC2016）。
- restore.sh 先恢复到 `<DB>_restore` 再改名切换，原库留作 `<DB>_old_<时间>`，需要手动删。
- nginx error_log 设为 crit（防签名 query 进日志），排查时临时调低。
- GHCR 在大陆可能拉不动，README 第三节列了替代方案。
- Docker 端口映射需保留客户端源 IP（默认 iptables DNAT 可以），否则 X-Real-IP 是网关地址。
- 固定网段 172.28.0.0/24 若冲突，按 README「改 Docker 网段」三处一起改。
- 实际服务器部署未做（无服务器权限）。
- 节省 GitHub API：读仓库用 `git clone`，写入用 git data API 一次性建 tree→commit→更新 ref。
