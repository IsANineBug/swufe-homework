package com.swufe.homework.ui.timetable

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.TimetableImportResult
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AiJobState
import com.swufe.homework.data.repo.AiJobStatus
import com.swufe.homework.data.repo.AiJobTracker
import com.swufe.homework.data.repo.TimetableRepository
import com.swufe.homework.util.SlotDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ImportStage { Pick, Recognizing, Confirm, Submitting, Result }

const val AI_OFFLINE_MESSAGE = "AI 暂时不可用，请手动添加"

/**
 * 导入流程：选 1–2 张截图 → 上传 → AI 识别（parse_timetable，交给 [AiJobTracker] 在后台跑，只出草稿）
 * → 确认页逐条改 → /timetable/import。手动添加直接从确认页开始，可搜索已有课一键填入。
 * 识别结果从 tracker 读（不依赖本 ViewModel 的内存）：用户中途离开、从状态条/通知回来时照样能进确认页。
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val repo: TimetableRepository,
    private val tracker: AiJobTracker,
) : ViewModel() {
    var stage by mutableStateOf(ImportStage.Pick); private set
    var images by mutableStateOf<List<Uri>>(emptyList()); private set
    var progress by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var aiOnline by mutableStateOf<Boolean?>(null); private set
    var result by mutableStateOf<TimetableImportResult?>(null); private set
    val drafts = mutableStateListOf<SlotDraft>()
    /** 上一次提交被判为冲突的草稿 key，确认页高亮。 */
    var conflictKeys by mutableStateOf<Set<Long>>(emptySet()); private set

    /** 已上传的截图（与 [images] 顺序一致），补截一张时只传新的那张。 */
    private var attachmentIds: List<Int> = emptyList()
    private var uploading = false
    private var observing = false
    /** 后台识图任务已建好（显示「已在后台识别，可以先去做别的」）。 */
    var jobStarted by mutableStateOf(false); private set
    /** 用户点了「留在这里等」：只显示进度。 */
    var stayHere by mutableStateOf(false); private set
    /** 本次识别结果里有截断的课名或被裁掉的起节：确认页顶部建议补截另一半。 */
    var aiCropped by mutableStateOf(false); private set
    /** 最多 2 张截图（与选图一致）。 */
    val canAddAnother: Boolean get() = images.size == 1

    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<ClassInfo>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    private var searchJob: Job? = null

    fun startManual() {
        if (stage == ImportStage.Pick && drafts.isEmpty()) stage = ImportStage.Confirm
    }

    /** 导入页（非手动）进入时：查 AI 状态，并接住 tracker 里进行中/已完成的课表识别。 */
    fun startImport() {
        checkAi()
        if (observing) return
        observing = true
        viewModelScope.launch {
            tracker.restore()
            tracker.jobs.collect { list -> onJob(list.firstOrNull { it.kind == AiJobTracker.PARSE_TIMETABLE }) }
        }
    }

    fun checkAi() {
        viewModelScope.launch { aiOnline = repo.aiOnline() }
    }

    fun setPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        images = uris.take(2)
        attachmentIds = emptyList()
        error = null
    }

    /** 「再加一张」：补截另一半，与已有的一张一起重新识别。 */
    fun addAnother(uris: List<Uri>) {
        if (uris.isEmpty() || !canAddAnother) return
        images = (images + uris).take(2)
        recognize()
    }

    private val backStage get() = if (drafts.isEmpty()) ImportStage.Pick else ImportStage.Confirm

    fun recognize() {
        if (images.isEmpty()) { error = "请先选择 1–2 张截图"; return }
        stage = ImportStage.Recognizing
        error = null
        jobStarted = false
        stayHere = false
        uploading = true
        progress = "正在上传截图…"
        viewModelScope.launch {
            try {
                if (!repo.aiOnline()) {
                    aiOnline = false
                    stage = backStage
                    error = AI_OFFLINE_MESSAGE
                    return@launch
                }
                if (attachmentIds.size < images.size) {
                    val more = repo.uploadScreenshots(images.drop(attachmentIds.size))
                        .getOrElse { error = it.userMessage; stage = backStage; return@launch }
                    attachmentIds = attachmentIds + more
                }
                progress = "正在识别…"
                val ctx = mapOf(
                    "attachment_ids" to attachmentIds.joinToString(","),
                    "image_uris" to images.joinToString("\n"),
                )
                tracker.start(AiJobTracker.PARSE_TIMETABLE, attachmentIds, ctx)
                    .onSuccess { jobStarted = true }
                    .onFailure { e ->
                        error = if ((e as? ApiException)?.code == "ai_offline") AI_OFFLINE_MESSAGE else e.userMessage
                        if ((e as? ApiException)?.code == "ai_offline") aiOnline = false
                        stage = backStage
                    }
            } finally {
                uploading = false
            }
        }
    }

    fun stay() { stayHere = true }

    /** 放弃这次识别。 */
    fun abandon() {
        viewModelScope.launch { tracker.job(AiJobTracker.PARSE_TIMETABLE)?.let { tracker.dismiss(it.jobId) } }
        stage = backStage
    }

    private suspend fun onJob(s: AiJobState?) {
        if (s == null) {
            if (stage == ImportStage.Recognizing && !uploading) stage = backStage
            return
        }
        val waiting = stage == ImportStage.Pick || stage == ImportStage.Recognizing
        when (s.status) {
            AiJobStatus.Running -> if (waiting) {
                restoreInputs(s)
                jobStarted = true
                progress = "正在识别…"
                stage = ImportStage.Recognizing
            }
            AiJobStatus.Done -> if (waiting) {
                tracker.take(s.jobId)
                restoreInputs(s)
                val got = tracker.timetableSlots(s).map { SlotDraft.fromAi(it) }
                drafts.clear(); drafts.addAll(got)
                conflictKeys = emptySet()
                aiCropped = got.any { it.needsReview }
                stage = ImportStage.Confirm
                error = if (got.isEmpty()) "没识别出课程，可以手动添加" else null
            }
            AiJobStatus.Failed, AiJobStatus.Timeout -> if (waiting) {
                tracker.take(s.jobId)
                restoreInputs(s)
                error = s.message
                stage = backStage
            }
        }
    }

    /** 从任务 context 恢复截图（ViewModel 被销毁后从状态条/通知回来时）。 */
    private fun restoreInputs(s: AiJobState) {
        if (attachmentIds.isNotEmpty()) return
        val ids = s.context["attachment_ids"].orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }
        val uris = s.context["image_uris"].orEmpty().split("\n").filter { it.isNotBlank() }.map { Uri.parse(it) }
        if (ids.isNotEmpty() && ids.size == uris.size) { attachmentIds = ids; images = uris }
    }

    fun update(d: SlotDraft) {
        val i = drafts.indexOfFirst { it.key == d.key }
        if (i >= 0) drafts[i] = d
    }

    fun remove(key: Long) { drafts.removeAll { it.key == key } }

    fun addEmpty() { drafts.add(SlotDraft()) }

    fun addFromClass(c: ClassInfo) {
        if (c.slots.isEmpty()) {
            drafts.add(SlotDraft(name = c.name, teacher = c.teacher))
        } else {
            c.slots.forEach { s ->
                drafts.add(SlotDraft(name = c.name, room = s.room, weekday = s.weekday, start = s.start, end = s.end, teacher = c.teacher))
            }
        }
        query = ""; searchResults = emptyList()
    }

    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { searchResults = emptyList(); return }
        searchJob = viewModelScope.launch {
            delay(300)
            searching = true
            repo.search(q).onSuccess { searchResults = it }.onFailure { error = it.userMessage }
            searching = false
        }
    }

    fun submit() {
        if (drafts.isEmpty()) { error = "至少添加一节课"; return }
        drafts.forEachIndexed { i, d -> d.validate()?.let { error = "第 ${i + 1} 条：$it"; return } }
        val snapshot = drafts.toList()
        stage = ImportStage.Submitting
        error = null
        viewModelScope.launch {
            repo.import(snapshot.map { it.toInput() })
                .onSuccess { r ->
                    result = r
                    // 冲突的时段不入库：留在确认页，让用户改名后重提
                    val conflictInputs = r.conflicts.map { it.slot }
                    val kept = snapshot.filter { d -> conflictInputs.any { c -> sameSlot(c, d) } }
                    drafts.clear(); drafts.addAll(kept)
                    conflictKeys = kept.map { it.key }.toSet()
                    stage = ImportStage.Result
                }
                .onFailure { error = it.userMessage; stage = ImportStage.Confirm }
        }
    }

    fun backToConfirm() { stage = ImportStage.Confirm }

    private fun sameSlot(c: com.swufe.homework.data.api.SlotInput, d: SlotDraft) =
        c.weekday == d.weekday && c.start == d.start && c.end == d.end && c.name.trim() == d.name.trim()
}
