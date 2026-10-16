package com.swufe.homework.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swufe.homework.data.local.AssignmentDao
import com.swufe.homework.data.repo.ReminderSync
import com.swufe.homework.util.Ddl
import com.swufe.homework.util.ReminderPlan
import com.swufe.homework.util.ReminderSlot
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun assignmentDao(): AssignmentDao
    fun scheduler(): ReminderScheduler
    fun reminderSync(): ReminderSync
    fun json(): Json
}

internal fun Context.reminderDeps(): ReminderEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)

/** 在 BroadcastReceiver 里跑一小段挂起代码（goAsync，10 秒内结束）。 */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try { block() } finally { pending.finish() }
    }
}

/** 闹钟到点：读本地缓存，未完成才弹通知。文案优先 reminder_text，没有就用内置模板。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getIntExtra(Notifications.EXTRA_ASSIGNMENT_ID, -1)
        val slot = ReminderSlot.of(intent.getStringExtra(EXTRA_SLOT)) ?: return
        if (id < 0) return
        runAsync {
            val deps = context.reminderDeps()
            val e = deps.assignmentDao().byId(id) ?: return@runAsync
            if (e.done) return@runAsync
            val ddl = Ddl.parse(e.ddl) ?: return@runAsync
            if (Ddl.isPast(ddl)) return@runAsync
            val ai = e.reminderText?.let { runCatching { deps.json().decodeFromString(JsonObject.serializer(), it) }.getOrNull() }
            val (title, body) = ReminderPlan.aiText(ai, slot)
                ?.let { (t, b) -> t to b.ifEmpty { "${e.title}，${Ddl.display(ddl)} 截止" } }
                ?: ReminderPlan.defaultText(slot, e.title, e.className, ddl, Ddl.now())
            Notifications.showDdl(context, id, title, body)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.swufe.homework.REMINDER_FIRE"
        const val EXTRA_SLOT = "slot"
    }
}

/** 通知栏「标记完成」：本地立即生效并取消剩余提醒，接口调用交给 WorkManager（断网会自动重试）。 */
class MarkDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_DONE) return
        val id = intent.getIntExtra(Notifications.EXTRA_ASSIGNMENT_ID, -1)
        if (id < 0) return
        runAsync {
            val deps = context.reminderDeps()
            deps.assignmentDao().setDone(id, true)
            deps.scheduler().cancelWithNotification(id)
            DoneWorker.enqueue(context, id, done = true)
        }
    }

    companion object {
        const val ACTION_MARK_DONE = "com.swufe.homework.MARK_DONE"
    }
}

/** 开机、升级、改时间/时区、精确闹钟权限变化后重建闹钟，并补一次同步。 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> runAsync {
                context.reminderDeps().scheduler().rescheduleAll()
                SyncWorker.runOnce(context)
            }
        }
    }
}
