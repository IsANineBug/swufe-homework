package com.swufe.homework

import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.Message
import com.swufe.homework.data.api.MessagesPage
import com.swufe.homework.data.repo.parseRetryAfter
import com.swufe.homework.data.repo.unreadOf
import com.swufe.homework.ui.chat.ALREADY_REPORTED_TEXT
import com.swufe.homework.ui.chat.CHAT_POLL_MS
import com.swufe.homework.ui.chat.REPORT_DONE_TEXT
import com.swufe.homework.ui.chat.ReportOutcome
import com.swufe.homework.ui.chat.canSendBody
import com.swufe.homework.ui.chat.chatLength
import com.swufe.homework.ui.chat.cooldownLabel
import com.swufe.homework.ui.chat.cooldownLeft
import com.swufe.homework.ui.chat.mergeMessages
import com.swufe.homework.ui.chat.mutedNoticeText
import com.swufe.homework.ui.chat.pollWhileVisible
import com.swufe.homework.ui.chat.reportOutcome
import com.swufe.homework.ui.chat.reportReasonValid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WS11 第 4–5 条：频道分页合并去重、可见时才轮询、限频倒计时、禁言文案、举报结果、小红点。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLogicTest {
    private fun m(id: Long, body: String = "m$id") = Message(id = id, classId = 1, authorNickname = "甲", body = body)
    private fun ids(l: List<Message>) = l.map { it.id }

    // ---- 分页合并去重 ----
    @Test fun mergeAfterAppendsAndDedupes() {
        val cur = listOf(m(10), m(11), m(12))
        // after_id=11 的结果与本地重叠（12），且服务器把 12 的内容更新了
        val merged = mergeMessages(cur, listOf(m(12, "新"), m(13), m(14)))
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), ids(merged))
        assertEquals("同一 id 以新来的为准", "新", merged.first { it.id == 12L }.body)
    }

    @Test fun mergeBeforePrependsSorted() {
        val cur = listOf(m(50), m(51))
        val older = listOf(m(47), m(48), m(49), m(50))
        assertEquals(listOf(47L, 48L, 49L, 50L, 51L), ids(mergeMessages(cur, older)))
    }

    @Test fun mergeOutOfOrderAndDuplicateInput() {
        // 自己刚发的那条先插进来，随后轮询又带回同一条；顺序乱的输入也要按 id 升序
        val cur = mergeMessages(listOf(m(1), m(2)), listOf(m(5)))
        val merged = mergeMessages(cur, listOf(m(4), m(3), m(5), m(3)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ids(merged))
    }

    @Test fun mergeEmptyKeepsSameList() {
        val cur = listOf(m(1))
        assertTrue(mergeMessages(cur, emptyList()) === cur)
        assertEquals(listOf(1L, 2L), ids(mergeMessages(emptyList(), listOf(m(2), m(1)))))
    }

    @Test fun decodesMessagesPage() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val p = json.decodeFromString(
            MessagesPage.serializer(),
            """{"items":[{"id":9007199254740,"class_id":3,"author_nickname":"乙","is_mine":false,"body":"hi","created_at":"2026-10-25T15:00:00+08:00"}],"muted_until":null}""",
        )
        assertEquals(9007199254740L, p.items.single().id)
        assertNull(p.mutedUntil)
    }

    // ---- 轮询只在可见时发生 ----
    @Test fun pollsOnlyWhileVisible() = runTest {
        val visible = MutableStateFlow(true)
        var ticks = 0
        val job = launch { pollWhileVisible(visible, CHAT_POLL_MS) { ticks++ } }
        runCurrent()
        assertEquals("进入时不立即拉（最新 50 条已拉过）", 0, ticks)
        advanceTimeBy(CHAT_POLL_MS * 3 + 1)
        assertEquals(3, ticks)

        // 切后台 / 离开：立即停，之后不论多久都不再拉
        visible.value = false
        runCurrent()
        advanceTimeBy(CHAT_POLL_MS * 10)
        assertEquals(3, ticks)

        // 回到前台：立刻补拉一次，然后每 12 秒
        visible.value = true
        runCurrent()
        assertEquals(4, ticks)
        advanceTimeBy(CHAT_POLL_MS + 1)
        assertEquals(5, ticks)

        // 页面离开组合（调用方作用域取消）：彻底停止
        job.cancel()
        advanceTimeBy(CHAT_POLL_MS * 10)
        assertEquals(5, ticks)
    }

    @Test fun neverPollsWhenStartedInvisible() = runTest {
        val visible = MutableStateFlow(false)
        var ticks = 0
        val job = launch { pollWhileVisible(visible, CHAT_POLL_MS) { ticks++ } }
        advanceTimeBy(CHAT_POLL_MS * 20)
        assertEquals(0, ticks)
        // 第一次变为可见不补拉（视同进入页面）
        visible.value = true
        runCurrent()
        assertEquals(0, ticks)
        advanceTimeBy(CHAT_POLL_MS + 1)
        assertEquals(1, ticks)
        job.cancel()
    }

    @Test fun leavingCancelsPendingWait() = runTest {
        val visible = MutableStateFlow(true)
        var ticks = 0
        val job = launch { pollWhileVisible(visible, CHAT_POLL_MS) { ticks++ } }
        advanceTimeBy(CHAT_POLL_MS - 1) // 差 1 毫秒就该拉了
        visible.value = false
        runCurrent()
        advanceTimeBy(CHAT_POLL_MS * 5)
        assertEquals("不可见后那一次也不该发生", 0, ticks)
        job.cancel()
    }

    // ---- 限频倒计时 ----
    @Test fun retryAfterParsing() {
        assertEquals(7, parseRetryAfter("7"))
        assertEquals(7, parseRetryAfter(" 7 "))
        assertEquals(10, parseRetryAfter(null))
        assertEquals(10, parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(1, parseRetryAfter("0"))
        assertEquals(3600, parseRetryAfter("99999"))
    }

    @Test fun cooldownCountsDownAndRoundsUp() {
        val until = 10_000L
        assertEquals(10, cooldownLeft(until, 0))
        assertEquals(10, cooldownLeft(until, 1))       // 9.999 秒 → 显示 10
        assertEquals(1, cooldownLeft(until, 9_001))
        assertEquals(0, cooldownLeft(until, 10_000))
        assertEquals(0, cooldownLeft(until, 20_000))
        assertEquals("8 秒后可再发", cooldownLabel(8))
    }

    @Test fun bodyLengthRules() {
        assertFalse(canSendBody("   "))
        assertTrue(canSendBody(" 好 "))
        assertTrue(canSendBody("字".repeat(300)))
        assertFalse(canSendBody("字".repeat(301)))
        // 首尾空白不计；emoji 按 1 个字
        assertEquals(1, chatLength("  😀  "))
        assertTrue(canSendBody("😀".repeat(300)))
    }

    @Test fun mutedNotice() {
        assertEquals("你已被管理员禁言，10月26日 周一 15:00 前不能在频道发言。原因：刷屏", mutedNoticeText("10月26日 周一 15:00", "刷屏", null))
        assertEquals("你已被禁言", mutedNoticeText(null, null, "你已被禁言"))
        assertEquals("你已被管理员禁言，暂时不能在频道发言", mutedNoticeText(null, null, null))
    }

    // ---- 举报 ----
    @Test fun reportOutcomes() {
        assertEquals(ReportOutcome.Done(REPORT_DONE_TEXT), reportOutcome(Result.success(Unit)))
        assertEquals(
            ReportOutcome.Done(ALREADY_REPORTED_TEXT),
            reportOutcome(Result.failure(ApiException("already_reported", "服务器原话", 409))),
        )
        assertEquals("你已经举报过，管理员处理中", ALREADY_REPORTED_TEXT)
        assertEquals(
            ReportOutcome.Retry("今天举报次数已达上限"),
            reportOutcome(Result.failure(ApiException("rate_limited", "今天举报次数已达上限", 429))),
        )
    }

    @Test fun reportReason() {
        assertFalse(reportReasonValid(""))
        assertFalse(reportReasonValid("  "))
        assertTrue(reportReasonValid("广告"))
        assertTrue(reportReasonValid("a".repeat(200)))
        assertFalse(reportReasonValid("a".repeat(201)))
    }

    // ---- 小红点 ----
    @Test fun unreadDots() {
        val latest = mapOf(1 to 100L, 2 to 50L, 3 to 7L)
        val read = mapOf(1 to 100L, 2 to 49L)
        assertEquals(setOf(2, 3), unreadOf(latest, read))
        assertEquals(emptySet<Int>(), unreadOf(emptyMap(), read))
    }

    @Test fun decodesLastMessageId() {
        val json = Json { ignoreUnknownKeys = true }
        val l = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ClassInfo.serializer()),
            """[{"id":1,"name":"a","last_message_id":42},{"id":2,"name":"b","last_message_id":null},{"id":3,"name":"c"}]""",
        )
        assertEquals(listOf(42L, null, null), l.map { it.lastMessageId })
    }
}
