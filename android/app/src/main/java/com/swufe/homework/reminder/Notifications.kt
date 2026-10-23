package com.swufe.homework.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swufe.homework.MainActivity
import com.swufe.homework.R

object Notifications {
    const val CHANNEL_DDL = "ddl"
    const val EXTRA_ASSIGNMENT_ID = "assignment_id"
    const val CHANNEL_AI = "ai"
    /** 点击「AI 识别」通知：带任务 kind，MainActivity 交给 DeepLinks 进对应确认页。 */
    const val EXTRA_AI_KIND = "ai_job_kind"

    fun notificationId(assignmentId: Int) = assignmentId

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL_DDL, "作业截止提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时提醒"
        }
        val ai = NotificationChannel(CHANNEL_AI, "AI 识别", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "课表、作业截图在后台识别好了"
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(ch)
        nm.createNotificationChannel(ai)
    }

    fun canPost(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** 点击通知进入作业详情（MainActivity 读 extra 后导航）。 */
    fun openDetailIntent(ctx: Context, assignmentId: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setAction("com.swufe.homework.OPEN_ASSIGNMENT_$assignmentId")
            .putExtra(EXTRA_ASSIGNMENT_ID, assignmentId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, assignmentId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun markDoneIntent(ctx: Context, assignmentId: Int): PendingIntent {
        val i = Intent(ctx, MarkDoneReceiver::class.java)
            .setAction(MarkDoneReceiver.ACTION_MARK_DONE)
            .putExtra(EXTRA_ASSIGNMENT_ID, assignmentId)
        return PendingIntent.getBroadcast(ctx, assignmentId, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showDdl(ctx: Context, assignmentId: Int, title: String, body: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_DDL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openDetailIntent(ctx, assignmentId))
            .addAction(0, "标记完成", markDoneIntent(ctx, assignmentId))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(assignmentId), n)
        } catch (_: SecurityException) {
            // 权限在检查后被撤销
        }
    }

    /** 后台识图结束（App 不在前台时）：每种任务一条通知，点开进确认页。id 用负数，不和作业提醒冲突。 */
    fun showAiResult(ctx: Context, kind: String, title: String, body: String) {
        if (!canPost(ctx)) return
        val id = aiNotificationId(kind)
        val i = Intent(ctx, MainActivity::class.java)
            .setAction("com.swufe.homework.OPEN_AI_$kind")
            .putExtra(EXTRA_AI_KIND, kind)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_AI)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            // 权限在检查后被撤销
        }
    }

    fun cancelAi(ctx: Context, kind: String) = NotificationManagerCompat.from(ctx).cancel(aiNotificationId(kind))

    private fun aiNotificationId(kind: String) = if (kind == "parse_timetable") -1 else -2
}
