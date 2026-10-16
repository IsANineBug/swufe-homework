# swufe-homework 后端（/server）

Go 1.23 · chi · pgx/v5 · golang-migrate · slog。接口契约见 `../docs/api.yaml`，表结构见 `db/schema.sql`。

## 目录
```
cmd/server/          入口：读配置 → 执行迁移 → 起 HTTP 服务
db/schema.sql        表结构契约（PostgreSQL 16）
db/migrations/       迁移；0001_init.up.sql 与 schema.sql 内容一致（有单测保证）
internal/config      环境变量配置
internal/httpx       统一 JSON 与错误格式 {code,message}，时间输出 +08:00
internal/auth        argon2id、恢复码、邀请码、JWT、refresh token
internal/netx        客户端 IP（只信任 TRUSTED_PROXY 的 X-Real-IP）、校园网判断、限流
internal/store       公共查询（当前学期、班级装载、成员校验、迁移、连接池）
internal/timetable   课表规范化与自动归班算法
internal/api         HTTP 路由与处理器（WS1：账号与班级）
internal/testdb      集成测试用数据库
```
其他工作线（WS2/WS3）在 `internal/api` 的 `Router()` 里挂自己的路由；写接口校验成员可用 `store.IsMember`，管理员接口用 `api.RequireAdmin`。

## 本地启动
需要 PostgreSQL 16（开启 pg_trgm、btree_gist，迁移会自动 `CREATE EXTENSION`，需要有权限）。

```bash
# 1. 起一个数据库（任选其一）
docker run -d --name hw-pg -e POSTGRES_PASSWORD=dev -e POSTGRES_DB=homework -p 5432:5432 postgres:16

# 2. 配置并启动（启动时自动执行迁移）
cd server
export DATABASE_URL='postgres://postgres:dev@127.0.0.1:5432/homework?sslmode=disable'
export JWT_SECRET="$(openssl rand -hex 32)"
export BOOTSTRAP_ADMIN_TOKEN=dev-boot
export CAMPUS_CIDRS=127.0.0.1          # 本机当成校园网，方便调试注册
go run ./cmd/server

# 3. 试一下
curl localhost:8080/healthz
curl localhost:8080/api/v1/meta
curl -X POST localhost:8080/api/v1/auth/register -H 'X-Bootstrap-Token: dev-boot' \
     -d '{"nickname":"管理员","password":"password123"}'
```

Docker：
```bash
docker build -t swufe-homework-server ./server
# 国内网络：docker build --build-arg GOPROXY=https://goproxy.cn,direct -t swufe-homework-server ./server
```

## 环境变量
| 变量 | 必填 | 说明 |
|---|---|---|
| `DATABASE_URL` | 是 | PostgreSQL 连接串 |
| `JWT_SECRET` | 是 | ≥32 字符，签 access token |
| `ADDR` | 否 | 监听地址，默认 `:8080` |
| `CAMPUS_CIDRS` | 否 | 西财校园网出口，逗号分隔 CIDR 或单 IP；空 = 只能用邀请码注册 |
| `TRUSTED_PROXY` | 否 | 只信任来自这些地址（nginx）的 `X-Real-IP`，逗号分隔 CIDR |
| `BOOTSTRAP_ADMIN_TOKEN` | 否 | 库里没有任何用户时，带请求头 `X-Bootstrap-Token` 注册即成为管理员 |
| `REGISTER_PER_IP_HOUR` | 否 | 同一 IP 每小时注册次数，默认 30（校园网 NAT 共用出口，宽松） |
| `LOG_LEVEL` | 否 | `debug`/`info`/`warn`/`error`，默认 info |

学期无需配置：按校历自动生成和切换（秋季 9/1–次年 2/1，春季 3/1–8/1，2 月和 8 月归入下一学期），切换时旧学期归档为只读。

日志为 JSON（slog），只记方法、路由、状态码、耗时，不记请求体。

## 测试
```bash
go test ./...                                   # 单元测试（无数据库时集成测试自动跳过）
createdb hwtest
TEST_DATABASE_URL='postgres://postgres:dev@127.0.0.1:5432/hwtest?sslmode=disable' go test -p 1 ./...
```
集成测试会清空 `TEST_DATABASE_URL` 指向的库，务必用专用测试库；多个包共用一个库，所以要 `-p 1`。

## 已实现接口（WS1）
| 接口 | 说明 |
|---|---|
| `GET /healthz` | 存活与数据库连通 |
| `GET /api/v1/auth/campus-check` | 是否校园网出口 |
| `POST /api/v1/auth/register` | 邀请码或校园网二选一；首个用户可用 `X-Bootstrap-Token` 注册为管理员；返回一次性恢复码 |
| `POST /api/v1/auth/login` | 昵称 + 密码 |
| `POST /api/v1/auth/refresh` | refresh token 轮换（旧的立即作废） |
| `POST /api/v1/auth/recover` | 恢复码重设密码，返回新恢复码，所有设备下线 |
| `POST /api/v1/auth/logout` | 带 `{refresh_token}` 注销该 token；不带则注销本人全部 |
| `GET /api/v1/meta` | 当前学期、12 节作息、服务器时间 |
| `GET/PATCH /api/v1/me` | 我的信息；改昵称、榜单匿名 |
| `GET/POST /api/v1/me/invites` | 邀请码列表 / 生成（每人 5 个名额，未使用的也占） |
| `GET /api/v1/me/notices` | 最近 100 条通知 |
| `GET /api/v1/me/classes` | 我在当前学期的班级（含时段） |
| `DELETE /api/v1/me/classes/{id}` | 「不是我的班」 |
| `POST /api/v1/timetable/import` | 提交确认后的课表，自动归班 |
| `GET /api/v1/classes/search?q=` | 课名前缀 / pg_trgm 相似度搜索 |
| `GET /api/v1/classes/{id}` | 班级详情；已合并的班返回保留班 |

## 归班算法要点
1. 本次提交里同名的时段合成一组（课名比较忽略空格、全半角、大小写；标了 `truncated` 的课名按前缀匹配）。
2. 每组逐个时段查「同学期 + 同教室 + 同星期 + 节次区间重叠」的已有班：
   - 课名相同 → 命中；课名不同 → 进 `conflicts`，**整组不入库**；
   - 无命中 → 新建班；命中 1 个 → 加入；命中多个 → 并到最早创建的班（成员、时段、作业、积分流水、禁发记录一起搬，旧班 `merged_into`）。
3. 补全该班缺的时段；截断课名遇到完整课名时回填；教师选填，只补空值。
4. 退出班级（「不是我的班」）后再次导入含该课的课表，会按规则重新加入。
5. 全程一个事务；并发时由 `class_slots` 的 EXCLUDE 约束兜底，冲突/死锁自动重试。
6. 教室规范化：去 `@`、全角转半角、去空白、字母大写，如 `@经世楼 Ｇ１０１` → `经世楼G101`。
