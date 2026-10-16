-- swufe-homework 数据库结构（PostgreSQL 16）
-- 时间一律 timestamptz，业务时区 Asia/Shanghai。
-- 用户可见的「删除」在库里都是软删除（deleted_at），只有管理员能恢复。

CREATE EXTENSION IF NOT EXISTS pg_trgm;     -- 作业标题相似度
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- 时段不重叠约束

-- ---------- 学期与作息 ----------
CREATE TABLE semesters (
  id          serial PRIMARY KEY,
  name        text NOT NULL UNIQUE,            -- 如 2026-2027-1
  starts_on   date NOT NULL,
  ends_on     date NOT NULL,
  is_current  boolean NOT NULL DEFAULT false,
  archived    boolean NOT NULL DEFAULT false   -- 归档后只读
);
CREATE UNIQUE INDEX one_current_semester ON semesters (is_current) WHERE is_current;

-- 西财 12 节作息，种子数据见文件末尾
CREATE TABLE periods (
  no        smallint PRIMARY KEY CHECK (no BETWEEN 1 AND 12),
  starts_at time NOT NULL,
  ends_at   time NOT NULL
);

-- ---------- 用户、邀请、登录 ----------
CREATE TABLE users (
  id                   bigserial PRIMARY KEY,
  nickname             text NOT NULL UNIQUE CHECK (char_length(nickname) BETWEEN 1 AND 20),
  password_hash        text NOT NULL,          -- argon2id
  recovery_code_hash   text NOT NULL,          -- 注册时生成一次，明文只返回一次
  inviter_id           bigint REFERENCES users(id),  -- 校园网注册为 NULL（邀请树根）
  registered_via       text NOT NULL CHECK (registered_via IN ('invite','campus','bootstrap')),
  invite_quota         smallint NOT NULL DEFAULT 5,
  role                 text NOT NULL DEFAULT 'user' CHECK (role IN ('user','admin')), -- admin = 管理员
  leaderboard_anonymous boolean NOT NULL DEFAULT false,
  created_at           timestamptz NOT NULL DEFAULT now(),
  disabled_at          timestamptz
);

CREATE TABLE invite_codes (
  code        text PRIMARY KEY,                -- 8 位，去掉易混字符
  owner_id    bigint NOT NULL REFERENCES users(id),
  used_by     bigint UNIQUE REFERENCES users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  used_at     timestamptz
);
CREATE INDEX invite_codes_owner ON invite_codes (owner_id);

CREATE TABLE auth_tokens (
  token_hash  text PRIMARY KEY,                -- sha256(refresh token)
  user_id     bigint NOT NULL REFERENCES users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz
);
CREATE INDEX auth_tokens_user ON auth_tokens (user_id);  -- 过期/已撤销的 token 由 WS2 定时任务清理

-- ---------- 班级（教学班）与时段 ----------
-- 识别规则：同一用户课表里同名课 = 一个班；课名+教室+星期+节次任一段对上 = 同一班；
-- 都对不上则新建；一个人的时段同时对上两个班 = 自动合并两个班（merged_into）。
CREATE TABLE classes (
  id           bigserial PRIMARY KEY,
  semester_id  int NOT NULL REFERENCES semesters(id),
  name         text NOT NULL,
  teacher      text,                           -- 选填
  name_truncated boolean NOT NULL DEFAULT false, -- 截图里课名被截断
  merged_into  bigint REFERENCES classes(id),  -- 自动合并后指向保留的班
  created_by   bigint NOT NULL REFERENCES users(id),
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX classes_name_trgm ON classes USING gin (name gin_trgm_ops);

CREATE TABLE class_slots (
  id            bigserial PRIMARY KEY,
  class_id      bigint NOT NULL REFERENCES classes(id),
  semester_id   int NOT NULL REFERENCES semesters(id),
  room          text NOT NULL,                 -- 规范化：楼栋+房间号，如「经世楼G101」
  weekday       smallint NOT NULL CHECK (weekday BETWEEN 1 AND 5),
  start_period  smallint NOT NULL CHECK (start_period BETWEEN 1 AND 12),
  end_period    smallint NOT NULL CHECK (end_period BETWEEN 1 AND 12),
  CHECK (start_period <= end_period),
  -- 同一学期同一教室同一时间只能有一个班
  EXCLUDE USING gist (
    semester_id WITH =, room WITH =, weekday WITH =,
    int4range(start_period, end_period, '[]') WITH &&
  )
);
CREATE INDEX class_slots_class ON class_slots (class_id);

CREATE TABLE class_members (
  class_id   bigint NOT NULL REFERENCES classes(id),
  user_id    bigint NOT NULL REFERENCES users(id),
  joined_at  timestamptz NOT NULL DEFAULT now(),
  left_at    timestamptz,                      -- 「不是我的班」退出；之后导入课表不会自动加回
  PRIMARY KEY (class_id, user_id)
);
CREATE INDEX class_members_user ON class_members (user_id) WHERE left_at IS NULL;

-- 班级自动合并记录，管理员可撤销（撤销接口归 WS2）。snapshot 存搬走的行 id 和合并前状态：
-- slot_ids / assignment_ids / points_ledger_ids / post_ban_ids、child_class_ids（原指向 from 的班）、
-- from_members / into_members（[{user_id, joined_at, left_at}]）、into_name / into_name_truncated / into_teacher。
CREATE TABLE class_merges (
  id             bigserial PRIMARY KEY,
  from_class_id  bigint NOT NULL REFERENCES classes(id),
  into_class_id  bigint NOT NULL REFERENCES classes(id),
  triggered_by   bigint REFERENCES users(id),   -- 导入课表触发合并的用户
  snapshot       jsonb NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  undone_at      timestamptz,
  undone_by      bigint REFERENCES users(id)
);
CREATE INDEX class_merges_into ON class_merges (into_class_id) WHERE undone_at IS NULL;
CREATE INDEX class_merges_from ON class_merges (from_class_id);

-- ---------- 作业（主体）与来源 ----------
CREATE TABLE assignments (
  id            bigserial PRIMARY KEY,
  class_id      bigint NOT NULL REFERENCES classes(id),
  title         text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 80),
  ddl           timestamptz NOT NULL,
  ddl_uncertain boolean NOT NULL DEFAULT false, -- AI 标记「待确认」
  ddl_conflict  boolean NOT NULL DEFAULT false, -- 来源之间 DDL 不一致，标红
  merged_into   bigint REFERENCES assignments(id),
  created_by    bigint NOT NULL REFERENCES users(id), -- 首发者
  created_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz,                   -- 所有来源都被删除时随之删除
  updated_at    timestamptz NOT NULL DEFAULT now(), -- /sync 增量：删除、合并、改 DDL、加来源都会刷新
  reminder_text jsonb                          -- 预生成提醒文案 {"2d","1d","12h"}，WS3 写入，DDL 变更时清空
);
CREATE INDEX assignments_class_updated ON assignments (class_id, updated_at);
CREATE INDEX assignments_class_ddl ON assignments (class_id, ddl) WHERE deleted_at IS NULL AND merged_into IS NULL;
CREATE INDEX assignments_title_trgm ON assignments USING gin (title gin_trgm_ops);

CREATE TABLE assignment_sources (
  id                      bigserial PRIMARY KEY,
  assignment_id           bigint NOT NULL REFERENCES assignments(id),
  original_assignment_id  bigint NOT NULL REFERENCES assignments(id), -- 撤销合并时据此搬回
  author_id               bigint NOT NULL REFERENCES users(id),
  kind                    text NOT NULL CHECK (kind IN ('first','supplement')),
  body                    text NOT NULL DEFAULT '' CHECK (char_length(body) <= 5000),
  stated_ddl              timestamptz,         -- 该来源声称的 DDL，用于冲突检测
  created_at              timestamptz NOT NULL DEFAULT now(),
  edited_at               timestamptz,
  deleted_at              timestamptz,
  deleted_reason          text CHECK (deleted_reason IN ('author','vote','admin'))
);
CREATE INDEX sources_assignment ON assignment_sources (assignment_id) WHERE deleted_at IS NULL;
CREATE INDEX sources_author_day ON assignment_sources (author_id, created_at);
CREATE INDEX sources_original ON assignment_sources (original_assignment_id);

CREATE TABLE attachments (
  id            bigserial PRIMARY KEY,
  source_id     bigint REFERENCES assignment_sources(id), -- 上传后、发布前为 NULL
  uploader_id   bigint NOT NULL REFERENCES users(id),
  semester_id   int NOT NULL REFERENCES semesters(id),   -- 用于按学期清理
  kind          text NOT NULL CHECK (kind IN ('image','file')),
  path          text NOT NULL,
  thumb_path    text,
  bytes         int NOT NULL CHECK (bytes <= 10485760),
  created_at    timestamptz NOT NULL DEFAULT now(),
  purged_at     timestamptz,
  mime          text NOT NULL DEFAULT 'application/octet-stream'
);
CREATE INDEX attachments_source ON attachments (source_id);
CREATE INDEX attachments_unbound ON attachments (created_at) WHERE source_id IS NULL;  -- 上传后 24h 未发布的清理

-- 完成状态：仅本人可见，只用于停止提醒
CREATE TABLE completions (
  user_id        bigint NOT NULL REFERENCES users(id),
  assignment_id  bigint NOT NULL REFERENCES assignments(id),
  done_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, assignment_id)
);
CREATE INDEX completions_assignment ON completions (assignment_id);

-- ---------- 合并 ----------
CREATE TABLE merge_proposals (
  id            bigserial PRIMARY KEY,
  from_id       bigint NOT NULL REFERENCES assignments(id), -- 被合并
  to_id         bigint NOT NULL REFERENCES assignments(id), -- 保留
  proposer_id   bigint NOT NULL REFERENCES users(id),
  status        text NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','applied','rejected','reverted','expired')),
  applied_by    text CHECK (applied_by IN ('poster','votes')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  applied_at    timestamptz,
  reverted_by   bigint REFERENCES users(id),   -- 管理员
  snapshot      jsonb,                         -- 执行时搬动的行与合并前状态，撤销据此还原（字段见 0003 迁移）
  CHECK (from_id <> to_id)
);
CREATE UNIQUE INDEX one_pending_merge ON merge_proposals (from_id, to_id) WHERE status = 'pending';
CREATE INDEX merge_proposals_pending ON merge_proposals (created_at) WHERE status = 'pending';
CREATE INDEX merge_proposals_to ON merge_proposals (to_id);

CREATE TABLE merge_votes (
  proposal_id  bigint NOT NULL REFERENCES merge_proposals(id),
  user_id      bigint NOT NULL REFERENCES users(id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (proposal_id, user_id)
);

-- ---------- 删除投票与禁发 ----------
-- 门槛：班级成员数 10%，最少 3，最多 6
CREATE TABLE delete_votes (
  source_id   bigint NOT NULL REFERENCES assignment_sources(id),
  user_id     bigint NOT NULL REFERENCES users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, user_id)
);

-- 30 天内被投票删除 3 次 -> 该班禁发 7 天
CREATE TABLE post_bans (
  id          bigserial PRIMARY KEY,
  user_id     bigint NOT NULL REFERENCES users(id),
  class_id    bigint NOT NULL REFERENCES classes(id),
  starts_at   timestamptz NOT NULL DEFAULT now(),
  ends_at     timestamptz NOT NULL,
  lifted_at   timestamptz                      -- 管理员恢复内容后可解除
);
CREATE INDEX post_bans_user_class ON post_bans (user_id, class_id);

-- 邀请树违规提醒：通知直接邀请人
CREATE TABLE notices (
  id          bigserial PRIMARY KEY,
  user_id     bigint NOT NULL REFERENCES users(id),
  kind        text NOT NULL,                   -- invitee_violation / merge_request / ban / points
  payload     jsonb NOT NULL DEFAULT '{}',
  created_at  timestamptz NOT NULL DEFAULT now(),
  read_at     timestamptz
);
CREATE INDEX notices_user_time ON notices (user_id, created_at DESC);

-- ---------- 谢谢与积分 ----------
CREATE TABLE thanks (
  source_id   bigint NOT NULL REFERENCES assignment_sources(id),
  user_id     bigint NOT NULL REFERENCES users(id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, user_id)
);

-- 积分流水。周榜/学期榜/课代表都由它汇总。
CREATE TABLE points_ledger (
  id            bigserial PRIMARY KEY,
  user_id       bigint NOT NULL REFERENCES users(id),
  class_id      bigint NOT NULL REFERENCES classes(id),
  semester_id   int NOT NULL REFERENCES semesters(id),
  source_id     bigint REFERENCES assignment_sources(id),
  reason        text NOT NULL CHECK (reason IN (
                  'first_post','supplement','thanks','deleted_revoke','deleted_penalty',
                  'restore_refund','merge_adjust')),
  delta         int NOT NULL,
  status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','settled','void')),
  settle_at     timestamptz NOT NULL,          -- 首发/补充 = 发布后 24h
  created_at    timestamptz NOT NULL DEFAULT now(),
  local_day     date NOT NULL                  -- Asia/Shanghai 日期，用于每日 30 分上限
);
CREATE INDEX points_user_day ON points_ledger (user_id, local_day) WHERE status = 'settled';
CREATE INDEX points_class_time ON points_ledger (class_id, created_at) WHERE status = 'settled';
CREATE INDEX points_pending ON points_ledger (settle_at) WHERE status = 'pending';
CREATE INDEX points_source ON points_ledger (source_id);

-- 规则变更公告（提前 7 天）
CREATE TABLE announcements (
  id            bigserial PRIMARY KEY,
  title         text NOT NULL,
  body          text NOT NULL,
  published_at  timestamptz NOT NULL DEFAULT now(),
  effective_at  timestamptz NOT NULL,
  CHECK (effective_at >= published_at + interval '7 days')
);

-- ---------- AI ----------
-- 电脑离线时不排队：服务器在 worker 心跳超过 60 秒时直接拒绝新任务。
CREATE TABLE ai_workers (
  id          text PRIMARY KEY,
  last_seen   timestamptz NOT NULL,
  model       text NOT NULL
);

CREATE TABLE ai_jobs (
  id           bigserial PRIMARY KEY,
  kind         text NOT NULL CHECK (kind IN (
                 'parse_assignment','parse_timetable','dedupe_review',
                 'ddl_conflict','spam_check','reminder_text')),
  status       text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','running','done','failed','expired')),
  requested_by bigint REFERENCES users(id),
  input        jsonb NOT NULL,
  output       jsonb,
  error        text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  claimed_at   timestamptz,
  finished_at  timestamptz,
  expires_at   timestamptz NOT NULL DEFAULT now() + interval '2 minutes',
  class_id     bigint REFERENCES classes(id),     -- 与某个班有关的任务
  assignment_id bigint REFERENCES assignments(id), -- ddl_conflict / reminder_text（服务器自动发起）
  worker_id    text                               -- 领取该任务的 worker
);
CREATE INDEX ai_jobs_queued ON ai_jobs (created_at) WHERE status = 'queued';
CREATE INDEX ai_jobs_requester ON ai_jobs (requested_by, created_at) WHERE requested_by IS NOT NULL;
CREATE INDEX ai_jobs_open ON ai_jobs (expires_at) WHERE status IN ('queued', 'running');
-- 任务输入含作业正文，结束 24 小时后由服务器定时删除（不长期保存）。

-- ---------- 种子数据 ----------
INSERT INTO periods (no, starts_at, ends_at) VALUES
 (1,'08:30','09:15'),(2,'09:25','10:10'),(3,'10:20','11:05'),(4,'11:15','12:00'),
 (5,'13:00','13:45'),(6,'13:55','14:40'),(7,'14:50','15:35'),(8,'15:45','16:30'),
 (9,'16:40','17:25'),(10,'18:30','19:15'),(11,'19:25','20:10'),(12,'20:20','21:05');
