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

    fun notificationId(assignmentId: Int) = assignmentId

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(CHANNEL_DDL, "作业截止提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时提醒"
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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
}
