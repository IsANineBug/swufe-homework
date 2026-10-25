package com.swufe.homework.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.swufe.homework.data.api.AiAssignmentDraft
import com.swufe.homework.data.api.AiJob
import com.swufe.homework.data.api.AiJobRequest
import com.swufe.homework.data.api.AiSlot
import com.swufe.homework.data.api.AiTimetableOutput
import com.swufe.homework.data.api.SwufeApi
import com.swufe.homework.data.api.apiCall
import com.swufe.homework.data.api.toApiException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/** 后台识图任务的状态。识别中 / 完成（带结果）/ 失败（带原因）/ 超时。 */
enum class AiJobStatus { Running, Done, Failed, Timeout }

/**
 * 一个被跟踪的 AI 任务。[context] 是确认页需要的东西（如作业草稿页的 class_id），由发起方决定，
 * 只存在本机，不发给服务器（api.yaml：input 里出现未列出的字段返回 400）。
 * 运行中和已完成未取走的任务存 DataStore（键 [AiJobTracker.KEY]），超时的只留在内存里给状态条显示。
 */
@Serializable
data class AiJobState(
    @SerialName("job_id") val jobId: Int,
    val kind: String,
    val context: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAt: Long,
    val status: AiJobStatus = AiJobStatus.Running,
    val output: JsonObject? = null,
    val error: String? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
) {
    val finished: Boolean get() = status != AiJobStatus.Running

    /** 给用户看的失败/超时原因。 */
    val message: String
        get() = when (status) {
            AiJobStatus.Running -> "正在识别…"
            AiJobStatus.Done -> "识别好了"
            AiJobStatus.Failed -> error ?: "识别失败"
            AiJobStatus.Timeout -> AiJobTracker.TIMEOUT_MESSAGE
        }
}

/** 服务器接口（抽出来便于单测）。 */
interface AiJobBackend {
    suspend fun create(kind: String, classId: Int?, input: JsonObject): AiJob
    suspend fun get(jobId: Int): AiJob
}

/** 本地持久化（抽出来便于单测）。 */
interface AiJobStore {
    suspend fun load(): List<AiJobState>
    suspend fun save(jobs: List<AiJobState>)
}

private class ApiJobBackend(private val api: SwufeApi) : AiJobBackend {
    override suspend fun create(kind: String, classId: Int?, input: JsonObject) =
        api.createAiJob(AiJobRequest(kind = kind, classId = classId, input = input))
    override suspend fun get(jobId: Int) = api.aiJob(jobId)
}

private val Context.aiJobStore by preferencesDataStore(name = "ai_jobs")

private class DataStoreJobStore(private val ctx: Context, private val json: Json) : AiJobStore {
    private val key = stringPreferencesKey(AiJobTracker.KEY)
    private val serializer = ListSerializer(AiJobState.serializer())

    override suspend fun load(): List<AiJobState> =
        ctx.aiJobStore.data.first()[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    override suspend fun save(jobs: List<AiJobState>) {
        ctx.aiJobStore.edit { if (jobs.isEmpty()) it.remove(key) else it[key] = json.encodeToString(serializer, jobs) }
    }
}

/**
 * 后台识图（requirements 十三.1）：提交识别后可以去别的页面，App 被划掉重开后继续等。
 *
 * - 只有 [PARSE_TIMETABLE]、[PARSE_ASSIGNMENT] 走这里；dedupe_review、spam_check 仍在调用处同步等待。
 * - 同一种任务每种最多一个：已有进行中的直接返回它，不新建（防重复点击）；已结束的被新任务替换。
 * - 在应用级 scope 里每 1.5 秒轮询，直到 createdAt + 150 秒（服务器 2 分钟过期，多留 30 秒给 worker 回传与网络）。
 * - [restore] 在 App 启动时调用：没过期的继续轮询；过期的先 GET 一次服务器，done/failed 用服务器结果，其余（含断网）标超时并从存储移除；已完成未取走的结果保留 [RESULT_TTL_MS]。
 * - 确认页用 [take] 取走结果，用户放弃用 [dismiss]，两者都会从存储删除。
 * - 不用 WorkManager / 前台服务：进程被杀期间不轮询，重开后再查（服务器任务结束 24 小时内都能查到结果）。
 */
@Singleton
class AiJobTracker internal constructor(
    private val backend: AiJobBackend,
    private val store: AiJobStore,
    private val scope: CoroutineScope,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
) {
    @Inject constructor(api: SwufeApi, @ApplicationContext ctx: Context, json: Json) : this(
        ApiJobBackend(api), DataStoreJobStore(ctx, json), CoroutineScope(SupervisorJob() + Dispatchers.IO), json,
    )

    private val _jobs = MutableStateFlow<List<AiJobState>>(emptyList())
    /** 当前跟踪的任务（每种 kind 最多一条）。 */
    val jobs: StateFlow<List<AiJobState>> = _jobs.asStateFlow()

    private val _events = MutableSharedFlow<AiJobState>(extraBufferCapacity = 8)
    /** 轮询中结束的任务（完成 / 失败 / 超时），用于前台 Snackbar 和后台通知。启动时恢复出的超时不发事件。 */
    val events: SharedFlow<AiJobState> = _events.asSharedFlow()

    private val mutex = Mutex()
    private val pollers = mutableMapOf<Int, Job>()
    private var restored = false

    fun job(kind: String): AiJobState? = _jobs.value.firstOrNull { it.kind == kind }

    /** App 启动时调用一次（重复调用无副作用）。 */
    suspend fun restore() = mutex.withLock { restoreLocked() }

    /**
     * 发起任务。图片只传 attachment_ids（服务器签名），[text] 只用于 parse_assignment。
     * 同 kind 已有进行中的任务时返回它（调用方可比较 context 判断是不是自己的）。
     * 失败返回 ApiException（如 409 ai_offline、429 ai_busy）。
     */
    suspend fun start(
        kind: String,
        attachmentIds: List<Int>,
        context: Map<String, String> = emptyMap(),
        text: String? = null,
        classId: Int? = null,
    ): Result<AiJobState> = mutex.withLock {
        require(kind in TRACKED_KINDS) { "$kind 不走后台识图" }
        restoreLocked()
        _jobs.value.firstOrNull { it.kind == kind && it.status == AiJobStatus.Running }?.let { return@withLock Result.success(it) }
        val input = buildJsonObject {
            if (kind == PARSE_ASSIGNMENT && !text.isNullOrBlank()) put("text", text)
            putJsonArray("attachment_ids") { attachmentIds.forEach { add(JsonPrimitive(it)) } }
        }
        apiCall { backend.create(kind, classId, input) }.map { created ->
            val state = applyRemote(AiJobState(created.id, kind, context, now()), created, now())
            _jobs.value = _jobs.value.filterNot { it.kind == kind } + state
            persistLocked()
            if (state.status == AiJobStatus.Running) launchPoll(state.jobId) else _events.tryEmit(state)
            state
        }
    }

    /** 确认页取走结果：返回并删除。 */
    suspend fun take(jobId: Int): AiJobState? = remove(jobId)

    /** 用户放弃（进行中的停止轮询，结果不要了）。 */
    suspend fun dismiss(jobId: Int) { remove(jobId) }

    /** 退出登录：清空。 */
    suspend fun clear() = mutex.withLock {
        pollers.values.forEach { it.cancel() }
        pollers.clear()
        _jobs.value = emptyList()
        persistLocked()
    }

    fun timetableSlots(s: AiJobState): List<AiSlot> =
        s.output?.let { runCatching { json.decodeFromJsonElement(AiTimetableOutput.serializer(), it).slots }.getOrNull() }.orEmpty()

    fun assignmentDraft(s: AiJobState): AiAssignmentDraft =
        s.output?.let { runCatching { json.decodeFromJsonElement(AiAssignmentDraft.serializer(), it) }.getOrNull() } ?: AiAssignmentDraft()

    private suspend fun remove(jobId: Int): AiJobState? = mutex.withLock {
        val s = _jobs.value.firstOrNull { it.jobId == jobId } ?: return@withLock null
        pollers.remove(jobId)?.cancel()
        _jobs.value = _jobs.value - s
        persistLocked()
        s
    }

    private suspend fun restoreLocked() {
        if (restored) return
        restored = true
        val saved = runCatching { store.load() }.getOrDefault(emptyList())
        val (kept, overdue) = partitionRestored(saved, now())
        // 本地「进行中」但已过期限的：先问一次服务器再定（rc2 修复 3）
        val checked = overdue.map { s ->
            val remote = withTimeoutOrNull(OVERDUE_CHECK_TIMEOUT_MS) { apiCall { backend.get(s.jobId) } }
                ?: Result.failure(java.io.IOException("timeout"))
            resolveOverdue(s, remote, now())
        }
        // 内存里已有的（理论上不会有）优先
        val mem = _jobs.value
        val merged = mem + (kept + checked).filter { s -> mem.none { it.kind == s.kind } }
        _jobs.value = merged.distinctBy { it.kind }
        persistLocked()
        _jobs.value.filter { it.status == AiJobStatus.Running }.forEach { launchPoll(it.jobId) }
    }

    private suspend fun persistLocked() {
        runCatching { store.save(_jobs.value.filter { it.status == AiJobStatus.Running || it.status == AiJobStatus.Done || it.status == AiJobStatus.Failed }) }
    }

    private fun launchPoll(jobId: Int) {
        if (pollers[jobId]?.isActive == true) return
        pollers[jobId] = scope.launch {
            while (true) {
                delay(pollMs)
                val cur = _jobs.value.firstOrNull { it.jobId == jobId && it.status == AiJobStatus.Running } ?: break
                if (now() >= cur.createdAt + DEADLINE_MS) {
                    finish(jobId) { it.copy(status = AiJobStatus.Timeout, finishedAt = now()) }
                    break
                }
                val r = apiCall { backend.get(jobId) }
                val e = r.exceptionOrNull()?.toApiException()
                if (e != null) {
                    // 404：任务不存在（被清理或不是本人的），不再等；其他错误（断网等）继续等到期限
                    if (e.http == 404) {
                        finish(jobId) { it.copy(status = AiJobStatus.Failed, error = "识别任务已失效，请重新识别", finishedAt = now()) }
                        break
                    }
                    continue
                }
                val next = applyRemote(cur, r.getOrThrow(), now())
                if (next.status != AiJobStatus.Running) {
                    finish(jobId) { next }
                    break
                }
            }
        }
    }

    private suspend fun finish(jobId: Int, transform: (AiJobState) -> AiJobState) {
        val done = mutex.withLock {
            val cur = _jobs.value.firstOrNull { it.jobId == jobId && it.status == AiJobStatus.Running } ?: return
            val next = transform(cur)
            _jobs.value = _jobs.value.map { if (it.jobId == jobId) next else it }
            persistLocked()
            pollers.remove(jobId)
            next
        }
        _events.tryEmit(done)
    }

    companion object {
        const val KEY = "pending_ai_jobs"
        const val PARSE_TIMETABLE = "parse_timetable"
        const val PARSE_ASSIGNMENT = "parse_assignment"
        val TRACKED_KINDS = setOf(PARSE_TIMETABLE, PARSE_ASSIGNMENT)
        const val POLL_MS = 1_500L
        /** createdAt + 150 秒后不再等。 */
        const val DEADLINE_MS = 150_000L
        /** 已完成但没去确认的结果在本机留多久（服务器任务结束 24 小时后删除）。 */
        const val RESULT_TTL_MS = 24 * 3600_000L
        /** 重开时对过期任务查服务器的单次等待上限，超过按断网处理。 */
        const val OVERDUE_CHECK_TIMEOUT_MS = 5_000L
        const val TIMEOUT_MESSAGE = "识别超时（超过 2 分钟），请手动添加"

        /**
         * 启动恢复的纯逻辑：返回（继续跟踪的，需要问服务器的）。
         * - 进行中且未到 createdAt + 150 秒：继续轮询；已到：原样放进第二组，由 [resolveOverdue] 按服务器结果定。
         * - 已完成/失败、未被取走：保留到结束后 [RESULT_TTL_MS]，之后丢弃。
         * - 同 kind 有多条时只留最新的一条。
         */
        internal fun partitionRestored(saved: List<AiJobState>, now: Long): Pair<List<AiJobState>, List<AiJobState>> {
            val latest = saved.groupBy { it.kind }.mapNotNull { (_, v) -> v.maxByOrNull { it.createdAt } }
            val kept = mutableListOf<AiJobState>()
            val overdue = mutableListOf<AiJobState>()
            for (s in latest) when (s.status) {
                AiJobStatus.Running ->
                    if (now < s.createdAt + DEADLINE_MS) kept += s else overdue += s
                AiJobStatus.Done, AiJobStatus.Failed ->
                    if (now < (s.finishedAt ?: s.createdAt) + RESULT_TTL_MS) kept += s
                AiJobStatus.Timeout -> Unit
            }
            return kept to overdue
        }

        /**
         * 重开时本地仍「进行中」但已过期限的任务，按一次 GET 的结果定状态：
         * - done / failed：用服务器结果；done 从现在起按 [RESULT_TTL_MS] 保留给用户确认。
         * - queued / running / expired、404、断网或其他错误：超时（断网时与原逻辑一致）。
         */
        internal fun resolveOverdue(s: AiJobState, remote: Result<AiJob>, now: Long): AiJobState {
            val job = remote.getOrNull()
            return if (job != null && (job.status == "done" || job.status == "failed")) applyRemote(s, job, now)
            else s.copy(status = AiJobStatus.Timeout, finishedAt = now)
        }

        internal fun applyRemote(s: AiJobState, job: AiJob, now: Long): AiJobState = when (job.status) {
            "done" -> s.copy(status = AiJobStatus.Done, output = job.output ?: JsonObject(emptyMap()), finishedAt = now)
            "failed" -> s.copy(status = AiJobStatus.Failed, error = job.error?.let { "识别失败：$it" } ?: "识别失败", finishedAt = now)
            "expired" -> s.copy(status = AiJobStatus.Timeout, finishedAt = now)
            else -> s
        }
    }
}
