package com.swufe.homework.ui.timetable

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.TimetableImportResult
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.TimetableRepository
import com.swufe.homework.util.SlotDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ImportStage { Pick, Recognizing, Confirm, Submitting, Result }

/**
 * 导入流程：选 1–2 张截图 → AI 识别（parse_timetable，只出草稿）→ 确认页逐条改 → /timetable/import。
 * 手动添加直接从确认页开始，可搜索已有课一键填入。
 */
@HiltViewModel
class ImportViewModel @Inject constructor(private val repo: TimetableRepository) : ViewModel() {
    var stage by mutableStateOf(ImportStage.Pick); private set
    var images by mutableStateOf<List<Uri>>(emptyList()); private set
    var progress by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var aiOnline by mutableStateOf<Boolean?>(null); private set
    var result by mutableStateOf<TimetableImportResult?>(null); private set
    val drafts = mutableStateListOf<SlotDraft>()
    /** 上一次提交被判为冲突的草稿 key，确认页高亮。 */
    var conflictKeys by mutableStateOf<Set<Long>>(emptySet()); private set

    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<ClassInfo>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    private var searchJob: Job? = null

    fun startManual() {
        if (stage == ImportStage.Pick && drafts.isEmpty()) stage = ImportStage.Confirm
    }

    fun checkAi() {
        viewModelScope.launch { aiOnline = repo.aiOnline() }
    }

    fun setPicked(uris: List<Uri>) {
        images = uris.take(2)
        error = null
    }

    fun recognize() {
        if (images.isEmpty()) { error = "请先选择 1–2 张截图"; return }
        stage = ImportStage.Recognizing
        error = null
        viewModelScope.launch {
            if (!repo.aiOnline()) {
                aiOnline = false
                stage = ImportStage.Pick
                error = "电脑未在线，请手动添加"
                return@launch
            }
            repo.recognize(images) { progress = it }
                .onSuccess {
                    drafts.clear(); drafts.addAll(it)
                    stage = ImportStage.Confirm
                    if (it.isEmpty()) error = "没识别出课程，可以手动添加"
                }
                .onFailure { error = it.userMessage; stage = ImportStage.Pick }
        }
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
