-- 0005（WS8，v2）：班级隐藏、管理员调整贡献值、班级频道、举报、管理员处置与操作日志。
-- 目标状态见 db/schema.sql 中标 v2 的部分。

-- 管理员隐藏的垃圾班级（可恢复）
ALTER TABLE classes ADD COLUMN hidden_at timestamptz;

-- 贡献值流水：admin_adjust 与原因
ALTER TABLE points_ledger DROP CONSTRAINT points_ledger_reason_check;
ALTER TABLE points_ledger ADD CONSTRAINT points_ledger_reason_check CHECK (reason IN (
  'first_post','supplement','thanks','deleted_revoke','deleted_penalty',
  'restore_refund','merge_adjust','admin_adjust'));
ALTER TABLE points_ledger ADD COLUMN note text;

-- 班级交流频道
CREATE TABLE class_messages (
  id          bigserial PRIMARY KEY,
  class_id    bigint NOT NULL REFERENCES classes(id),
  user_id     bigint NOT NULL REFERENCES users(id),
  body        text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 300),
  created_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  deleted_by  text CHECK (deleted_by IN ('author','admin'))
);
CREATE INDEX class_messages_class ON class_messages (class_id, id);
CREATE INDEX class_messages_user_time ON class_messages (user_id, created_at);

-- 举报
CREATE TABLE reports (
  id           bigserial PRIMARY KEY,
  reporter_id  bigint NOT NULL REFERENCES users(id),
  target_kind  text NOT NULL CHECK (target_kind IN ('source','message','class','user')),
  target_id    bigint NOT NULL,
  reason       text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 200),
  created_at   timestamptz NOT NULL DEFAULT now(),
  handled_at   timestamptz,
  handled_by   bigint REFERENCES users(id),
  resolution   text CHECK (resolution IN ('removed','dismissed'))
);
CREATE UNIQUE INDEX reports_open_unique ON reports (reporter_id, target_kind, target_id) WHERE handled_at IS NULL;
CREATE INDEX reports_open ON reports (created_at) WHERE handled_at IS NULL;

-- 管理员处置
CREATE TABLE user_sanctions (
  id          bigserial PRIMARY KEY,
  user_id     bigint NOT NULL REFERENCES users(id),
  kind        text NOT NULL CHECK (kind IN ('mute','post_ban','disable')),
  reason      text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 200),
  starts_at   timestamptz NOT NULL DEFAULT now(),
  ends_at     timestamptz,
  created_by  bigint NOT NULL REFERENCES users(id),
  lifted_at   timestamptz,
  lifted_by   bigint REFERENCES users(id)
);
CREATE INDEX user_sanctions_active ON user_sanctions (user_id, kind) WHERE lifted_at IS NULL;

-- 管理员操作日志
CREATE TABLE admin_actions (
  id           bigserial PRIMARY KEY,
  admin_id     bigint NOT NULL REFERENCES users(id),
  action       text NOT NULL,
  target_kind  text NOT NULL,
  target_id    bigint NOT NULL,
  reason       text,
  payload      jsonb NOT NULL DEFAULT '{}',
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_actions_time ON admin_actions (created_at DESC);
