package com.swufe.homework.data.repo

import android.net.Uri
import com.swufe.homework.data.api.AiAssignmentDraft
import com.swufe.homework.data.api.AiDedupeOutput
import com.swufe.homework.data.api.AiJob
import com.swufe.homework.data.api.AiJobRequest
import com.swufe.homework.data.api.AiSpamOutput
import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.AssignmentCreate
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.Attachment
import com.swufe.homework.data.api.CandidatesRequest
import com.swufe.homework.data.api.DeleteVoteResult
import com.swufe.homework.data.api.HomeworkApi
import com.swufe.homework.data.api.Leaderboard
import com.swufe.homework.data.api.MergeProposal
import com.swufe.homework.data.api.MergeProposalCreate
import com.swufe.homework.data.api.PointsPage
import com.swufe.homework.data.api.Source
import com.swufe.homework.data.api.SourceCreate
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.api.orThrow
import com.swufe.homework.util.ImageCompressor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** WS5：作业、来源、社区操作、积分、AI 草稿。 */
@Singleton
class AssignmentRepository @Inject constructor(
    private val api: HomeworkApi,
    private val core: SwufeApi,
    private val reminders: ReminderSync,
    private val compressor: ImageCompressor,
    private val json: Json,
) {
    // ---- 列表 ----
    suspend fun feed(includeDone: Boolean): Result<List<AssignmentSummary>> =
        apiCall { api.feed(includeDone) }.onSuccess { reminders.apply(it) }

    suspend fun classAssignments(classId: Int, from: String?, to: String?): Result<List<AssignmentSummary>> =
        apiCall { api.classAssignments(classId, from, to) }

    suspend fun detail(id: Int): Result<Assignment> =
        apiCall { api.assignment(id) }
            .onSuccess {
                // 已被合并：返回的是合并目标，旧 id 的提醒立即取消，不等 /sync 的 removed_ids
                if (it.id != id) reminders.forget(id)
                reminders.apply(listOf(it.toSummary()))
            }
            .onFailure { if ((it as? ApiException)?.http == 404) reminders.forget(id) }

    // ---- 发布 / 补充 / 编辑 ----
    suspend fun candidates(classId: Int, title: String, ddl: String?): Result<List<AssignmentSummary>> =
        apiCall { api.candidates(classId, CandidatesRequest(title, ddl)) }

    suspend fun create(classId: Int, body: AssignmentCreate): Result<Assignment> =
        apiCall { api.createAssignment(classId, body) }.onSuccess { reminders.apply(listOf(it.toSummary())) }

    suspend fun addSource(assignmentId: Int, body: SourceCreate): Result<Source> =
        apiCall { api.addSource(assignmentId, body) }.onSuccess { reminders.sync() }

    suspend fun editSource(sourceId: Int, body: SourceCreate): Result<Source> =
        apiCall { api.editSource(sourceId, body) }.onSuccess { reminders.sync() }

    suspend fun deleteSource(sourceId: Int): Result<Unit> =
        apiCall { api.deleteSource(sourceId).orThrow() }.onSuccess { reminders.sync() }

    suspend fun uploadImages(uris: List<Uri>): Result<List<Attachment>> = apiCall {
        uris.map { uri ->
            val bytes = compressor.compress(uri) // 约 1 MB，用户无感知（requirements §十一）
            val part = MultipartBody.Part.createFormData("file", "image.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            core.upload(part)
        }
    }

    // ---- 完成状态（只本人可见）----
    suspend fun setDone(id: Int, done: Boolean): Result<Unit> = reminders.setDone(id, done)

    // ---- 社区 ----
    suspend fun thank(sourceId: Int): Result<Unit> = apiCall { api.thank(sourceId).orThrow() }

    suspend fun voteDelete(sourceId: Int): Result<DeleteVoteResult> = apiCall { api.voteDelete(sourceId) }

    suspend fun proposeMerge(fromId: Int, toId: Int): Result<MergeProposal> =
        apiCall { api.proposeMerge(MergeProposalCreate(fromId, toId)) }

    suspend fun acceptMerge(id: Int): Result<MergeProposal> = apiCall { api.acceptMerge(id) }.onSuccess { reminders.sync() }

    suspend fun voteMerge(id: Int): Result<MergeProposal> = apiCall { api.voteMerge(id) }.onSuccess { reminders.sync() }

    suspend fun leaderboard(classId: Int, scope: String): Result<Leaderboard> = apiCall { api.leaderboard(classId, scope) }

    suspend fun points(cursor: String?): Result<PointsPage> = apiCall { api.points(cursor) }

    // ---- AI（只出草稿；离线时 App 置灰，不排队）----
    suspend fun aiOnline(): Boolean = apiCall { core.aiStatus().online }.getOrDefault(false)

    /** parse_assignment：只传 attachment_ids，由服务器签名生成 image_urls（issue #4 裁决 6 同理）。 */
    suspend fun aiParse(classId: Int, text: String, attachmentIds: List<Int>): Result<Pair<Int, AiAssignmentDraft>> = apiCall {
        val input = buildJsonObject {
            if (text.isNotBlank()) put("text", text)
            putJsonArray("attachment_ids") { attachmentIds.forEach { add(JsonPrimitive(it)) } }
        }
        val job = runJob("parse_assignment", classId, input, 120_000)
        job.id to json.decodeFromJsonElement(AiAssignmentDraft.serializer(), job.output ?: JsonObject(emptyMap()))
    }

    /**
     * dedupe_review：只传 candidate_ids（取自 /candidates），候选内容由服务器按 id 读取（ai-contract §3）。
     * draft.ddl 为带 +08:00 的 ISO 时间。失败或超时返回 null，退回服务器的文字匹配结果。
     */
    suspend fun aiDedupe(classId: Int, title: String, body: String, ddl: String?, candidates: List<AssignmentSummary>): AiDedupeOutput? =
        apiCall {
            val input = buildJsonObject {
                putJsonObject("draft") { put("title", title); put("body", body); put("ddl", ddl) }
                putJsonArray("candidate_ids") { candidates.take(3).forEach { add(JsonPrimitive(it.id)) } }
            }
            val job = runJob("dedupe_review", classId, input, 30_000)
            json.decodeFromJsonElement(AiDedupeOutput.serializer(), job.output ?: JsonObject(emptyMap()))
        }.getOrNull()

    /** spam_check：只提示发布者本人，失败或超时返回 null（跳过）。 */
    suspend fun aiSpam(classId: Int, title: String, body: String, attachmentIds: List<Int>): AiSpamOutput? = apiCall {
        val input = buildJsonObject {
            put("title", title); put("body", body)
            putJsonArray("attachment_ids") { attachmentIds.forEach { add(JsonPrimitive(it)) } }
        }
        val job = runJob("spam_check", classId, input, 30_000)
        json.decodeFromJsonElement(AiSpamOutput.serializer(), job.output ?: JsonObject(emptyMap()))
    }.getOrNull()

    /** 发起并轮询（每 1.5 秒），超时或失败抛 ApiException。 */
    private suspend fun runJob(kind: String, classId: Int?, input: JsonObject, timeoutMs: Long): AiJob {
        var job = core.createAiJob(AiJobRequest(kind = kind, classId = classId, input = input))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (job.status == "queued" || job.status == "running") {
            if (System.currentTimeMillis() > deadline) throw ApiException("expired", "识别超时，请手动填写")
            delay(1500)
            job = core.aiJob(job.id)
        }
        return when (job.status) {
            "done" -> job
            "expired" -> throw ApiException("expired", "识别超时，请手动填写")
            else -> throw ApiException("ai_failed", job.error?.let { "识别失败：$it" } ?: "识别失败，请手动填写")
        }
    }
}
