DROP INDEX IF EXISTS ai_jobs_open;
DROP INDEX IF EXISTS ai_jobs_requester;
ALTER TABLE ai_jobs DROP COLUMN IF EXISTS worker_id;
ALTER TABLE ai_jobs DROP COLUMN IF EXISTS assignment_id;
ALTER TABLE ai_jobs DROP COLUMN IF EXISTS class_id;
