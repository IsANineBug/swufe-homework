-- 0003（WS2）：作业增量同步与提醒文案、作业合并撤销数据、上传 MIME、热点查询索引。

-- /sync 按 updated_at 增量下发；删除、合并、改 DDL、加来源都会刷新它
ALTER TABLE assignments ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
-- 预生成提醒文案 {"2d":…,"1d":…,"12h":…}，由 WS3 写入；DDL 变更时清空
ALTER TABLE assignments ADD COLUMN reminder_text jsonb;
CREATE INDEX assignments_class_updated ON assignments (class_id, updated_at);

-- 作业合并执行时记下搬动的行与合并前状态，管理员撤销时据此还原：
--   source_ids：从 from 搬到 to 的来源；first_source_id：from 的首发来源（合并后改为 supplement）
--   voided_ledger：[{id,status}] 作废前的首发流水；added_ledger_ids：合并时新记的补充流水
--   added_completion_users：合并时给 to 补上完成状态的用户；superseded_ids：因此失效的其他提议
ALTER TABLE merge_proposals ADD COLUMN snapshot jsonb;
CREATE INDEX merge_proposals_pending ON merge_proposals (created_at) WHERE status = 'pending';
CREATE INDEX merge_proposals_to ON merge_proposals (to_id);

ALTER TABLE attachments ADD COLUMN mime text NOT NULL DEFAULT 'application/octet-stream';
CREATE INDEX attachments_source ON attachments (source_id);
CREATE INDEX attachments_unbound ON attachments (created_at) WHERE source_id IS NULL;

CREATE INDEX points_pending ON points_ledger (settle_at) WHERE status = 'pending';
CREATE INDEX points_source ON points_ledger (source_id);
CREATE INDEX post_bans_user_class ON post_bans (user_id, class_id);
CREATE INDEX completions_assignment ON completions (assignment_id);
CREATE INDEX sources_original ON assignment_sources (original_assignment_id);
