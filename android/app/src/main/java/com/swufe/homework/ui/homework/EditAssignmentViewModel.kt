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
import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.AssignmentPatch
import com.swufe.homework.data.api.Attachment
import com.swufe.homework.data.api.Source
import com.swufe.homework.data.api.SourcePatch
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.ui.nav.Routes
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/**
 * 首发者编辑作业（WS11 第 2 条）：
 * - 标题、DDL、「DDL 待确认」→ PATCH /assignments/{id}（只下发改了的字段）；
 * - 说明 = 首发来源的正文和附件 → PATCH /sources/{id}（附件整体替换；不碰 stated_ddl，改 DDL 时服务器会同步首发来源的 stated_ddl）。
 */
@HiltViewModel
class EditAssignmentViewModel @Inject constructor(
    saved: SavedStateHandle,
    @ApplicationContext private val ctx: Context,
    private val repo: AssignmentRepository,
) : ViewModel() {
    val id = saved.toRoute<Routes.EditAssignment>().id

    var title by mutableStateOf("")
    var body by mutableStateOf("")
    var ddlDate by mutableStateOf<LocalDate?>(null); private set
    var ddlTime by mutableStateOf<LocalTime?>(null); private set
    var ddlUncertain by mutableStateOf(false)
    var existing by mutableStateOf<List<Attachment>>(emptyList()); private set
    var newImages by mutableStateOf<List<Uri>>(emptyList()); private set
    private val uploaded = mutableMapOf<Uri, Int>()

    var loading by mutableStateOf(true); private set
    var busy by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    /** 不是首发者（或已不是）：只显示说明，不给保存。 */
    var forbidden by mutableStateOf(false); private set

    /** 首发来源（且是我发的）；没有时只能改标题和 DDL。 */
    var firstSource by mutableStateOf<Source?>(null); private set
    private var original: Assignment? = null

    init { load() }

    fun load() {
        loading = true
        viewModelScope.launch {
            repo.detail(id).onSuccess { a ->
                original = a
                forbidden = !a.isFirstPoster
                title = a.title
                ddlUncertain = a.ddlUncertain
                Ddl.parse(a.ddl)?.let { ddlDate = it.toLocalDate(); ddlTime = it.toLocalTime() }
                val s = a.sources.firstOrNull { it.isFirst && it.isMine }
                firstSource = s
                body = s?.body.orEmpty()
                existing = s?.attachments.orEmpty()
                error = null
            }.onFailure { error = it.userMessage }
            loading = false
        }
    }

    val imageCount get() = newImages.size + existing.size

    fun addImages(uris: List<Uri>) {
        error = null
        val room = PublishViewModel.MAX_IMAGES - imageCount
        val ok = uris.filter { sizeOf(it) <= PublishViewModel.MAX_PICK_BYTES }
        if (ok.size < uris.size) error = "有图片超过 10 MB，已跳过"
        if (ok.size > room) error = "最多 ${PublishViewModel.MAX_IMAGES} 张图片"
        newImages = (newImages + ok.take(room.coerceAtLeast(0))).distinct()
    }

    fun removeNew(u: Uri) { newImages = newImages - u; uploaded.remove(u) }
    fun removeExisting(a: Attachment) { existing = existing - a }

    fun setDate(d: LocalDate) { ddlDate = d; if (ddlTime == null) ddlTime = LocalTime.of(23, 59) }
    fun setTime(t: LocalTime) { ddlTime = t }

    private fun ddlIso(): String? = ddlDate?.let { Ddl.toApi(Ddl.at(it, ddlTime ?: LocalTime.of(23, 59))) }

    private fun sizeOf(u: Uri): Long = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /** 只下发改了的字段；什么都没改返回 null。 */
    private fun assignmentPatch(o: Assignment): AssignmentPatch? {
        val t = title.trim()
        val ddl = ddlIso()
        val ddlChanged = ddl != null && Ddl.parse(ddl)?.toInstant() != Ddl.parse(o.ddl)?.toInstant()
        val p = AssignmentPatch(
            title = t.takeIf { it != o.title },
            ddl = ddl.takeIf { ddlChanged },
            // 未给 ddl_uncertain 时服务器把改 DDL 视为已确认；勾着「待确认」时要显式带上
            ddlUncertain = ddlUncertain.takeIf { it != o.ddlUncertain || (ddlChanged && it) },
        )
        return p.takeIf { it.title != null || it.ddl != null || it.ddlUncertain != null }
    }

    private fun sourceChanged(s: Source): Boolean =
        body.trim() != s.body || newImages.isNotEmpty() || existing.map { it.id } != s.attachments.map { it.id }

    fun save(onDone: () -> Unit) {
        if (busy != null || forbidden) return
        val o = original ?: return
        error = null
        if (title.isBlank()) { error = "请填写标题"; return }
        if (ddlIso() == null) { error = "请填写 DDL"; return }
        val s = firstSource
        if (s != null && body.isBlank() && imageCount == 0) { error = "说明和图片不能都为空"; return }
        val ap = assignmentPatch(o)
        val sc = s != null && sourceChanged(s)
        if (ap == null && !sc) { onDone(); return }
        viewModelScope.launch {
            try {
                if (ap != null) {
                    busy = "正在保存标题和 DDL…"
                    repo.patchAssignment(id, ap).onSuccess { original = it }.onFailure { error = it.userMessage; return@launch }
                }
                if (s != null && sc) {
                    val pending = newImages.filter { it !in uploaded }
                    if (pending.isNotEmpty()) {
                        busy = "正在上传图片…"
                        val r = repo.uploadImages(pending)
                        r.onFailure { error = it.userMessage; return@launch }
                        pending.zip(r.getOrThrow()).forEach { (u, att) -> uploaded[u] = att.id }
                    }
                    val ids = existing.map { it.id } + newImages.mapNotNull { uploaded[it] }
                    busy = "正在保存说明…"
                    repo.patchSource(s.id, SourcePatch(body = body.trim(), attachmentIds = ids))
                        .onFailure {
                            error = if (ap != null) "标题和 DDL 已保存，说明保存失败：${it.userMessage}" else it.userMessage
                            return@launch
                        }
                }
                onDone()
            } finally {
                busy = null
            }
        }
    }
}
