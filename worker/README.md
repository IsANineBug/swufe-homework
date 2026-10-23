# swufe-worker（服务器上的 AI worker）

单个可执行文件，和后端部署在**同一台服务器**（2 核 2 GB）上常驻，由 systemd 或 docker 管理、崩溃自动重启。它**主动连后端**拉任务（worker 不需要开任何端口）：

```
App ──HTTPS──> nginx ──> server <──HTTPS 长轮询── swufe-worker ──（可选 HTTPS_PROXY）──> Command Code（Gemini 3.8 Flash，思考 high）
```

- 每 20 秒 `POST /worker/heartbeat`；服务器 60 秒没收到心跳就判离线。
- 长轮询 `POST /worker/jobs/claim`（服务器最多挂 25 秒），领到任务后按 `kind` 组装提示词，图片从服务器签名链接下载后以 base64 交给多模态接口。
- 输出用 JSON Schema（`schemas/*.json`）严格校验，不合格把错误告诉模型重试 1 次，仍失败回传 `failed`；单任务最长 90 秒（服务器 2 分钟过期）。
- **不重试**的情况：模型因输出上限停止（`finish_reason=length`，回传失败原因「输出被截断」）、调用超时、剩余时间不足 30 秒。
- 请求体带 `max_tokens`：`parse_timetable`、`parse_assignment` 用满 `LLM_MAX_TOKENS`，其余文字任务用 `min(LLM_MAX_TOKENS, 16384)`。默认不传 `temperature`。
- 内存控制在 64 MB 以内：`WORKER_CONCURRENCY` 默认 2；图片**一张张下载、直接写进临时文件**，调用模型时边读文件边 base64 编码写进请求体，不把整批图片读进内存（识图每个任务最多 2 张、解析作业最多 6 张，单张 ≤ 10 MB）；任务结束删临时文件。没设 `GOMEMLIMIT` 时程序自带 48 MiB 的 Go 软上限，硬上限交给 systemd `MemoryMax=64M` 或 docker `mem_limit: 64m`。
- 日志只记任务编号、种类、耗时和错误，不记作业正文和图片。每次模型调用另记一行「模型调用」：`kind`、`ms`、`prompt_tokens`、`completion_tokens`（含思考）、`reasoning_tokens`（接口给了才有）、`finish_reason`。

契约见 `docs/ai-contract.md`。

## 1. 下载与配置

1. 在服务器上从 GitHub Releases 下载 `swufe-worker-linux-amd64`（ARM 服务器自己编译）。
   自己编译：`cd worker && CGO_ENABLED=0 go build -o swufe-worker .`（Go 1.23+）。
2. 放到固定目录，例如 `/opt/swufe-worker/`：

```
sudo mkdir -p /opt/swufe-worker
sudo install -m 755 swufe-worker-linux-amd64 /opt/swufe-worker/swufe-worker
```

3. 在**同一目录**建 `.env`（从仓库根目录 `.env.example` 的「worker」段复制；这是 worker 自己的 `.env`，和 `deploy/.env` 分开放），`sudo chmod 600 /opt/swufe-worker/.env`：

| 变量 | 说明 |
|---|---|
| `SERVER_URL` | `https://<服务器 IP>:8443/api/v1`。同机也走 nginx 的 HTTPS 端口，可直接写 `https://127.0.0.1:8443/api/v1`（证书固定只比对公钥，不看 IP） |
| `WORKER_TOKEN` | 与服务器 `deploy/.env` 里的 `WORKER_TOKEN` 相同（≥ 24 位随机串） |
| `SERVER_CERT_PIN` | `deploy/scripts/gen-cert.sh` 输出的 `sha256/<base64>`；换证书期间可逗号分隔多个 |
| `LLM_BASE_URL` | Command Code 的 OpenAI 兼容地址（到 `/v1` 为止，程序会拼 `/chat/completions`） |
| `LLM_API_KEY` | 模型 API Key |
| `LLM_MODEL` | 默认 `google/gemini-3.8-flash`（Command Code 模型清单里的 id；思考等级由 Command Code 固定为 high）。不确定时用 `curl -H "Authorization: Bearer $LLM_API_KEY" $LLM_BASE_URL/models` 核对 |
| `LLM_MAX_TOKENS` | 可选，输出上限（含思考），默认 `32768`，允许 4096–65536，超范围启动即报配置错误。识图/解析作业用满，其余文字任务取 `min(此值, 16384)` |
| `LLM_TEMPERATURE` | 可选，默认留空 = 不传该字段，让模型用默认值；需要时填 0–2 |
| `HTTPS_PROXY` | **只用于访问模型**，连后端不走代理。以服务器实际网络为准：服务器能直连 Command Code（`curl -sS -o /dev/null -w '%{http_code}\n' $LLM_BASE_URL/models` 能返回 200/401）就**留空**；连不上就填服务器上能用的代理地址，如 `http://10.0.0.5:3128`（不是电脑上 Clash 的 `127.0.0.1:7890`） |
| `LLM_JSON_MODE` | 可选，默认 `on`（请求 `response_format=json_object`）；接口不支持时设 `off` |
| `WORKER_CONCURRENCY` | 可选，同时处理的任务数，默认 2（1–8） |
| `ACTIVE_HOURS` | 可选，服务器上一般不设（留空 = 一直在线）；需要时填如 `12-24`（整点，含起不含止） |
| `WORKER_ID` | 可选，默认主机名；同时跑多个 worker 时区分用 |
| `GOMEMLIMIT` | 可选，Go 内存软上限；不设时程序自带 48 MiB。注意它只认**进程环境变量**（systemd `Environment=` / compose `environment:`），写在 `.env` 里不生效 |

同名的系统环境变量优先于 `.env`。`.env` 里有密钥，**不要提交、不要发给别人**。

4. 先自检：

```
cd /opt/swufe-worker && ./swufe-worker -check
```

看到「服务器连通、证书固定与 WORKER_TOKEN 均正常」即可。常见报错：
- `服务器证书公钥不匹配`：`SERVER_CERT_PIN` 填错或服务器换了证书。
- `HTTP 401`：两边 `WORKER_TOKEN` 不一致，或服务器没设 `WORKER_TOKEN`。

然后按下一节交给 systemd 或 docker 常驻，App 里的 AI 按钮会在几秒内变为可用。收到 `SIGTERM`（`systemctl stop`、`docker stop`）时会把手上的任务做完再停。

## 2. 在服务器上常驻（崩溃自动重启）

两种方式二选一。

### systemd（推荐）

```
sudo useradd --system --no-create-home --shell /usr/sbin/nologin swufe-worker
sudo chown -R swufe-worker: /opt/swufe-worker
```

保存为 `/etc/systemd/system/swufe-worker.service`：

```ini
[Unit]
Description=swufe-worker (AI worker)
After=network-online.target docker.service
Wants=network-online.target

[Service]
User=swufe-worker
WorkingDirectory=/opt/swufe-worker
ExecStart=/opt/swufe-worker/swufe-worker -env /opt/swufe-worker/.env
Restart=always
RestartSec=5
# 和后端同机：硬上限 64M，Go 软上限 48MiB
MemoryMax=64M
Environment=GOMEMLIMIT=48MiB
# 收到 SIGTERM 后最多等 95 秒把手上的任务做完（单任务上限 90 秒）
TimeoutStopSec=95
PrivateTmp=true
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl daemon-reload
sudo systemctl enable --now swufe-worker      # 开机自启并立即启动
sudo systemctl restart swufe-worker           # 改了 .env 或换了程序后重启
sudo systemctl status swufe-worker            # 状态，含 Memory: 当前占用
journalctl -u swufe-worker -f                 # 跟日志
journalctl -u swufe-worker --since "1 hour ago" | grep 模型调用   # 看用量
```

验证崩溃自动重启：`sudo systemctl kill -s KILL swufe-worker`，5 秒后 `systemctl status swufe-worker` 应重新是 `active (running)`，`journalctl -u swufe-worker` 里能看到又一行「swufe-worker 启动」。

### docker compose

不改 `deploy/docker-compose.yml` 的话，把下面的片段加到服务器上的 compose 文件（或单独的 `docker-compose.worker.yml`）。镜像用 `alpine` 直接挂载 Release 里的 linux 二进制，不需要另做镜像：

```yaml
services:
  worker:
    image: docker.m.daocloud.io/library/alpine:3.20
    command: ["/opt/swufe-worker/swufe-worker", "-env", "/opt/swufe-worker/.env"]
    volumes:
      - /opt/swufe-worker:/opt/swufe-worker:ro
    environment:
      GOMEMLIMIT: 48MiB
    restart: unless-stopped
    mem_limit: 64m
    stop_grace_period: 95s
    # 用宿主机网络：SERVER_URL 可直接写服务器 IP 或 127.0.0.1:8443；也可以改成加入 compose 网络后访问 https://nginx:443
    network_mode: host
```

```
docker compose up -d worker
docker compose logs -f worker
docker compose restart worker
docker stats --no-stream            # 看 MEM USAGE / LIMIT
```

验证崩溃自动重启：`docker kill -s KILL <worker 容器名>`，几秒后 `docker compose ps` 里 worker 重新是 `Up`（`restart: unless-stopped` 只有手动 `docker compose stop` 才不拉起）。

### 内存实测与自测方法

- 实测（本 PR，linux/amd64）：空闲 RSS 约 8.4 MB；2 个任务并发、每个 2 张 10 MB 图片（最坏情况）连续跑 3 轮，含测试用的假服务器在内整个进程峰值 RSS（VmHWM）约 24.6 MB。`go test -run TestStreamBodyMemory -v .` 会打印 6 张 10 MB 图片（请求体约 80 MB）发送期间的分配量（不到 1 MB）。
- 服务器上自测：`systemctl status swufe-worker` 的 `Memory:`、`docker stats`，或 `grep -E 'VmRSS|VmHWM' /proc/$(pidof swufe-worker)/status`（VmHWM 是进程启动以来的峰值）。

**worker 不在线时不影响服务**：AI 按设计是「有就用，没有就手动」。worker 不在线时，`GET /ai/status` 返回 `online:false`，App 把 AI 按钮置灰、提示「AI 暂时不可用，请手动添加」；`POST /ai/jobs` 直接 409 `ai_offline`，**不排队**，所以重启后不会涌来一堆过期任务。查重退回服务器的文字匹配、跳过垃圾预检，提醒照常（用 App 内置文案）。已发布的作业、课表、提醒都不依赖 worker。

## 3. 开发与测试

```
cd worker
go test ./...                     # 单测：配置、证书固定、Schema 校验、重试与保险（截断/超时/<30 秒不重试）、图片落盘与流式请求体、端到端（假服务器 + 假模型）
WORKER_REGRESSION=1 WORKER_ENV=./.env go test -run TestTimetableRegression -v .
```

课表识别回归测试读取 `docs/seed-timetable/`：每组 `<名字>.png`（或上下两截 `<名字>-1.png`、`<名字>-2.png`）配一份 `<名字>.expected.json`（`{"slots":[...]}`，格式同 `parse_timetable` 输出），调用真实模型后按「星期+节次+教室」配对比较课名。会消耗模型额度，默认跳过。

常见失败原因：「输出被截断」= 思考加输出超过上限，可把 `LLM_MAX_TOKENS` 调大（最多 65536）；「超时（90 秒）」= 模型太慢，看日志里的 `ms` 和 `completion_tokens`。

提示词在 `prompts.go`，Schema 在 `schemas/`。改 `docs/ai-contract.md` 时同步改这两处。
