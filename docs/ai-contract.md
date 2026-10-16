# AI 契约

## 架构
```
App ──HTTPS──> 服务器(Go) <──HTTPS 长轮询── 你电脑上的 worker ──代理──> Command Code (DeepSeek V4.1 Flash)
```
- worker 主动连服务器，服务器不需要访问家里电脑。
- worker 每 20 秒 `POST /worker/heartbeat`；心跳超过 60 秒视为离线。
- **离线不排队**：`GET /ai/status` 返回 `online:false` 时 App 把 AI 按钮置灰；`POST /ai/jobs` 返回 `409 ai_offline`。
- 任务 2 分钟内未完成即过期（`expired`），App 提示改为手动录入。
- worker 认证用 `.env` 里的 `WORKER_TOKEN`（Bearer）。模型名、代理地址、API Key 只放 worker 本机 `.env`。
- **AI 只出草稿**：输出永远回给发起的用户确认，服务器不会凭 AI 输出直接写业务表、删除或隐藏任何内容。

## 通用约定
- 所有输出必须是**单个 JSON 对象**，不能有多余文字。worker 用 JSON Schema 校验，失败重试 1 次，仍失败则 `failed`。
- 时间输出 ISO 8601，带 `+08:00`。
- 拿不准的字段给 `null` 并把字段名放进 `uncertain` 数组。
- 服务器给每个任务附 `context`：
```json
{
  "today": "2026-10-16",
  "weekday": 5,
  "timezone": "Asia/Shanghai",
  "periods": [{"no":1,"start":"08:30","end":"09:15"}, "...共 12 节"],
  "class": {"name":"概率论与数理统计B","slots":[{"weekday":1,"start":6,"end":7,"room":"经世楼G101"},{"weekday":5,"start":5,"end":6,"room":"经世楼G101"}]}
}
```
`class` 只在与某个班有关的任务里出现；AI **不判断作业属于哪门课**。

---

## 1. parse_assignment：解析作业
**输入**：`{context, text?, image_urls?[]}`（图片为服务器签名的临时 URL，最多 6 张）
**输出**：
```json
{
  "title": "第三章习题 3.1–3.8",
  "requirements": "手写拍照，写清步骤",
  "ddl": "2026-10-23T13:55:00+08:00",
  "ddl_source": "下周五上课前",
  "submit_method": "学习通作业",
  "uncertain": []
}
```
- 相对日期按 `context.today` 和班级时段换算：「下节课前」= 下一个上课时段的开始时间；「下周五」= 下周的周五；只说日期不说时间时默认 23:59。
- 换算有歧义时 `ddl` 仍给最可能的值，同时把 `"ddl"` 放进 `uncertain`，App 标「待确认」。
- 标题 ≤ 30 字，提炼，不照抄整段。

## 2. parse_timetable：识别课表
**输入**：`{context(无 class), image_urls[1..2]}`（易校园截图）
**输出**：
```json
{
  "slots": [
    {"name":"概率论与数理统计B","room":"经世楼G101","weekday":1,"start":6,"end":7,"truncated":false,"start_visible":true},
    {"name":"形势与政策III","room":null,"weekday":3,"start":4,"end":4,"truncated":true,"start_visible":true}
  ]
}
```
- 节次按左侧时间轴和课块高度对照 12 节作息推算；课块从截图上方被裁掉时 `start_visible:false`，`start` 给能看到的最早节。
- 多张截图（上下两截）按节次拼接，重复课块只出一次。
- 教室规范化为「楼栋+房间号」，去掉 `@`；灰色「非本周」的课也要输出。
- 服务器之后做：校验范围 → 同一用户同名合成一个班 → 按「课名+教室+星期+节次」任一段匹配已有班 → 都不中则新建 → 一人命中两个班则自动合并。这些都不是 AI 的工作。

## 3. dedupe_review：语义查重
**输入**：`{context, draft:{title, body, ddl}, candidates:[{id,title,body,ddl}...≤3]}`（候选由服务器按近 7 天、DDL ±1 天、pg_trgm 相似度选出）
**输出**：
```json
{"matches":[{"id":123,"same":true,"confidence":0.86,"reason":"同为第三章习题，DDL 相同"}]}
```
App 只把 `same:true` 的排在前面提示「可能是同一份作业，作为补充发布？」，用户自己选。

## 4. ddl_conflict：DDL 冲突
**输入**：`{context, assignment:{title}, sources:[{id, body, stated_ddl}]}`
**输出**：
```json
{"conflict":true,"values":["2026-10-23T13:55:00+08:00","2026-10-24T23:59:00+08:00"],"note":"补充里写的是周六晚上"}
```
服务器只据此把 `assignments.ddl_conflict` 标红，不改 DDL。

## 5. spam_check：垃圾预检
**输入**：`{context, title, body, image_urls?}`
**输出**：
```json
{"suspicious":false,"category":null,"reason":""}
```
`category` ∈ `ad | unrelated | abusive | gibberish | null`。只在发布前提示发布者本人「内容看起来与作业无关，确定发布吗？」，不拦截、不通知别人。离线时跳过。

## 6. reminder_text：提醒文案（可选）
**输入**：`{assignment:{title, ddl, class_name}, slot:"2d|1d|12h"}`
**输出**：`{"title":"概率论作业后天截止","body":"第三章习题，周五 13:55 前交到学习通"}`
- ≤ 40 字，不要表情，不要催促语气。离线或失败时 App 用内置模板，提醒照常。
- 文案在作业发布或 DDL 变更时预先生成并随作业同步，提醒本身仍由手机本地 AlarmManager 触发。

---

## 提示词骨架（worker 内置，可迭代）
```
system: 你是作业信息整理助手。只输出符合给定 JSON Schema 的单个 JSON 对象，不输出任何解释。
不确定的字段填 null 并写入 uncertain。不要猜测作业属于哪门课。
user: <context JSON>
<任务说明（按 kind 取上面对应小节）>
<输入 JSON / 图片>
```
