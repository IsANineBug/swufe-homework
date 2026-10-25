package com.swufe.homework.ui.chat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.Message
import com.swufe.homework.data.api.MessagesPage
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.data.repo.CHAT_PAGE
import com.swufe.homework.data.repo.ChatReadStore
import com.swufe.homework.data.repo.ChatRepository
import com.swufe.homework.data.repo.SendResult
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** 发送中（乐观显示）的消息：本地负数 id，成功后换成服务器那条，失败回滚到输入框。 */
data class PendingMessage(val localId: Long, val body: String)

private const val TAG = "ChatPoll"

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: ChatRepository,
    private val readStore: ChatReadStore,
    private val account: AccountRepository,
) : ViewModel() {
    var classId = -1; private set

    var messages by mutableStateOf<List<Message>>(emptyList()); private set
    var pending by mutableStateOf<List<PendingMessage>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var loadingOlder by mutableStateOf(false); private set
    var hasOlder by mutableStateOf(false); private set
    /** 轮询出错时的小提示（不打断阅读）；下一次成功后清掉。 */
    var pollError by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null)

    var input by mutableStateOf("")
    var sending by mutableStateOf(false); private set

    /** 429 后剩余秒数，> 0 时禁用发送。 */
    var cooldown by mutableIntStateOf(0); private set
    private var cooldownJob: Job? = null

    /** GET 返回的 muted_until。 */
    private var mutedUntil by mutableStateOf<String?>(null)
    /** 发送时收到 403 muted 的服务器原话（GET 还没来得及反映时用）。 */
    private var mutedMessage by mutableStateOf<String?>(null)
    /** 班级已隐藏 / 学期已归档（409 class_archived）。 */
    var archivedMessage by mutableStateOf<String?>(null); private set

    private var nextLocalId = -1L

    fun bind(id: Int) {
        if (classId == id) {
            // 切回「交流」Tab：补拉一次离开期间的新消息（之后由可见轮询接手）
            if (messages.isNotEmpty()) viewModelScope.launch { fetchNewer() }
            return
        }
        classId = id
        loadLatest()
        if (account.me.value == null) viewModelScope.launch { account.refreshMe() }
    }

    /** 进入频道：最新 50 条。 */
    fun loadLatest() {
        if (classId < 0) return
        loading = true
        loadError = null
        viewModelScope.launch {
            repo.latest(classId)
                .onSuccess { p ->
                    messages = mergeMessages(messages, p.items)
                    hasOlder = p.items.size >= CHAT_PAGE
                    applyPage(p)
                }
                .onFailure { loadError = it.userMessage }
            loading = false
        }
    }

    /**
     * 拉新消息（after_id）。只由频道页在可见时调用（[pollWhileVisible]），所以写成 suspend、在调用方的协程里跑：
     * 页面离开或切后台时调用方取消，请求也一起取消。
     */
    suspend fun fetchNewer() {
        if (classId < 0 || loading) return
        val after = messages.lastOrNull()?.id
        if (after == null) {
            // 还没拿到过任何消息（空频道或首屏失败）：仍用「最新 50 条」
            Log.d(TAG, "poll class=$classId latest")
            repo.latest(classId).onSuccess { p ->
                messages = mergeMessages(messages, p.items); hasOlder = p.items.size >= CHAT_PAGE; loadError = null; applyPage(p)
            }.onFailure { pollError = it.userMessage }
            return
        }
        var cursor: Long = after
        repeat(CHAT_CATCHUP_PAGES) {
            Log.d(TAG, "poll class=$classId after_id=$cursor")
            val r = repo.newer(classId, cursor)
            val p = r.getOrElse { pollError = it.userMessage; return }
            messages = mergeMessages(messages, p.items)
            applyPage(p)
            if (p.items.size < CHAT_PAGE) return
            cursor = p.items.last().id
        }
    }

    /** 往上翻（before_id）。 */
    fun loadOlder() {
        val first = messages.firstOrNull()?.id ?: return
        if (classId < 0 || loadingOlder || !hasOlder) return
        loadingOlder = true
        viewModelScope.launch {
            repo.older(classId, first)
                .onSuccess { p ->
                    messages = mergeMessages(messages, p.items)
                    hasOlder = p.items.size >= CHAT_PAGE
                    mutedUntil = p.mutedUntil
                }
                .onFailure { toast = it.userMessage }
            loadingOlder = false
        }
    }

    private suspend fun applyPage(p: MessagesPage) {
        pollError = null
        mutedUntil = p.mutedUntil
        if (p.mutedUntil == null) mutedMessage = null
        messages.lastOrNull()?.let { readStore.markRead(classId, it.id) }
    }

    /** 禁言说明；null = 没被禁言。 */
    val mutedNotice: String?
        get() {
            val until = Ddl.parse(mutedUntil)?.takeIf { it.toInstant().isAfter(Instant.now()) }
            if (until == null && mutedMessage == null) return null
            val reason = account.me.value?.restrictions?.firstOrNull { it.kind == "mute" }?.reason?.takeIf { it.isNotBlank() }
            return mutedNoticeText(until?.let { Ddl.display(it) }, reason, mutedMessage)
        }

    val canSend: Boolean
        get() = canSendBody(input) && !sending && cooldown == 0 && mutedNotice == null && archivedMessage == null

    fun send() {
        if (!canSend) return
        val body = input.trim()
        val p = PendingMessage(nextLocalId--, body)
        pending = pending + p
        input = ""
        sending = true
        viewModelScope.launch {
            val r = repo.send(classId, body)
            pending = pending - p
            when (r) {
                is SendResult.Sent -> {
                    messages = mergeMessages(messages, listOf(r.message))
                    readStore.markRead(classId, r.message.id)
                }
                is SendResult.RateLimited -> { rollback(body); startCooldown(r.seconds); toast = r.message }
                is SendResult.Muted -> { rollback(body); mutedMessage = r.message }
                is SendResult.Failed -> {
                    rollback(body)
                    if (r.error.code == "class_archived") archivedMessage = r.error.message
                    else toast = "发送失败：${r.error.message}"
                }
            }
            sending = false
        }
    }

    /** 失败回滚：消息从列表撤下，文字放回输入框（用户在等待时又输入了新内容就不覆盖）。 */
    private fun rollback(body: String) { if (input.isBlank()) input = body }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        val until = System.currentTimeMillis() + seconds * 1000L
        cooldown = cooldownLeft(until, System.currentTimeMillis())
        cooldownJob = viewModelScope.launch {
            while (cooldown > 0) {
                delay(250)
                cooldown = cooldownLeft(until, System.currentTimeMillis())
            }
        }
    }

    /** 删除自己的消息：先从列表撤下，失败放回。 */
    fun delete(m: Message) {
        val before = messages
        messages = messages.filter { it.id != m.id }
        viewModelScope.launch {
            repo.delete(m.id).onSuccess { toast = "已删除" }.onFailure { messages = before; toast = it.userMessage }
        }
    }
}

/** 禁言说明文案（纯函数，便于单测）。 */
fun mutedNoticeText(untilDisplay: String?, reason: String?, serverMessage: String?): String = buildString {
    append(if (untilDisplay != null) "你已被管理员禁言，$untilDisplay 前不能在频道发言" else (serverMessage ?: "你已被管理员禁言，暂时不能在频道发言"))
    if (reason != null) append("。原因：").append(reason)
}
