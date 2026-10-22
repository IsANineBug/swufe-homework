DROP TABLE IF EXISTS admin_actions;
DROP TABLE IF EXISTS user_sanctions;
DROP TABLE IF EXISTS reports;
DROP TABLE IF EXISTS class_messages;
ALTER TABLE points_ledger DROP COLUMN IF EXISTS note;
DELETE FROM points_ledger WHERE reason = 'admin_adjust';
ALTER TABLE points_ledger DROP CONSTRAINT IF EXISTS points_ledger_reason_check;
ALTER TABLE points_ledger ADD CONSTRAINT points_ledger_reason_check CHECK (reason IN (
  'first_post','supplement','thanks','deleted_revoke','deleted_penalty',
  'restore_refund','merge_adjust'));
ALTER TABLE classes DROP COLUMN IF EXISTS hidden_at;
