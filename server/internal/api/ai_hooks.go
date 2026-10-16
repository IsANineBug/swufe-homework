package api

import (
	"context"
	"log/slog"
	"time"
)

// aiAfterWrite 在作业写入的事务提交之后触发服务器侧 AI 任务（WS3）：
//   - reminders：作业创建、DDL 或标题变更 → 预生成 2d/1d/12h 提醒文案，写回 assignments.reminder_text
//   - conflict：新增或编辑来源 → 让 AI 看正文里的截止时间是否矛盾，矛盾只标红（不改 DDL）
//
// worker 离线时什么也不做（不排队）。失败只记日志，不影响这次写入的响应。
func (s *Server) aiAfterWrite(ctx context.Context, assignmentID int64, reminders, conflict bool) {
	if s.AI == nil || assignmentID == 0 || (!reminders && !conflict) {
		return
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	if reminders {
		if err := s.AI.RequestReminderTexts(ctx, assignmentID); err != nil {
			slog.WarnContext(ctx, "ai reminder_text", "assignment", assignmentID, "err", err)
		}
	}
	if conflict {
		if err := s.AI.RequestDDLConflict(ctx, assignmentID); err != nil {
			slog.WarnContext(ctx, "ai ddl_conflict", "assignment", assignmentID, "err", err)
		}
	}
}
