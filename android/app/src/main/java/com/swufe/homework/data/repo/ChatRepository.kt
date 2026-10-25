package com.swufe.homework.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.HomeworkApi
import com.swufe.homework.data.api.Message
import com.swufe.homework.data.api.MessageCreate
import com.swufe.homework.data.api.MessagesPage
import com.swufe.homework.data.api.ReportCreate
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.api.toApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** 一页消息条数（api.yaml limit 上限 50）。 */
const val CHAT_PAGE = 50

/** 发送结果：成功 / 限频（带 Retry-After 秒数）/ 被禁言 / 其他失败。 */
sealed interface SendResult {
    data class Sent(val message: Message) : SendResult
    data class RateLimited(val seconds: Int, val message: String) : SendResult
    data class Muted(val message: String) : SendResult
    data class Failed(val error: ApiException) : SendResult
}

/** Retry-After 只按「秒数」解析（服务器给整数秒）；缺失或无法解析时按 10 秒（每人每 10 秒 1 条）。 */
fun parseRetryAfter(header: String?, default: Int = 10): Int =
    header?.trim()?.toIntOrNull()?.coerceIn(1, 3600) ?: default

/**
 * 班级频道与举报（api.yaml tag chat）。只做请求，不轮询：
 * 拉新消息由频道页在「可见」时驱动（见 ui/chat/ChatPolling），离开或切后台即停，没有长连接、前台服务或后台任务。
 */
@Singleton
class ChatRepository @Inject constructor(private val api: HomeworkApi) {
    suspend fun latest(classId: Int): Result<MessagesPage> = apiCall { api.messages(classId, limit = CHAT_PAGE) }

    suspend fun newer(classId: Int, afterId: Long): Result<MessagesPage> =
        apiCall { api.messages(classId, afterId = afterId, limit = CHAT_PAGE) }

    suspend fun older(classId: Int, beforeId: Long): Result<MessagesPage> =
        apiCall { api.messages(classId, beforeId = beforeId, limit = CHAT_PAGE) }

    suspend fun send(classId: Int, body: String): SendResult = try {
        val r = api.sendMessage(classId, MessageCreate(body))
        val msg = r.body()
        if (r.isSuccessful && msg != null) {
            SendResult.Sent(msg)
        } else {
            val e = HttpException(r).toApiException()
            when {
                r.code() == 429 || e.code == "chat_rate_limited" ->
                    SendResult.RateLimited(parseRetryAfter(r.headers()["Retry-After"]), e.message)
                e.code == "muted" -> SendResult.Muted(e.message)
                else -> SendResult.Failed(e)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        SendResult.Failed(e.toApiException())
    }

    /** 作者删除自己的消息（幂等；已被删 404 也当成功）。 */
    suspend fun delete(messageId: Long): Result<Unit> = apiCall {
        val r = api.deleteMessage(messageId)
        if (!r.isSuccessful && r.code() != 404) throw HttpException(r)
    }

    /** POST /reports。409 already_reported 由界面提示「你已经举报过，管理员处理中」。 */
    suspend fun report(kind: String, targetId: Long, reason: String): Result<Unit> = apiCall {
        val r = api.report(ReportCreate(kind, targetId, reason))
        if (!r.isSuccessful) throw HttpException(r)
    }
}

private val Context.chatPrefs by preferencesDataStore(name = "chat_prefs")

/**
 * 频道小红点：/me/classes 的 last_message_id 与本地「已读到」的消息 id 比较。
 * 已读 id 存 DataStore（每班一个键）；最新 id 来自最近一次 /me/classes（内存，不进 Room，不改表结构）。
 */
@Singleton
class ChatReadStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val latest = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val latestIds: StateFlow<Map<Int, Long>> = latest

    private fun key(classId: Int) = longPreferencesKey("read_$classId")

    /** 每次拿到 /me/classes 后调用。 */
    fun updateLatest(classes: List<ClassInfo>) {
        latest.value = classes.mapNotNull { c -> c.lastMessageId?.let { c.id to it } }.toMap()
    }

    /** 频道里看到了更新的消息（含自己刚发的）：最新 id 也跟着前进，避免小红点等到下次刷新才消失。 */
    suspend fun markRead(classId: Int, messageId: Long) {
        ctx.chatPrefs.edit { p -> if ((p[key(classId)] ?: 0L) < messageId) p[key(classId)] = messageId }
        latest.value = latest.value.toMutableMap().apply { if ((this[classId] ?: 0L) < messageId) this[classId] = messageId }
    }

    private val readIds: Flow<Map<Int, Long>> = ctx.chatPrefs.data.map { p ->
        p.asMap().mapNotNull { (k, v) -> k.name.removePrefix("read_").toIntOrNull()?.let { id -> (v as? Long)?.let { id to it } } }.toMap()
    }

    /** 有未读消息的班级 id。 */
    val unreadClasses: Flow<Set<Int>> = combine(latest, readIds) { l, r -> unreadOf(l, r) }
}

/** 纯函数，便于单测：最新 id 大于已读 id（没读过按 0）即未读。 */
fun unreadOf(latest: Map<Int, Long>, read: Map<Int, Long>): Set<Int> =
    latest.filter { (cid, last) -> last > (read[cid] ?: 0L) }.keys
