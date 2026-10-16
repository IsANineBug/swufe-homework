# 部署（WS6）

目标：中国大陆 2 核 2 GB 服务器，无域名，`https://<公网IP>:8443`，自签证书 + App 公钥固定。

```
deploy/
  docker-compose.yml        postgres:16 + server + nginx
  nginx/nginx.conf          HTTPS、反代 /api、/files 内部转发、限流、X-Real-IP
  postgres/init/            首次初始化开启 pg_trgm、btree_gist
  scripts/gen-cert.sh       生成自签证书并输出公钥 pin
  scripts/backup.sh         每日 pg_dump，保留 7 天
  scripts/restore.sh        从备份恢复
```

## 内存预算（2 GB）

| 组件 | 限制 | 说明 |
|---|---|---|
| postgres | 640 MB | shared_buffers 256MB、work_mem 4MB、max_connections 50 |
| server | 512 MB | Go 进程；compose 已设 `GOMEMLIMIT=400MiB`（`.env` 可改），连接池 ≤ 20 |
| nginx | 64 MB | 1 个 worker |
| 系统 + Docker | ~700 MB | 另建 1–2 GB swap 兜底 |

## 一、从零部署

1. **系统准备**（Ubuntu 22.04/24.04 为例）
   ```bash
   sudo timedatectl set-timezone Asia/Shanghai
   # swap 2G
   sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   # Docker（阿里云镜像源）
   curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker.gpg
   echo "deb [signed-by=/usr/share/keyrings/docker.gpg] https://mirrors.aliyun.com/docker-ce/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list
   sudo apt update && sudo apt install -y docker-ce docker-compose-plugin openssl
   ```
2. **拉代码**
   ```bash
   sudo mkdir -p /opt && cd /opt
   git clone https://github.com/IsANineBug/swufe-homework.git   # 慢的话下载 zip 或从本机 scp
   cd swufe-homework/deploy
   ```
3. **填配置**
   ```bash
   cp ../.env.example .env && chmod 600 .env
   vim .env   # 只填 server、deploy 两段；每个 CHANGE_ME 换成随机值
   ```
   - `POSTGRES_PASSWORD` 与 `DATABASE_URL` 里的密码保持一致。
   - `TRUSTED_PROXY=172.28.0.10/32`（compose 里 nginx 的固定地址），不要改成 0.0.0.0/0 或 172.16.0.0/12。
   - `SEMESTER_NAME/SEMESTER_STARTS_ON/SEMESTER_ENDS_ON` 一般留空，server 按校历自动推算并创建当前学期；
     三个同时填才固定用这个学期，此后每学期都要手动改并重启 server，否则一直停在旧学期。
   - worker 段的变量不要放到服务器上。
4. **证书**
   ```bash
   ./scripts/gen-cert.sh <服务器公网IP>
   ```
   把输出的 `sha256/...` 填到 GitHub Secrets `SERVER_CERT_PIN` 和电脑端 `worker/.env`。
   `certs/server.key` 另存一份到安全的地方：**丢了私钥就得换 pin，旧版 App 全部连不上**。
   证书 10 年有效；到期前用 `./scripts/gen-cert.sh <IP> --renew` 复用私钥续期，pin 不变。
5. **启动**
   ```bash
   docker compose pull      # 或见下文「镜像替代方案」
   docker compose up -d
   curl -k https://127.0.0.1:8443/healthz
   ```
   `uploads-init` 是一次性服务：每次 `up` 时把上传卷属主改成 server 的非 root 用户（uid 10001）、权限 755（nginx 以 uid 101 只读访问）后退出，`docker compose ps -a` 里显示 Exited (0) 是正常的。
6. **放行端口**：云厂商安全组和 `ufw` 只开 22 和 8443。5432 不对外。
7. **首个管理员**：App 用 `BOOTSTRAP_ADMIN_TOKEN` 注册后，把它从 `.env` 清空并 `docker compose up -d server`。
8. **定时备份**：见下文。

## 二、更新

```bash
cd /opt/swufe-homework && git pull
cd deploy
docker compose pull server && docker compose up -d server   # 数据库迁移由 server 启动时执行
docker image prune -f
```
改了 `nginx.conf`：`docker compose exec nginx nginx -t && docker compose exec nginx nginx -s reload`。
升级 postgres 大版本（16→17）需要 dump/restore，不要直接改镜像标签。

## 三、镜像替代方案

国内拉 Docker Hub、GHCR 经常失败。`.env` 里的 `POSTGRES_IMAGE`、`NGINX_IMAGE`、`SERVER_IMAGE` 可以任意替换：

| 方案 | 做法 |
|---|---|
| 默认 | `docker.m.daocloud.io/library/...`（DaoCloud 公共镜像） |
| 换公共加速 | 在 `/etc/docker/daemon.json` 配 `registry-mirrors`，镜像名改回 `postgres:16-alpine`、`nginx:1.27-alpine`；公共加速站时有关停，以当时可用为准 |
| 云厂商 | 阿里云/腾讯云容器镜像服务的个人版加速地址（控制台获取，只对本厂商服务器生效） |
| GHCR 镜像 | `SERVER_IMAGE=ghcr.nju.edu.cn/isaninebug/swufe-homework-server:latest`（南大镜像，可用性不保证） |
| 本机中转 | 在能拉的电脑上 `docker pull` → `docker save ... \| gzip > img.tgz` → `scp` → 服务器 `docker load < img.tgz` |
| 服务器构建 | `docker build -t swufe-server ../server`，`.env` 设 `SERVER_IMAGE=swufe-server`（需要 Go 模块代理：Dockerfile 里设 `GOPROXY=https://goproxy.cn,direct`） |

GHCR 包默认私有：首次推送后在 GitHub → Packages → swufe-homework-server → Package settings 改为 Public，服务器才能匿名拉取。

## 四、Nginx 约定（与 WS1/WS2 对接）

- 对外只有 8443；`/api/` 原样反代到 `server:8080`，基础路径 `/api/v1`。server 监听地址变量统一为 `HTTP_ADDR`（默认 `:8080`，作者裁决 issue #4 第 2 条）。
- `X-Real-IP` 由 nginx 用 TCP 对端地址覆盖，客户端伪造的头不会透传。server 只在请求来自 `TRUSTED_PROXY` 时读它。
- 限流（按 IP）：`/api/` 20 r/s、突发 100；`/api/v1/auth/` 5 r/s、突发 20。校园网共用 NAT 出口，所以放得很宽，注册限流在 server 内做。超限返回 429。
- `/api/v1/worker/` 读超时 75 秒，覆盖 `/worker/jobs/claim` 长轮询（api.yaml 规定最长挂起 25 秒）。
- `client_max_body_size 11m`（上传上限 10 MB + multipart 开销）。
- **文件**（api.yaml，作者裁决 issue #4 第 4 条）：外部 URL 为 `/files/<相对路径>?exp=<unix 秒>&sig=<HMAC-SHA256>`，有效期 1 小时。
  请求先到 server 校验签名和过期时间；通过后 server 返回空 body 和 `X-Accel-Redirect: /_protected/<相对 UPLOAD_DIR 的路径>` 以及 `Content-Type`，
  nginx 从共享卷读文件返回。`/_protected/` 是 internal，外部直接访问为 404。响应 `Cache-Control: private, max-age=600`，不超过签名有效期。
- access log 只记录方法和路径，不记录 query（签名）和请求体。error log 会带完整请求行，所以级别设为 `crit`，限流拒绝不写日志；
  排查问题时可临时改成 `error_log /var/log/nginx/error.log error;` 并 reload，查完改回。即便泄露，签名 1 小时后也失效。

### 改 Docker 网段

compose 固定用 `172.28.0.0/24`（nginx `.10`、postgres `.20`、server `.30`）。如果和云主机 VPC 或其他 Docker 网络冲突（`ip route` 能看到 172.28.x），
改成例如 `10.213.0.0/24`，**三处一起改**：`docker-compose.yml` 的 `subnet` 和三个 `ipv4_address`、`.env` 的 `TRUSTED_PROXY`（改成新的 nginx 地址 `/32`），
然后 `docker compose down && docker compose up -d`（数据在命名卷里，不会丢）。

## 五、备份与恢复

```bash
sudo crontab -e
30 3 * * * /opt/swufe-homework/deploy/scripts/backup.sh >> /var/log/swufe-backup.log 2>&1
```
- 每天 03:30（服务器时区须为 Asia/Shanghai）`pg_dump -Fc` 到 `/var/backups/swufe-homework/`，保留 7 天，写入前用 `pg_restore --list` 校验。
- 上传目录不备份（图片按学期清理）。
- 建议每周把最新一份 `scp` 到自己电脑或对象存储，防止整机故障。

**恢复**：
```bash
cd /opt/swufe-homework/deploy
ls /var/backups/swufe-homework/
sudo ./scripts/restore.sh /var/backups/swufe-homework/homework-YYYYMMDD-0330.dump   # 输入 yes 确认
```
备份文件由 root 的 cron 生成、权限 600，所以要 `sudo`。脚本先把备份恢复到临时库 `homework_restore`，成功后才停 server，
把原库改名为 `homework_old_<时间>`、临时库改名为 `homework`，再启动 server。确认数据无误后按脚本最后的提示删掉旧库。
建议每学期初在测试机上演练一次。

**恢复失败怎么办**：
- 第 1 步（pg_restore）失败：脚本已删掉临时库，原库和 server 都没动，看报错（常见是备份文件损坏，换前一天的）。
- 第 2 步（改名）失败：server 处于停止状态。`docker compose exec postgres psql -U homework -d postgres -c '\l'` 看现有库：
  若 `homework` 不存在而 `homework_old_*` 在，执行 `ALTER DATABASE "homework_old_<时间>" RENAME TO homework;` 回滚；然后 `docker compose start server`。
- 备份脚本、恢复脚本都不读取 `.env`，库名和用户取 postgres 容器自己的环境变量。

## 六、填 CAMPUS_CIDRS

1. 在西财校园网（宿舍有线、校园 Wi-Fi 各测一次，不开代理）打开 `https://ip.cn` 或 `curl ip.sb`，记下出口 IP。
2. 多次、多地点测，出口可能是一个网段；用 `whois <IP>` 查所属网段，确认是学校/CERNET 的地址。
3. 写成 CIDR，逗号分隔，例如 `CAMPUS_CIDRS=202.115.x.0/24,222.18.y.0/24`（以实测为准）。
4. `docker compose up -d server` 生效。用 App 的 `/auth/campus-check` 在校园网内外各验证一次。
5. 留空 = 只能邀请码注册。

## 七、监控磁盘

预计每学期图片约 2 GB，server 只保留当前和上一学期（约 4–5 GB 上限），加数据库和 7 份备份。建议磁盘 ≥ 40 GB。

```bash
df -h /                                  # 总体
docker system df                         # 镜像、卷
du -sh /var/lib/docker/volumes/swufe-homework_uploads
du -sh /var/backups/swufe-homework
```
简单告警（每小时，超过 80% 写日志；可改成发邮件/Server 酱）：
```bash
0 * * * * [ $(df --output=pcent / | tail -1 | tr -dc 0-9) -gt 80 ] && echo "$(date) disk >80%" >> /var/log/swufe-disk.log
```
Docker 日志限制：`/etc/docker/daemon.json` 加 `"log-driver":"json-file","log-opts":{"max-size":"10m","max-file":"3"}`。
带宽：图片已压缩到约 1 MB、缩略图 400px；小带宽服务器上注意高峰时段，必要时在 nginx 对 `/_protected/` 加 `limit_rate`。

## 八、CI/CD（.github/workflows）

| 工作流 | 触发 | 内容 |
|---|---|---|
| `server.yml` | PR / push main（`server/**`） | `go test -race`（带 postgres 服务，`TEST_DATABASE_URL`）+ golangci-lint；main 推镜像到 GHCR（`latest`、`sha-<commit>`） |
| `android.yml` | PR（`android/**`） | `testDebugUnitTest lintDebug` |
| `deploy-check.yml` | PR（`deploy/**`） | `docker compose config`、`nginx -t`、shellcheck |
| `release.yml` | tag `v*` | release APK（arm64-v8a，签名与 pin 来自 Secrets）+ worker 四平台二进制 → 同一个 GitHub Release，附 SHA256SUMS |

需要在仓库 Settings → Secrets and variables → Actions 配置：
`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`SERVER_CERT_PIN`、`SERVER_BASE_URL`。

变量名（作者裁决 issue #4 第 1 条，唯一一套）：release.yml 把 Secrets 映射成 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`、
`SERVER_CERT_PIN`、`SERVER_BASE_URL`、`VERSION_NAME`、`VERSION_CODE` 传给 Gradle。任一 Secret 为空、产出未签名 APK 或 `apksigner verify` 失败，release 直接失败，不会发出去。

发版：`git tag v0.1.0 && git push origin v0.1.0`。带 `-` 的 tag（如 `v0.0.1-rc1`）发成预发布（prerelease），App 检查更新应只看正式 Release。
