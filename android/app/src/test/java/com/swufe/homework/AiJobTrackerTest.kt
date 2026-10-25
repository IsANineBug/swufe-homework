package com.swufe.homework

import com.swufe.homework.data.api.AiJob
import com.swufe.homework.data.repo.AiJobBackend
import com.swufe.homework.data.repo.AiJobState
import com.swufe.homework.data.repo.AiJobStatus
import com.swufe.homework.data.repo.AiJobStore
import com.swufe.homework.data.repo.AiJobTracker
import com.swufe.homework.data.repo.AiJobTracker.Companion.DEADLINE_MS
import com.swufe.homework.data.repo.AiJobTracker.Companion.PARSE_ASSIGNMENT
import com.swufe.homework.data.repo.AiJobTracker.Companion.PARSE_TIMETABLE
import com.swufe.homework.data.repo.AiJobTracker.Companion.RESULT_TTL_MS
import com.swufe.homework.ui.me.restrictionText
import com.swufe.homework.data.api.Restriction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** 后台识图：恢复逻辑、去重、轮询到期（虚拟时间，不依赖 Android 设备）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiJobTrackerTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val base = 1_700_000_000_000L

    private class FakeBackend : AiJobBackend {
        var nextId = 100
        var creates = 0
        var gets = 0
        val lastInputs = mutableListOf<JsonObject>()
        /** jobId → 每次 GET 返回的状态序列（用完后重复最后一个）。 */
        val script = mutableMapOf<Int, ArrayDeque<AiJob>>()
        val notFound = mutableSetOf<Int>()
        val offline = mutableSetOf<Int>()
        override suspend fun create(kind: String, classId: Int?, input: JsonObject): AiJob {
            creates++
            lastInputs += input
            return AiJob(nextId++, kind, "queued")
        }
        override suspend fun get(jobId: Int): AiJob {
            gets++
            if (jobId in offline) throw java.io.IOException("offline")
            if (jobId in notFound) throw HttpException(Response.error<Any>(404, "{}".toResponseBody()))
            val q = script[jobId] ?: return AiJob(jobId, "x", "running")
            return if (q.size > 1) q.removeFirst() else q.first()
        }
    }

    private class FakeStore(var saved: List<AiJobState> = emptyList()) : AiJobStore {
        override suspend fun load() = saved
        override suspend fun save(jobs: List<AiJobState>) { saved = jobs }
    }

    private fun TestScope.tracker(backend: AiJobBackend, store: AiJobStore): Pair<AiJobTracker, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return AiJobTracker(backend, store, scope, json, now = { base + testScheduler.currentTime }) to scope
    }

    private fun done(id: Int, kind: String = PARSE_TIMETABLE) = AiJob(id, kind, "done", output = buildJsonObject {
        putJsonArray("slots") { add(buildJsonObject { put("name", JsonPrimitive("高等数学")); put("start_visible", JsonPrimitive(false)) }) }
    })

    @Test fun sameKindRunningIsReused() = runTest {
        val b = FakeBackend(); val st = FakeStore()
        val (t, scope) = tracker(b, st)
        val first = t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow()
        val again = t.start(PARSE_TIMETABLE, listOf(1, 2)).getOrThrow()
        assertEquals(first.jobId, again.jobId)
        assertEquals(1, b.creates)
        // 不同 kind 照常新建
        val other = t.start(PARSE_ASSIGNMENT, listOf(3), mapOf("class_id" to "7"), text = "第三章", classId = 7).getOrThrow()
        assertEquals(2, b.creates)
        assertTrue(other.jobId != first.jobId)
        assertEquals(2, t.jobs.value.size)
        assertEquals(2, st.saved.size)
        scope.cancel()
    }

    @Test fun concurrentStartsCreateOnce() = runTest {
        val b = FakeBackend()
        val (t, scope) = tracker(b, FakeStore())
        val ids = (1..5).map { async { t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow().jobId } }.awaitAll()
        assertEquals(1, b.creates)
        assertEquals(1, ids.toSet().size)
        scope.cancel()
    }

    @Test fun inputOnlyHasContractFields() = runTest {
        val b = FakeBackend()
        val (t, scope) = tracker(b, FakeStore())
        t.start(PARSE_TIMETABLE, listOf(1, 2), mapOf("image_uris" to "content://x"), text = "忽略")
        t.start(PARSE_ASSIGNMENT, listOf(5), mapOf("class_id" to "7"), text = "第三章习题", classId = 7)
        val tt = b.lastInputs[0]; val pa = b.lastInputs[1]
        assertEquals(setOf("attachment_ids"), tt.keys) // context 不发服务器；parse_timetable 不带 text
        assertEquals(listOf(1, 2), tt["attachment_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals(setOf("text", "attachment_ids"), pa.keys)
        scope.cancel()
    }

    @Test fun pollsUntilDoneThenEmitsAndKeepsResultUntilTaken() = runTest {
        val b = FakeBackend(); val st = FakeStore()
        val (t, scope) = tracker(b, st)
        val events = mutableListOf<AiJobState>()
        val collector = launch { t.events.toList(events) }
        val s = t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow()
        b.script[s.jobId] = ArrayDeque(listOf(AiJob(s.jobId, PARSE_TIMETABLE, "running"), done(s.jobId)))
        advanceTimeBy(1_600); runCurrent()
        assertEquals(AiJobStatus.Running, t.job(PARSE_TIMETABLE)!!.status)
        advanceTimeBy(1_600); runCurrent()
        val d = t.job(PARSE_TIMETABLE)!!
        assertEquals(AiJobStatus.Done, d.status)
        assertEquals(1, events.size)
        assertEquals("高等数学", t.timetableSlots(d).single().name)
        assertFalse(t.timetableSlots(d).single().startVisible)
        // 结果存着，直到确认页取走
        assertEquals(AiJobStatus.Done, st.saved.single().status)
        val gets = b.gets
        advanceTimeBy(10_000); runCurrent()
        assertEquals(gets, b.gets) // 完成后不再轮询
        assertEquals(s.jobId, t.take(s.jobId)!!.jobId)
        assertTrue(st.saved.isEmpty())
        assertNull(t.job(PARSE_TIMETABLE))
        collector.cancel(); scope.cancel()
    }

    @Test fun timesOutAt150SecondsAndAllowsNewJob() = runTest {
        val b = FakeBackend(); val st = FakeStore()
        val (t, scope) = tracker(b, st)
        val s = t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow()
        advanceTimeBy(DEADLINE_MS - 2_000); runCurrent()
        assertEquals(AiJobStatus.Running, t.job(PARSE_TIMETABLE)!!.status)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(AiJobStatus.Timeout, t.job(PARSE_TIMETABLE)!!.status)
        assertTrue("超时的不再存", st.saved.isEmpty())
        val n = t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow()
        assertTrue(n.jobId != s.jobId)
        assertEquals(2, b.creates)
        scope.cancel()
    }

    @Test fun failedAndNotFound() = runTest {
        val b = FakeBackend()
        val (t, scope) = tracker(b, FakeStore())
        val a = t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow()
        b.script[a.jobId] = ArrayDeque(listOf(AiJob(a.jobId, PARSE_TIMETABLE, "failed", error = "图片看不清")))
        val c = t.start(PARSE_ASSIGNMENT, listOf(2), classId = 1).getOrThrow()
        b.notFound += c.jobId
        advanceTimeBy(1_600); runCurrent()
        assertEquals("识别失败：图片看不清", t.job(PARSE_TIMETABLE)!!.message)
        assertEquals(AiJobStatus.Failed, t.job(PARSE_ASSIGNMENT)!!.status)
        scope.cancel()
    }

    @Test fun dismissStopsPolling() = runTest {
        val b = FakeBackend(); val st = FakeStore()
        val (t, scope) = tracker(b, st)
        val s = t.start(PARSE_TIMETABLE, listOf(1)).getOrThrow()
        advanceTimeBy(1_600); runCurrent()
        val gets = b.gets
        t.dismiss(s.jobId)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(gets, b.gets)
        assertTrue(t.jobs.value.isEmpty())
        assertTrue(st.saved.isEmpty())
        scope.cancel()
    }

    // ---- 恢复 ----

    @Test fun restoreResumesUnexpiredRunningJob() = runTest {
        val b = FakeBackend()
        val st = FakeStore(listOf(AiJobState(42, PARSE_TIMETABLE, mapOf("attachment_ids" to "1"), createdAt = base - 60_000)))
        b.script[42] = ArrayDeque(listOf(done(42)))
        val (t, scope) = tracker(b, st)
        t.restore()
        assertEquals(AiJobStatus.Running, t.job(PARSE_TIMETABLE)!!.status)
        assertEquals(mapOf("attachment_ids" to "1"), t.job(PARSE_TIMETABLE)!!.context)
        advanceTimeBy(1_600); runCurrent()
        assertEquals(AiJobStatus.Done, t.job(PARSE_TIMETABLE)!!.status)
        // 再次恢复不重复
        t.restore()
        assertEquals(1, t.jobs.value.size)
        // 恢复后同 kind 仍去重：返回已有任务，不新建
        assertEquals(0, b.creates)
        scope.cancel()
    }

    @Test fun restoreRunningJobThatTimesOutAfterRelaunch() = runTest {
        val b = FakeBackend()
        // 划掉 App 时任务刚建 100 秒，重开后还要等 50 秒，仍未完成 → 超时
        val st = FakeStore(listOf(AiJobState(7, PARSE_TIMETABLE, createdAt = base - 100_000)))
        val (t, scope) = tracker(b, st)
        t.restore()
        advanceTimeBy(52_000); runCurrent()
        assertEquals(AiJobStatus.Timeout, t.job(PARSE_TIMETABLE)!!.status)
        assertTrue(st.saved.isEmpty())
        scope.cancel()
    }

    @Test fun restoreMarksExpiredAsTimeoutAndRemoves() = runTest {
        val b = FakeBackend()
        val st = FakeStore(listOf(AiJobState(9, PARSE_ASSIGNMENT, mapOf("class_id" to "3"), createdAt = base - DEADLINE_MS - 1)))
        val (t, scope) = tracker(b, st)
        t.restore()
        val s = t.job(PARSE_ASSIGNMENT)!!
        assertEquals(AiJobStatus.Timeout, s.status)
        assertEquals(AiJobTracker.TIMEOUT_MESSAGE, s.message)
        assertTrue(st.saved.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals("过期的只在重开时查一次服务器，不再轮询", 1, b.gets)
        scope.cancel()
    }

    // ---- rc2 修复 3：过期任务重开先问服务器 ----

    private fun TestScope.restoreOverdue(b: FakeBackend, kind: String = PARSE_TIMETABLE): Triple<AiJobTracker, FakeStore, CoroutineScope> {
        val st = FakeStore(listOf(AiJobState(9, kind, mapOf("class_id" to "3"), createdAt = base - DEADLINE_MS - 60_000)))
        val (t, scope) = tracker(b, st)
        return Triple(t, st, scope)
    }

    @Test fun restoreOverdueButServerDoneShowsResult() = runTest {
        val b = FakeBackend().apply { script[9] = ArrayDeque(listOf(done(9))) }
        val (t, st, scope) = restoreOverdue(b)
        t.restore()
        val s = t.job(PARSE_TIMETABLE)!!
        assertEquals(AiJobStatus.Done, s.status)
        assertEquals("高等数学", t.timetableSlots(s).single().name)
        assertEquals(mapOf("class_id" to "3"), s.context)
        assertEquals("结果存下来等用户确认", listOf(9), st.saved.map { it.jobId })
        // 按 24 小时保留规则：从现在起算，23 小时后重开仍在，25 小时后丢弃
        val (kept23, _) = AiJobTracker.partitionRestored(st.saved, base + 23 * 3_600_000L)
        assertEquals(1, kept23.size)
        val (kept25, _) = AiJobTracker.partitionRestored(st.saved, base + 25 * 3_600_000L)
        assertTrue(kept25.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals("不再轮询", 1, b.gets)
        scope.cancel()
    }

    @Test fun restoreOverdueButServerFailedShowsReason() = runTest {
        val b = FakeBackend().apply { script[9] = ArrayDeque(listOf(AiJob(9, PARSE_ASSIGNMENT, "failed", error = "看不清"))) }
        val (t, st, scope) = restoreOverdue(b, PARSE_ASSIGNMENT)
        t.restore()
        val s = t.job(PARSE_ASSIGNMENT)!!
        assertEquals(AiJobStatus.Failed, s.status)
        assertEquals("识别失败：看不清", s.message)
        assertEquals(1, st.saved.size)
        scope.cancel()
    }

    @Test fun restoreOverdueStillRunningExpiredNotFoundOrOfflineIsTimeout() = runTest {
        for (setup in listOf<(FakeBackend) -> Unit>(
            { it.script[9] = ArrayDeque(listOf(AiJob(9, PARSE_TIMETABLE, "queued"))) },
            { it.script[9] = ArrayDeque(listOf(AiJob(9, PARSE_TIMETABLE, "running"))) },
            { it.script[9] = ArrayDeque(listOf(AiJob(9, PARSE_TIMETABLE, "expired"))) },
            { it.notFound += 9 },
            { it.offline += 9 },
        )) {
            val b = FakeBackend().also(setup)
            val (t, st, scope) = restoreOverdue(b)
            t.restore()
            val s = t.job(PARSE_TIMETABLE)!!
            assertEquals(AiJobStatus.Timeout, s.status)
            assertEquals(AiJobTracker.TIMEOUT_MESSAGE, s.message)
            assertTrue(st.saved.isEmpty())
            assertEquals(1, b.gets)
            scope.cancel()
        }
    }

    @Test fun partitionSendsOverdueRunningToServerCheck() {
        val fresh = AiJobState(1, PARSE_TIMETABLE, createdAt = base - 10_000)
        val overdue = AiJobState(2, PARSE_ASSIGNMENT, createdAt = base - DEADLINE_MS)
        val (kept, toCheck) = AiJobTracker.partitionRestored(listOf(fresh, overdue), base)
        assertEquals(listOf(1), kept.map { it.jobId })
        assertEquals(listOf(overdue), toCheck)
    }

    @Test fun resolveOverdueMapping() {
        val s = AiJobState(5, PARSE_TIMETABLE, createdAt = base - DEADLINE_MS - 1)
        fun r(status: String) = AiJobTracker.resolveOverdue(s, Result.success(AiJob(5, PARSE_TIMETABLE, status)), base).status
        assertEquals(AiJobStatus.Done, r("done"))
        assertEquals(AiJobStatus.Failed, r("failed"))
        assertEquals(AiJobStatus.Timeout, r("queued"))
        assertEquals(AiJobStatus.Timeout, r("running"))
        assertEquals(AiJobStatus.Timeout, r("expired"))
        assertEquals(AiJobStatus.Timeout, AiJobTracker.resolveOverdue(s, Result.failure(java.io.IOException()), base).status)
        assertEquals(base, AiJobTracker.resolveOverdue(s, Result.success(AiJob(5, PARSE_TIMETABLE, "done")), base).finishedAt)
    }

    @Test fun restoreKeepsUntakenResultForADay() = runTest {
        val keep = AiJobState(1, PARSE_TIMETABLE, createdAt = base - 3_600_000, status = AiJobStatus.Done, output = JsonObject(mapOf("slots" to JsonArray(emptyList()))), finishedAt = base - 3_590_000)
        val old = AiJobState(2, PARSE_ASSIGNMENT, createdAt = base - RESULT_TTL_MS - 10_000, status = AiJobStatus.Done, finishedAt = base - RESULT_TTL_MS - 5_000)
        val (kept, toCheck) = AiJobTracker.partitionRestored(listOf(keep, old), base)
        assertEquals(listOf(keep), kept)
        assertTrue(toCheck.isEmpty())
    }

    @Test fun partitionKeepsLatestPerKind() {
        val a = AiJobState(1, PARSE_TIMETABLE, createdAt = base - 10_000)
        val b = AiJobState(2, PARSE_TIMETABLE, createdAt = base - 5_000)
        val (kept, _) = AiJobTracker.partitionRestored(listOf(a, b), base)
        assertEquals(listOf(2), kept.map { it.jobId })
    }

    @Test fun persistedStateRoundTrips() {
        val s = AiJobState(5, PARSE_ASSIGNMENT, mapOf("class_id" to "3", "text" to "第三章\n习题"), base, AiJobStatus.Done,
            output = buildJsonObject { putJsonObject("x") {} }, finishedAt = base + 1)
        val ser = kotlinx.serialization.builtins.ListSerializer(AiJobState.serializer())
        assertEquals(listOf(s), json.decodeFromString(ser, json.encodeToString(ser, listOf(s))))
    }

    @Test fun restrictionWording() {
        assertEquals(
            "你被禁言到 10月30日 周五 12:00，原因：刷屏",
            restrictionText(Restriction("mute", "2026-10-30T12:00:00+08:00", "刷屏")).replace(Regex("^你被禁言到 (\\d{4}年)?"), "你被禁言到 "),
        )
        assertTrue(restrictionText(Restriction("post_ban", null, "")).startsWith("你被禁发"))
    }
}
