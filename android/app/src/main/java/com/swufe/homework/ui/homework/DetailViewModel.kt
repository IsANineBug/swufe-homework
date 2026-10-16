package com.swufe.homework.ui.homework

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.Source
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: AssignmentRepository,
    private val account: AccountRepository,
) : ViewModel() {
    val id = saved.toRoute<Routes.AssignmentDetail>().id
    val me = account.me

    var a by mutableStateOf<Assignment?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null)
    var gone by mutableStateOf(false); private set

    /** 提议合并时可选的同班作业。 */
    var mergeTargets by mutableStateOf<List<AssignmentSummary>?>(null); private set

    init {
        load()
        if (account.me.value == null) viewModelScope.launch { account.refreshMe() }
    }

    private var resumed = false
    /** 从补充/编辑页返回时刷新。 */
    fun onResume() { if (resumed) load() else resumed = true }

    /** 当前作业 id：请求的作业已被合并时，接口返回合并目标（id 不同），之后一律以目标 id 为准。 */
    var curId: Int = id
        private set

    fun load() {
        loading = true
        viewModelScope.launch {
            repo.detail(curId)
                .onSuccess { curId = it.id; a = it; error = null }
                .onFailure {
                    error = it.userMessage
                    if ((it as? com.swufe.homework.data.api.ApiException)?.http == 404) gone = true
                }
            loading = false
        }
    }

    fun isMine(s: Source): Boolean = me.value?.nickname?.let { it == s.authorNickname } == true
    val iAmFirstPoster: Boolean get() = me.value?.nickname?.let { it == a?.firstPosterNickname } == true

    fun toggleDone() {
        val cur = a ?: return
        val done = !cur.done
        a = cur.copy(done = done)
        viewModelScope.launch {
            repo.setDone(cur.id, done)
                .onSuccess { toast = if (done) "已标记完成，不再提醒" else "已取消完成，提醒已恢复" }
                .onFailure { a = cur; error = it.userMessage }
        }
    }

    fun thank(s: Source) {
        viewModelScope.launch {
            repo.thank(s.id).onSuccess { patchSource(s.id) { it.copy(thankedByMe = true, thanksCount = it.thanksCount + 1) } }
                .onFailure { toast = it.userMessage }
        }
    }

    fun voteDelete(s: Source) {
        viewModelScope.launch {
            repo.voteDelete(s.id).onSuccess { r ->
                if (r.deleted) { toast = "已达到人数，内容已删除"; load() }
                else { toast = "已投票（${r.votes}/${r.threshold}）"; patchSource(s.id) { it.copy(votedDeleteByMe = true) } }
            }.onFailure { toast = it.userMessage }
        }
    }

    fun deleteMine(s: Source, onAssignmentGone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteSource(s.id).onSuccess {
                toast = "已删除"
                val left = a?.sources.orEmpty().count { it.id != s.id }
                if (left == 0) onAssignmentGone() else load()
            }.onFailure { toast = it.userMessage }
        }
    }

    fun loadMergeTargets() {
        val cur = a ?: return
        mergeTargets = null
        viewModelScope.launch {
            repo.classAssignments(cur.classId, null, null)
                .onSuccess { l -> mergeTargets = l.filter { it.id != cur.id && it.id !in cur.mergedFrom } }
                .onFailure { toast = it.userMessage; mergeTargets = emptyList() }
        }
    }

    fun propose(toId: Int) {
        viewModelScope.launch {
            repo.proposeMerge(curId, toId).onSuccess { toast = "已提议合并：首发者同意或 3 位同学赞同后执行"; load() }
                .onFailure { toast = it.userMessage }
        }
    }

    fun voteMerge(pid: Int) {
        viewModelScope.launch {
            repo.voteMerge(pid).onSuccess { p ->
                toast = if (p.status == "applied") "已合并" else "已赞同（${p.votes}/${p.neededVotes}）"
                load()
            }.onFailure { toast = it.userMessage }
        }
    }

    fun acceptMerge(pid: Int) {
        viewModelScope.launch {
            repo.acceptMerge(pid).onSuccess { toast = "已同意，作业已合并"; load() }.onFailure { toast = it.userMessage }
        }
    }

    private fun patchSource(sid: Int, f: (Source) -> Source) {
        val cur = a ?: return
        a = cur.copy(sources = cur.sources.map { if (it.id == sid) f(it) else it })
    }
}
