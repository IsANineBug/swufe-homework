package com.swufe.homework.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.swufe.homework.data.local.AssignmentDao
import com.swufe.homework.data.local.AssignmentEntity
import com.swufe.homework.util.Ddl
import com.swufe.homework.util.ReminderPlan
import com.swufe.homework.util.ReminderSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地提醒：AlarmManager.setExactAndAllowWhileIdle，不依赖推送（requirements §十）。
 * 每条作业最多 3 个闹钟，requestCode = id × 4 + slot.index，重排前先全部取消。
 * 没有精确闹钟权限（Android 12+ 用户关掉）时退回 setAndAllowWhileIdle，可能晚几分钟。
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: AssignmentDao,
) {
    private val alarms get() = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun schedule(e: AssignmentEntity, now: Instant = Instant.now()) {
        cancel(e.id)
        if (e.done) return
        val ddl = Ddl.parse(e.ddl) ?: return
        for (r in ReminderPlan.plan(ddl, now)) {
            val pi = alarmIntent(e.id, r.slot, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            val at = r.at.toEpochMilli()
            if (canExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(id: Int) {
        ReminderSlot.entries.forEach { s ->
            alarmIntent(id, s, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
        }
    }

    /** 标记完成：取消剩余提醒并收起已弹出的通知。 */
    fun cancelWithNotification(id: Int) {
        cancel(id)
        NotificationManagerCompat.from(ctx).cancel(Notifications.notificationId(id))
    }

    suspend fun rescheduleAll() {
        val now = Instant.now()
        dao.all().forEach { schedule(it, now) }
    }

    /** 退出登录：取消所有闹钟和通知（之后由调用方清库）。 */
    suspend fun cancelAll() {
        dao.all().forEach { cancel(it.id) }
        NotificationManagerCompat.from(ctx).cancelAll()
    }

    private fun alarmIntent(id: Int, slot: ReminderSlot, flag: Int): PendingIntent? {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(Notifications.EXTRA_ASSIGNMENT_ID, id)
            .putExtra(ReminderReceiver.EXTRA_SLOT, slot.key)
        return PendingIntent.getBroadcast(ctx, id * 4 + slot.index, i, flag or PendingIntent.FLAG_IMMUTABLE)
    }
}
