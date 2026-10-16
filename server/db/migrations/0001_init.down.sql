-- 回滚 0001：按依赖逆序删除全部表（仅开发环境使用）
DROP TABLE IF EXISTS ai_jobs, ai_workers, announcements, points_ledger, thanks, notices,
  post_bans, delete_votes, merge_votes, merge_proposals, completions, attachments,
  assignment_sources, assignments, class_members, class_slots, classes, auth_tokens,
  invite_codes, users, periods, semesters CASCADE;
