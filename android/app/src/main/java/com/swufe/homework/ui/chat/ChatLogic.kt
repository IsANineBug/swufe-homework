package com.swufe.homework.ui.chat

import com.swufe.homework.data.api.Message
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.ceil

// 频道的纯逻辑（不依赖 Android），单测见 test/ChatLogicTest.kt。

/** 打开频道时每 12 秒拉一次新消息（requirements 十三.7：10–15 秒）。 */
const val CHAT_POLL_MS = 12_000L

/** 一条消息最多 300 字（去掉首尾空白后，按字符计，emoji 算 1 个）。 */
const val CHAT_MAX_LEN = 300

/** 一次轮询最多连续追几页（离开太久回来、一下子有很多新消息时），防止死循环。 */
const val CHAT_CATCHUP_PAGES = 4

/**
 * 合并一批消息（after_id 拉到的新消息、before_id 翻到的旧消息、刚发出的那条），按 id 去重并升序。
 * 同一 id 以新来的为准；已删除的消息服务器不再返回，删除由调用方单独移除。
 */
fun mergeMessages(current: List<Message>, incoming: List<Message>): List<Message> {
    if (incoming.isEmpty()) return current
    val byId = LinkedHashMap<Long, Message>(current.size + incoming.size)
    current.forEach { byId[it.id] = it }
    incoming.forEach { byId[it.id] = it }
    return byId.values.sortedBy { it.id }
}

/** 用户可见的字数（去掉首尾空白，按 Unicode 码点）。 */
fun chatLength(text: String): Int = text.trim().let { it.codePointCount(0, it.length) }

fun canSendBody(text: String): Boolean = chatLength(text) in 1..CHAT_MAX_LEN

/** 限频剩余秒数（向上取整）；到点或已过返回 0。 */
fun cooldownLeft(untilMs: Long, nowMs: Long): Int =
    if (nowMs >= untilMs) 0 else ceil((untilMs - nowMs) / 1000.0).toInt()

fun cooldownLabel(left: Int): String = "$left 秒后可再发"

/**
 * 只在 [visible] 为 true 时每 [intervalMs] 调一次 [tick]；变为 false（离开频道、切后台）立即取消正在等的那次。
 * 首次可见不立刻拉（进入时的「最新 50 条」已经拉过）；从后台回来时立刻补拉一次再按间隔继续。
 * 由调用方的协程作用域决定生命周期（频道页 Composable 离开组合时整个循环被取消）。
 */
suspend fun pollWhileVisible(
    visible: Flow<Boolean>,
    intervalMs: Long = CHAT_POLL_MS,
    tick: suspend () -> Unit,
) {
    var firstVisible = true
    visible.distinctUntilChanged().collectLatest { v ->
        if (!v) return@collectLatest
        if (!firstVisible) tick()
        firstVisible = false
        while (true) {
            delay(intervalMs)
            tick()
        }
    }
}
