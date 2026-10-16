# swufe-worker（电脑端 AI worker）

运行在作者电脑上的单个可执行文件。它**主动连服务器**（家里电脑不需要公网 IP、不需要开端口）：

```
App ──HTTPS──> 服务器 <──HTTPS 长轮询── swufe-worker ──HTTPS_PROXY──> Command Code（DeepSeek V4.1 Flash）
```

- 每 20 秒 `POST /worker/heartbeat`；服务器 60 秒没收到心跳就判离线。
- 长轮询 `POST /worker/jobs/claim`（服务器最多挂 25 秒），领到任务后按 `kind` 组装提示词，图片从服务器签名链接下载后以 base64 交给多模态接口。
- 输出用 JSON Schema（`schemas/*.json`）严格校验，不合格把错误告诉模型重试 1 次，仍失败回传 `failed`；单任务最长 90 秒。
- 日志只记任务编号、种类、耗时和错误，不记作业正文和图片。

契约见 `docs/ai-contract.md`。

## 1. 下载与配置

1. 从 GitHub Releases 下载对应系统的文件：`swufe-worker-windows-amd64.exe`、`swufe-worker-darwin-arm64`（M 系列 Mac）、`swufe-worker-darwin-amd64`、`swufe-worker-linux-amd64`。
   自己编译：`cd worker && go build -o swufe-worker .`（Go 1.23+）。
2. 放到一个固定目录，例如 Windows `C:\swufe-worker\`，macOS `~/swufe-worker/`。macOS 首次运行前：`chmod +x swufe-worker-darwin-arm64 && xattr -d com.apple.quarantine swufe-worker-darwin-arm64`。
3. 在**同一目录**建 `.env`（可从仓库根目录 `.env.example` 的「worker」段复制）：

| 变量 | 说明 |
|---|---|
| `SERVER_URL` | `https://<服务器 IP>:8443/api/v1` |
| `WORKER_TOKEN` | 与服务器 `deploy/.env` 里的 `WORKER_TOKEN` 相同（≥ 24 位随机串） |
| `SERVER_CERT_PIN` | `deploy/scripts/gen-cert.sh` 输出的 `sha256/<base64>`；换证书期间可逗号分隔多个 |
| `LLM_BASE_URL` | Command Code 的 OpenAI 兼容地址（到 `/v1` 为止，程序会拼 `/chat/completions`） |
| `LLM_API_KEY` | 模型 API Key |
| `LLM_MODEL` | 默认 `deepseek-v4.1-flash` |
| `HTTPS_PROXY` | 你的代理，如 `http://127.0.0.1:7890`。**只用于访问模型**；连服务器不走代理 |
| `LLM_JSON_MODE` | 可选，默认 `on`（请求 `response_format=json_object`）；接口不支持时设 `off` |
| `WORKER_CONCURRENCY` | 可选，同时处理的任务数，默认 2（1–8） |
| `ACTIVE_HOURS` | 可选，只在这个时段在线，如 `12-24`（整点，含起不含止） |
| `WORKER_ID` | 可选，默认主机名；多台电脑同时跑时区分用 |

同名的系统环境变量优先于 `.env`。`.env` 里有密钥，**不要提交、不要发给别人**。

4. 先自检：

```
./swufe-worker -check        # Windows: swufe-worker-windows-amd64.exe -check
```

看到「服务器连通、证书固定与 WORKER_TOKEN 均正常」即可。常见报错：
- `服务器证书公钥不匹配`：`SERVER_CERT_PIN` 填错或服务器换了证书。
- `HTTP 401`：两边 `WORKER_TOKEN` 不一致，或服务器没设 `WORKER_TOKEN`。

然后直接运行 `./swufe-worker`，App 里的 AI 按钮会在几秒内变为可用。`Ctrl+C` 退出时会把手上的任务做完再停。

## 2. 开机自启

### Windows（任务计划程序）

以普通用户身份在 PowerShell 里执行（路径按实际修改）：

```powershell
$exe = "C:\swufe-worker\swufe-worker-windows-amd64.exe"
$action  = New-ScheduledTaskAction -Execute $exe -WorkingDirectory "C:\swufe-worker"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "swufe-worker" -Action $action -Trigger $trigger -Settings $set
```

- 立即启动：`Start-ScheduledTask swufe-worker`；停止：`Stop-ScheduledTask swufe-worker`；删除：`Unregister-ScheduledTask swufe-worker`。
- 想不弹黑窗口：在「任务计划程序」里把该任务改为「不管用户是否登录都要运行」。
- 日志：需要时在命令行直接运行程序查看输出；或把操作改成 `cmd /c "...exe >> C:\swufe-worker\worker.log 2>&1"`。

### macOS（launchd）

保存为 `~/Library/LaunchAgents/com.swufe.worker.plist`（把 `YOU` 换成用户名、文件名按芯片选择）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.swufe.worker</string>
  <key>ProgramArguments</key>
  <array><string>/Users/YOU/swufe-worker/swufe-worker-darwin-arm64</string></array>
  <key>WorkingDirectory</key><string>/Users/YOU/swufe-worker</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/Users/YOU/swufe-worker/worker.log</string>
  <key>StandardErrorPath</key><string>/Users/YOU/swufe-worker/worker.log</string>
</dict>
</plist>
```

```
launchctl load -w ~/Library/LaunchAgents/com.swufe.worker.plist     # 启用并立即启动
launchctl unload -w ~/Library/LaunchAgents/com.swufe.worker.plist   # 停用
```

合盖睡眠时 worker 暂停，服务器 60 秒后判离线；唤醒后自动恢复，无需处理。

### Linux（systemd 用户服务）

`~/.config/systemd/user/swufe-worker.service`：

```ini
[Unit]
Description=swufe-worker
After=network-online.target

[Service]
WorkingDirectory=%h/swufe-worker
ExecStart=%h/swufe-worker/swufe-worker-linux-amd64
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
```

`systemctl --user enable --now swufe-worker`；日志 `journalctl --user -u swufe-worker -f`。

## 3. 只在 12:00–24:00 运行

两种做法任选：

1. **推荐**：程序照常开机自启，在 `.env` 里加 `ACTIVE_HOURS=12-24`。时段外 worker 停止心跳和领取任务（进程仍在，几乎不占资源），进入时段自动上线。
2. 用系统计划只在这个时段运行：Windows 把触发器换成「每天 12:00」并设「如果任务运行时间超过 12 小时，停止任务」；macOS 用 `StartCalendarInterval`（Hour=12）启动、再配一个 23:59 执行 `launchctl stop com.swufe.worker` 的任务。

**为什么不影响服务**：AI 按设计是「有就用，没有就手动」。worker 不在线时，`GET /ai/status` 返回 `online:false`，App 把 AI 按钮置灰、提示「电脑未在线，请手动添加」；`POST /ai/jobs` 直接 409 `ai_offline`，**不排队**，所以不会在你开机时涌来一堆过期任务。查重退回服务器的文字匹配、跳过垃圾预检，提醒照常（用 App 内置文案）。已发布的作业、课表、提醒都不依赖 worker。

## 4. 开发与测试

```
cd worker
go test ./...                     # 单测：配置、证书固定、Schema 校验、重试、端到端（假服务器 + 假模型）
WORKER_REGRESSION=1 WORKER_ENV=./.env go test -run TestTimetableRegression -v .
```

课表识别回归测试读取 `docs/seed-timetable/`：每组 `<名字>.png`（或上下两截 `<名字>-1.png`、`<名字>-2.png`）配一份 `<名字>.expected.json`（`{"slots":[...]}`，格式同 `parse_timetable` 输出），调用真实模型后按「星期+节次+教室」配对比较课名。会消耗模型额度，默认跳过。

提示词在 `prompts.go`，Schema 在 `schemas/`。改 `docs/ai-contract.md` 时同步改这两处。
