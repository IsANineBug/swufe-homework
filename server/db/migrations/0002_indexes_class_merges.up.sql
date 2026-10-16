-- 0002（issue #4 裁决 10、15）：热点查询索引；班级自动合并留撤销数据。

CREATE INDEX invite_codes_owner ON invite_codes (owner_id);
CREATE INDEX auth_tokens_user ON auth_tokens (user_id);
CREATE INDEX notices_user_time ON notices (user_id, created_at DESC);

-- 班级自动合并记录：管理员可撤销（撤销接口归 WS2 管理员接口）。
-- snapshot 记录合并时从 from 班搬到 into 班的行和合并前状态，撤销时按它搬回：
--   slot_ids / assignment_ids / points_ledger_ids / post_ban_ids：搬走的行 id
--   child_class_ids：原先 merged_into = from 的班（随之改指向 into）
--   from_members：from 班合并前的成员 [{user_id, joined_at, left_at}]
--   into_members：into 班合并前的成员 [{user_id, joined_at, left_at}]
--   into_name / into_name_truncated / into_teacher：into 班合并前的课名与教师
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
