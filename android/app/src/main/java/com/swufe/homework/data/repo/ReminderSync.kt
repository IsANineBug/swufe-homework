package com.swufe.homework.data.repo

import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.HomeworkApi
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.api.orThrow
import com.swufe.homework.data.local.AssignmentDao
import com.swufe.homework.data.local.AssignmentEntity
import com.swufe.homework.data.local.TimetableDao
import com.swufe.homework.data.local.TokenStore
import com.swufe.homework.reminder.ReminderPrefs
import com.swufe.homework.reminder.ReminderScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地作业缓存 + 闹钟的唯一入口：/sync 增量同步、首页/详情拿到的新数据、完成状态变化都经过这里，
 * 保证缓存和闹钟一致。只缓存提醒需要的字段（标题、班级、DDL、完成、提醒文案），不存正文和图片。
 */
@Singleton
class ReminderSync @Inject constructor(
    private val api: HomeworkApi,
    private val core: SwufeApi,
    private val dao: AssignmentDao,
    private val timetable: TimetableDao,
    private val scheduler: ReminderScheduler,
    private val prefs: ReminderPrefs,
    private val tokens: TokenStore,
    private val json: Json,
) {
    private val mutex = Mutex()

    private fun loggedIn() = !tokens.refreshToken().isNullOrEmpty()

    /** GET /sync?since=游标。首次（无游标）为全量，先清掉旧缓存。 */
    suspend fun sync(): Result<Unit> {
        if (!loggedIn()) return Result.success(Unit)
        return mutex.withLock {
            apiCall {
                val since = prefs.cursor()
                val r = api.sync(since)
                if (since == null) {
                    scheduler.cancelAll()
                    dao.clear()
                }
                applyLocked(r.changed)
                if (r.removedIds.isNotEmpty()) {
                    r.removedIds.forEach { scheduler.cancelWithNotification(it) }
                    dao.delete(r.removedIds)
                }
                if (since != null && hasStaleClasses()) {
                    // 缓存里有不在「我的班级」里的作业（退了班、班级被合并、另一台设备上变动）：
                    // 不在本地猜，直接全量同步一次，以服务端结果为准。
                    val full = api.sync(null)
                    scheduler.cancelAll()
                    dao.clear()
                    applyLocked(full.changed)
                    prefs.setCursor(full.now)
                } else {
                    prefs.setCursor(r.now)
                }
                Unit
            }
        }
    }

    /** 首页、班级页、详情拿到的最新数据顺手写进缓存并重排提醒。 */
    suspend fun apply(list: List<AssignmentSummary>) {
        if (list.isEmpty()) return
        mutex.withLock { applyLocked(list) }
    }

    /** 作业已不存在（404 / 被合并走）。 */
    suspend fun forget(id: Int) = mutex.withLock {
        scheduler.cancelWithNotification(id)
        dao.delete(listOf(id))
    }

    /** 本地先生效（停止或恢复提醒），再调接口。接口失败时把本地状态改回去。 */
    suspend fun setDone(id: Int, done: Boolean): Result<Unit> {
        mutex.withLock { localDone(id, done) }
        return pushDone(id, done).onFailure { mutex.withLock { localDone(id, !done) } }
    }

    /** 只调接口（通知栏「标记完成」由 DoneWorker 调，本地已先改好）。 */
    suspend fun pushDone(id: Int, done: Boolean): Result<Unit> = apiCall {
        (if (done) api.markDone(id) else api.unmarkDone(id)).orThrow()
    }

    private suspend fun localDone(id: Int, done: Boolean) {
        dao.setDone(id, done)
        if (done) scheduler.cancelWithNotification(id) else dao.byId(id)?.let { scheduler.schedule(it) }
    }

    private suspend fun applyLocked(list: List<AssignmentSummary>) {
        val entities = list.map { it.toEntity() }
        dao.upsert(entities)
        entities.forEach { scheduler.schedule(it) }
    }

    /**
     * 缓存里是否有不属于「我的班级」的作业。先 GET /me/classes 刷新本地课表（本地 my_class 只在课表页刷新，
     * 可能过期）；刷新失败就当没有，跳过本次清理，绝不按过期的本地课表删缓存。
     */
    private suspend fun hasStaleClasses(): Boolean {
        val cached = dao.all()
        if (cached.isEmpty()) return false
        val classes = runCatching { core.myClasses() }.getOrNull() ?: return false
        runCatching { timetable.replaceAll(classes) }
        val mine = classes.map { it.id }.toSet()
        return cached.any { it.classId !in mine }
    }

    /** 退出登录 / 会话过期：取消全部提醒，清游标（表由调用方 clearAllTables）。 */
    suspend fun reset() = mutex.withLock {
        scheduler.cancelAll()
        dao.clear()
        prefs.clearCursor()
    }

    private fun AssignmentSummary.toEntity() = AssignmentEntity(
        id = id, classId = classId, className = className, title = title, ddl = ddl,
        ddlUncertain = ddlUncertain, ddlConflict = ddlConflict, done = done, sourceCount = sourceCount,
        reminderText = reminderText?.let { json.encodeToString(JsonObject.serializer(), it) },
        updatedAt = updatedAt,
    )
}
