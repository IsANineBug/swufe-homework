package com.swufe.homework.ui.homework

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swufe.homework.data.api.AiAssignmentDraft
import com.swufe.homework.data.api.AssignmentCreate
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.Attachment
import com.swufe.homework.data.api.SourceCreate
import com.swufe.homework.data.api.SourcePatch
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.ui.nav.Routes
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

enum class PublishMode { Create, Supplement, Edit }

/** 发布前查重的一条候选；aiSame = AI 语义复核认为是同一份。 */
data class Candidate(val a: AssignmentSummary, val aiSame: Boolean, val reason: String)

sealed interface PublishPrompt {
    data class Spam(val reason: String, val answer: CompletableDeferred<Boolean>) : PublishPrompt
    /** answer：选中的候选作业 id（作为补充），或 null = 仍然新建。 */
    data class Duplicates(val list: List<Candidate>, val answer: CompletableDeferred<Int?>) : PublishPrompt
}

@HiltViewModel
class PublishViewModel @Inject constructor(
    saved: SavedStateHandle,
    @ApplicationContext private val ctx: Context,
    private val repo: AssignmentRepository,
) : ViewModel() {
    private val route = saved.toRoute<Routes.Publish>()
    val classId = route.classId
    val mode = when {
        route.sourceId >= 0 -> PublishMode.Edit
        route.assignmentId >= 0 -> PublishMode.Supplement
        else -> PublishMode.Create
    }

    var title by mutableStateOf("")
    var body by mutableStateOf("")
    var ddlDate by mutableStateOf<LocalDate?>(null)
    var ddlTime by mutableStateOf<LocalTime?>(null)
    var ddlUncertain by mutableStateOf(false)
    /** AI 标记拿不准的字段（title / requirements / ddl / submit_method），表单里标黄。 */
    var uncertain by mutableStateOf<Set<String>>(emptySet()); private set

    var newImages by mutableStateOf<List<Uri>>(emptyList()); private set
    var existing by mutableStateOf<List<Attachment>>(emptyList()); private set
    private val uploaded = mutableMapOf<Uri, Int>()

    var aiOnline by mutableStateOf(false); private set
    var aiJobId: Int? = null; private set
    var busy by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var prompt by mutableStateOf<PublishPrompt?>(null); private set
    var loadingExisting by mutableStateOf(mode == PublishMode.Edit); private set

    init {
        viewModelScope.launch { aiOnline = repo.aiOnline() }
        if (mode == PublishMode.Edit) loadSource()
    }

    private fun loadSource() = viewModelScope.launch {
        repo.detail(route.assignmentId).onSuccess { a ->
            val s = a.sources.firstOrNull { it.id == route.sourceId }
            if (s == null) error = "这条内容已不存在" else {
                body = s.body
                existing = s.attachments
                Ddl.parse(s.statedDdl)?.let { ddlDate = it.toLocalDate(); ddlTime = it.toLocalTime() }
            }
        }.onFailure { error = it.userMessage }
        loadingExisting = false
    }

    val imageCount get() = newImages.size + existing.size

    fun addImages(uris: List<Uri>) {
        error = null
        val room = MAX_IMAGES - imageCount
        val ok = uris.filter { sizeOf(it) <= MAX_PICK_BYTES }
        if (ok.size < uris.size) error = "有图片超过 10 MB，已跳过"
        if (ok.size > room) error = "最多 $MAX_IMAGES 张图片"
        newImages = (newImages + ok.take(room.coerceAtLeast(0))).distinct()
    }

    fun removeNew(u: Uri) { newImages = newImages - u; uploaded.remove(u) }
    fun removeExisting(a: Attachment) { existing = existing - a }

    fun clearDdl() { ddlDate = null; ddlTime = null; ddlUncertain = false; uncertain = uncertain - "ddl" }

    fun setDate(d: LocalDate) { ddlDate = d; if (ddlTime == null) ddlTime = LocalTime.of(23, 59); uncertain = uncertain - "ddl" }
    fun setTime(t: LocalTime) { ddlTime = t; uncertain = uncertain - "ddl" }

    private fun ddlIso(): String? {
        val d = ddlDate ?: return null
        return Ddl.toApi(Ddl.at(d, ddlTime ?: LocalTime.of(23, 59)))
    }

    private fun sizeOf(u: Uri): Long = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 压缩并上传还没传过的图片，返回全部 attachment_ids（已有的在前）。 */
    private suspend fun uploadAll(): List<Int>? {
        val pending = newImages.filter { it !in uploaded }
        if (pending.isNotEmpty()) {
            busy = "正在上传图片…"
            val r = repo.uploadImages(pending)
            r.onFailure { error = it.userMessage; return null }
            pending.zip(r.getOrThrow()).forEach { (u, a) -> uploaded[u] = a.id }
        }
        return existing.map { it.id } + newImages.mapNotNull { uploaded[it] }
    }

    // ---- 智能识别 ----
    fun aiRecognize() {
        if (!aiOnline || busy != null) return
        if (body.isBlank() && newImages.isEmpty() && existing.isEmpty()) { error = "先粘贴作业文字或选几张图片"; return }
        error = null
        viewModelScope.launch {
            val ids = uploadAll() ?: run { busy = null; return@launch }
            busy = "电脑正在识别…"
            repo.aiParse(classId, body, ids)
                .onSuccess { (jobId, d) -> aiJobId = jobId; applyDraft(d) }
                .onFailure { error = it.userMessage; aiOnline = repo.aiOnline() }
            busy = null
        }
    }

    private fun applyDraft(d: AiAssignmentDraft) {
        if (mode == PublishMode.Create) d.title?.takeIf { it.isNotBlank() }?.let { title = it.take(80) }
        val parts = listOfNotNull(
            d.requirements?.takeIf { it.isNotBlank() },
            d.submitMethod?.takeIf { it.isNotBlank() }?.let { "提交方式：$it" },
        )
        if (parts.isNotEmpty()) {
            val draftText = parts.joinToString("\n")
            body = if (body.isBlank()) draftText else "$draftText\n\n原文：\n$body"
        }
        Ddl.parse(d.ddl)?.let { ddlDate = it.toLocalDate(); ddlTime = it.toLocalTime() }
        uncertain = d.uncertain.toSet()
        if ("ddl" in uncertain || (d.ddl == null && mode == PublishMode.Create)) ddlUncertain = true
    }

    // ---- 发布 ----
    fun publish(onDone: (assignmentId: Int) -> Unit) {
        if (busy != null) return
        error = null
        val t = title.trim()
        val hasImages = imageCount > 0
        val ddl = ddlIso()
        when (mode) {
            PublishMode.Create -> {
                if (t.isEmpty()) { error = "请填写标题"; return }
                if (ddl == null) { error = if (hasImages) "有图片的作业必须填写 DDL" else "请填写 DDL"; return }
            }
            PublishMode.Supplement, PublishMode.Edit -> {
                if (body.isBlank() && !hasImages && ddl == null) { error = "请填写补充内容或选择图片"; return }
            }
        }
        viewModelScope.launch {
            try {
                val ids = uploadAll() ?: return@launch
                when (mode) {
                    PublishMode.Create -> create(t, ddl!!, ids, onDone)
                    PublishMode.Supplement -> {
                        busy = "正在发布…"
                        repo.addSource(route.assignmentId, SourceCreate(body.trim(), ddl, ids))
                            .onSuccess { onDone(route.assignmentId) }.onFailure { error = it.userMessage }
                    }
                    PublishMode.Edit -> {
                        busy = "正在保存…"
                        repo.patchSource(route.sourceId, SourcePatch.full(body.trim(), ddl, ids))
                            .onSuccess { onDone(route.assignmentId) }.onFailure { error = it.userMessage }
                    }
                }
            } finally {
                busy = null
            }
        }
    }

    private suspend fun create(t: String, ddl: String, ids: List<Int>, onDone: (Int) -> Unit) {
        // 1. 垃圾预检（可选，只提示本人；离线跳过）
        if (aiOnline) {
            busy = "正在检查内容…"
            repo.aiSpam(classId, t, body, ids)?.takeIf { it.suspicious }?.let { s ->
                val ans = CompletableDeferred<Boolean>()
                prompt = PublishPrompt.Spam(s.reason, ans)
                val go = ans.await(); prompt = null
                if (!go) return
            }
        }
        // 2. 查重：服务器文字匹配，在线时 AI 语义复核
        busy = "正在查找重复…"
        val cands = repo.candidates(classId, t, ddl).getOrElse { emptyList() }
        if (cands.isNotEmpty()) {
            val review = if (aiOnline) repo.aiDedupe(classId, t, body, ddl, cands) else null
            val byId = review?.matches.orEmpty().associateBy { it.id }
            val list = cands.map { Candidate(it, byId[it.id]?.same == true, byId[it.id]?.reason.orEmpty()) }
                .sortedByDescending { it.aiSame }
            val ans = CompletableDeferred<Int?>()
            prompt = PublishPrompt.Duplicates(list, ans)
            val chosen = ans.await(); prompt = null
            if (chosen == CANCELLED) return
            if (chosen != null) {
                busy = "正在作为补充发布…"
                val text = body.trim().ifEmpty { t }
                repo.addSource(chosen, SourceCreate(text, ddl, ids)).onSuccess { onDone(chosen) }.onFailure { error = it.userMessage }
                return
            }
        }
        busy = "正在发布…"
        repo.create(classId, AssignmentCreate(t, ddl, ddlUncertain, body.trim(), ids, aiJobId))
            .onSuccess { onDone(it.id) }.onFailure { error = it.userMessage }
    }

    fun answerSpam(go: Boolean) { (prompt as? PublishPrompt.Spam)?.answer?.complete(go) }
    fun answerDuplicate(id: Int?) { (prompt as? PublishPrompt.Duplicates)?.answer?.complete(id) }
    fun cancelDuplicate() { (prompt as? PublishPrompt.Duplicates)?.answer?.complete(CANCELLED) }

    companion object {
        const val MAX_IMAGES = 6
        const val MAX_PICK_BYTES = 10L * 1024 * 1024
        private const val CANCELLED = -1
    }
}
