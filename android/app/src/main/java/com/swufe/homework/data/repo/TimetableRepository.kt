package com.swufe.homework.data.repo

import com.swufe.homework.data.api.AiJob
import com.swufe.homework.data.api.AiJobRequest
import com.swufe.homework.data.api.AiTimetableOutput
import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.Period
import com.swufe.homework.data.api.SlotInput
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.TimetableImportRequest
import com.swufe.homework.data.api.TimetableImportResult
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.local.PeriodEntity
import com.swufe.homework.data.local.TimetableDao
import com.swufe.homework.data.local.toPeriod
import com.swufe.homework.data.local.toSlot
import com.swufe.homework.util.ImageCompressor
import com.swufe.homework.util.SlotDraft
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 西财作息（requirements.md §四），/meta 拿不到时兜底。 */
val DEFAULT_PERIODS = listOf(
    "08:30" to "09:15", "09:25" to "10:10", "10:20" to "11:05", "11:15" to "12:00",
    "13:00" to "13:45", "13:55" to "14:40", "14:50" to "15:35", "15:45" to "16:30",
    "16:40" to "17:25", "18:30" to "19:15", "19:25" to "20:10", "20:20" to "21:05",
).mapIndexed { i, (s, e) -> Period(i + 1, s, e) }

@Singleton
class TimetableRepository @Inject constructor(
    private val api: SwufeApi,
    private val dao: TimetableDao,
    private val json: Json,
    private val compressor: ImageCompressor,
) {
    val classes: Flow<List<ClassInfo>> = combine(dao.classes(), dao.slots()) { cs, ss ->
        val byClass = ss.groupBy { it.classId }
        cs.map { c -> ClassInfo(c.id, c.name, c.teacher, byClass[c.id].orEmpty().map { it.toSlot() }, c.memberCount) }
    }

    val periods: Flow<List<Period>> = dao.periods().map { p -> if (p.size == 12) p.map { it.toPeriod() } else DEFAULT_PERIODS }

    suspend fun refreshMeta(): Result<Unit> = apiCall {
        val m = api.meta()
        if (m.periods.isNotEmpty()) dao.insertPeriods(m.periods.map { PeriodEntity(it.no, it.start, it.end) })
    }

    suspend fun refreshClasses(): Result<Unit> = apiCall { dao.replaceAll(api.myClasses()) }

    suspend fun classDetail(id: Int): Result<ClassInfo> = apiCall { api.classInfo(id) }

    suspend fun cachedClass(id: Int): ClassInfo? =
        dao.classById(id)?.let { c -> ClassInfo(c.id, c.name, c.teacher, dao.slotsOf(id).map { it.toSlot() }, c.memberCount) }

    suspend fun leave(id: Int): Result<Unit> = apiCall {
        val r = api.leaveClass(id)
        if (!r.isSuccessful && r.code() != 404) throw retrofit2.HttpException(r)
        dao.removeClass(id)
    }

    suspend fun search(q: String): Result<List<ClassInfo>> = apiCall { api.searchClasses(q.trim()) }

    suspend fun aiOnline(): Boolean = apiCall { api.aiStatus().online }.getOrDefault(false)

    suspend fun import(slots: List<SlotInput>): Result<TimetableImportResult> = apiCall {
        val r = api.importTimetable(TimetableImportRequest(slots))
        dao.replaceAll(api.myClasses())
        r
    }

    /**
     * 上传 1–2 张截图 → parse_timetable → 轮询（1.5 秒一次，最多 2 分钟）。
     * 返回给确认页的草稿；AI 只出草稿，不入库。
     */
    suspend fun recognize(images: List<Uri>, onStage: (String) -> Unit): Result<List<SlotDraft>> = apiCall {
        require(images.size in 1..2)
        onStage("正在上传截图…")
        val attachments = images.map { uri ->
            val bytes = compressor.compress(uri)
            val part = MultipartBody.Part.createFormData(
                "file", "timetable.jpg", bytes.toRequestBody("image/jpeg".toMediaType()),
            )
            api.upload(part)
        }
        onStage("电脑正在识别…")
        val input = buildJsonObject {
            putJsonArray("image_urls") { attachments.forEach { add(JsonPrimitive(it.url)) } }
            putJsonArray("attachment_ids") { attachments.forEach { add(JsonPrimitive(it.id)) } }
        }
        var job: AiJob = api.createAiJob(AiJobRequest(kind = "parse_timetable", input = input))
        val deadline = System.currentTimeMillis() + 120_000
        while (job.status == "queued" || job.status == "running") {
            if (System.currentTimeMillis() > deadline) throw ApiException("expired", "识别超时，请手动添加")
            delay(1500)
            job = api.aiJob(job.id)
        }
        when (job.status) {
            "done" -> {
                val out = json.decodeFromJsonElement(AiTimetableOutput.serializer(), job.output ?: buildJsonObject { put("slots", kotlinx.serialization.json.JsonArray(emptyList())) })
                out.slots.map { SlotDraft.fromAi(it) }
            }
            "expired" -> throw ApiException("expired", "识别超时，请手动添加")
            else -> throw ApiException("ai_failed", job.error?.let { "识别失败：$it" } ?: "识别失败，请手动添加")
        }
    }
}
