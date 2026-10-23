package com.swufe.homework.ui.homework

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.MergeProposal
import com.swufe.homework.data.api.Source
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: AssignmentRepository,
    private val account: AccountRepository,
    private val prefs: HomeworkPrefs,
) : ViewModel() {
    val id = saved.toRoute<Routes.AssignmentDetail>().id

    // 注意：必须写在 init { load() } 之前（Kotlin 按书写顺序初始化，load 在 Main.immediate 上同步读 curId）。
    /** 当前作业 id：请求的作业已被合并时，接口返回合并目标（id 不同），之后一律以目标 id 为准。 */
    var curId: Int = id
        private set
    val me = account.me

    var a by mutableStateOf<Assignment?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null)
    var gone by mutableStateOf(false); private set

    /** 本机提议过或赞同过的合并提议 id（提议人算第 1 票，这些提议的「赞同」按钮禁用）。 */
    val votedMerges = prefs.votedMerges.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 提议合并时可选的同班作业。 */
    var mergeTargets by mutableStateOf<List<AssignmentSummary>?>(null); private set

    init {
        load()
        if (account.me.value == null) viewModelScope.launch { account.refreshMe() }
    }

    private var resumed = false
    /** 从补充/编辑页返回时刷新。 */
    fun onResume() { if (resumed) load() else resumed = true }

    fun load() {
        loading = true
        viewModelScope.launch {
            repo.detail(curId)
                .onSuccess { curId = it.id; a = it; error = null; gone = false }
                .onFailure {
                    error = it.userMessage
                    if ((it as? com.swufe.homework.data.api.ApiException)?.http == 404) gone = true
                }
            loading = false
        }
    }

    /** 按服务器给的 Source.is_mine（用户 id）判断，不再比昵称（issue #8：改昵称后会短时误判）。 */
    fun isMine(s: Source): Boolean = s.isMine
    /** 按服务器给的 Assignment.is_first_poster（用户 id）判断，决定「编辑」与「我是发布者，同意」。 */
    val iAmFirstPoster: Boolean get() = a?.isFirstPoster == true

    fun voteState(p: MergeProposal): MergeVoteState =
        mergeVoteState(p, votedMerges.value, viewingFrom = p.fromId == curId, iAmFirstPoster = iAmFirstPoster)

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
            repo.proposeMerge(curId, toId).onSuccess { p ->
                prefs.markMergeVoted(p.id) // 提议人算第 1 票，之后不能再赞同
                toast = if (p.status == "applied") "已合并" else "已提议合并：首发者同意或 3 位同学赞同后执行"
                load()
            }.onFailure { toast = it.userMessage }
        }
    }

    fun voteMerge(pid: Int) {
        viewModelScope.launch {
            repo.voteMerge(pid).onSuccess { p ->
                prefs.markMergeVoted(pid)
                toast = if (p.status == "applied") "已合并" else "已赞同（${p.votes}/${p.neededVotes}）"
                load()
            }.onFailure {
                if ((it as? ApiException)?.code == "already_voted") {
                    // 例如在别的设备上提议/赞同过：记下来，按钮随之禁用
                    prefs.markMergeVoted(pid)
                    toast = "你已经赞同过这条提议"
                } else toast = it.userMessage
            }
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

/** 合并提议卡片上的操作按钮状态。 */
enum class MergeVoteState {
    /** 我是被合并那条（from）的首发者：「我是发布者，同意」（不看票数）。 */
    CanAccept,
    /** 可以「赞同」。 */
    CanVote,
    /** 我提议的或已赞同过：「赞同」禁用（issue #8，避免 409 already_voted）。 */
    Voted,
}

/**
 * 纯函数，便于单测。[votedLocally] = 本机提议过或赞同过的提议 id（api.yaml 的 MergeProposal 没有对应字段）；
 * [viewingFrom] = 当前详情页是提议里的 from 作业。
 */
fun mergeVoteState(p: MergeProposal, votedLocally: Set<Int>, viewingFrom: Boolean, iAmFirstPoster: Boolean): MergeVoteState = when {
    viewingFrom && iAmFirstPoster -> MergeVoteState.CanAccept
    p.id in votedLocally -> MergeVoteState.Voted
    else -> MergeVoteState.CanVote
}

/** 详情页是否显示「编辑」：只看服务器的 is_first_poster（按用户 id），作业已删除/合并走时不显示。 */
fun canEditAssignment(a: Assignment?, gone: Boolean): Boolean = a != null && a.isFirstPoster && !gone
