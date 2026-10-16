-- 0004（WS3）：AI 任务关联班级 / 作业与领取的 worker；限流与过期扫描索引。
-- 提醒文案写回 WS2 在 0003 加的 assignments.reminder_text（DDL 变更时由 WS2 清空）。

-- 发起任务时的班级（parse_assignment / dedupe_review / spam_check）与作业（ddl_conflict / reminder_text，服务器自动发起）
ALTER TABLE ai_jobs ADD COLUMN class_id bigint REFERENCES classes(id);
ALTER TABLE ai_jobs ADD COLUMN assignment_id bigint REFERENCES assignments(id);
-- 领取该任务的 worker（排查用）
ALTER TABLE ai_jobs ADD COLUMN worker_id text;
-- 每人限流（进行中数量、每小时次数）
CREATE INDEX ai_jobs_requester ON ai_jobs (requested_by, created_at) WHERE requested_by IS NOT NULL;
-- 过期扫描：只看未结束的任务
CREATE INDEX ai_jobs_open ON ai_jobs (expires_at) WHERE status IN ('queued', 'running');
