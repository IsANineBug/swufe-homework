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
| server | 512 MB | Go 进程；建议 server 内 `GOMEMLIMIT=400MiB`，连接池 ≤ 20 |
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
   - `TRUSTED_PROXY=172.28.0.10/32`（compose 里 nginx 的固定地址），不要改成 0.0.0.0/0。
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

- 对外只有 8443；`/api/` 原样反代到 `server:8080`（`HTTP_ADDR`），基础路径 `/api/v1`。
- `X-Real-IP` 由 nginx 用 TCP 对端地址覆盖，客户端伪造的头不会透传。server 只在请求来自 `TRUSTED_PROXY` 时读它。
- 限流（按 IP）：`/api/` 20 r/s、突发 100；`/api/v1/auth/` 5 r/s、突发 20。校园网共用 NAT 出口，所以放得很宽，注册限流在 server 内做。超限返回 429。
- `/api/v1/worker/` 读超时 75 秒，供 `/worker/jobs/claim` 长轮询（server 端长轮询请控制在 60 秒内）。
- `client_max_body_size 11m`（上传上限 10 MB + multipart 开销）。
- **文件**：外部 URL 为 `/files/...?...签名`，请求先到 server 校验签名和过期时间；通过后 server 返回空 body 和
  `X-Accel-Redirect: /_protected/<semester>/<yyyy-mm>/<uuid>`（路径相对 `UPLOAD_DIR`）以及 `Content-Type`，
  nginx 从共享卷读文件返回。`/_protected/` 是 internal，外部直接访问为 404。
- access log 只记录方法和路径，不记录 query（签名）和请求体。

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
./scripts/restore.sh /var/backups/swufe-homework/homework-YYYYMMDD-0330.dump   # 输入 yes 确认
```
脚本会停 server、重建数据库、`pg_restore`、再启动 server。建议每学期初在测试机上演练一次。

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

发版：`git tag v0.1.0 && git push origin v0.1.0`。
