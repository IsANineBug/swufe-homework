package com.swufe.homework.ui.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swufe.homework.data.api.AdminAction
import com.swufe.homework.data.api.AdminClass
import com.swufe.homework.data.api.AdminDeletedSource
import com.swufe.homework.data.api.AdminReport
import com.swufe.homework.data.api.AdminUser
import com.swufe.homework.data.api.ClassMergeRecord
import com.swufe.homework.data.api.Me
import com.swufe.homework.data.api.SanctionCreate
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.ui.nav.Routes
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 管理路由的门：进入时重新拉 /me，非管理员看不到任何管理内容（[adminAccess]）。 */
@HiltViewModel
class AdminGateViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    private val cached: Me? = account.me.value
    private var fresh by mutableStateOf<Me?>(null)
    private var failed by mutableStateOf(false)
    val access: AdminAccess get() = adminAccess(cached, fresh, failed)

    init {
        viewModelScope.launch { account.refreshMe().onSuccess { fresh = it }.onFailure { failed = true } }
    }
}

/** 各管理页共用：一次只做一个写操作，结果用 toast 提示。 */
abstract class AdminBaseViewModel : ViewModel() {
    var busy by mutableStateOf(false); protected set
    var toast by mutableStateOf<String?>(null)

    /** 跑一个写操作；成功后提示 [done] 并执行 [after]（通常是重新加载）。 */
    protected fun act(done: String, block: suspend () -> Result<*>, after: () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            block().onSuccess { toast = done; after() }.onFailure { toast = it.userMessage }
            busy = false
        }
    }
}

// ---------------- 举报 ----------------

@HiltViewModel
class AdminReportsViewModel @Inject constructor(private val repo: AdminRepository) : AdminBaseViewModel() {
    var handled by mutableStateOf(false); private set
    var items by mutableStateOf<List<AdminReport>?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set

    init { load() }

    fun showHandled(v: Boolean) { if (handled != v) { handled = v; items = null; load() } }

    fun load() {
        loading = true; error = null
        viewModelScope.launch {
            repo.reports(if (handled) "handled" else "open").onSuccess { items = it }.onFailure { error = it.userMessage }
            loading = false
        }
    }

    /** 处置成功后自动 resolve；resolve 失败时说清楚处置已生效。 */
    private fun thenResolve(r: AdminReport, action: ReportAction, done: String, block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            block().onSuccess {
                repo.resolve(r.id, resolutionFor(action))
                    .onSuccess { toast = done }
                    .onFailure { toast = "$done，但标记举报为已处理失败：${it.userMessage}" }
                load()
            }.onFailure { toast = it.userMessage }
            busy = false
        }
    }

    fun deleteContent(r: AdminReport, reason: String, penalize: Boolean) {
        if (!adminReasonValid(reason)) return
        when (r.targetKind) {
            "source" -> thenResolve(r, ReportAction.DeleteContent, "已删除这条作业内容") { repo.deleteSource(r.targetId, reason, penalize) }
            "message" -> thenResolve(r, ReportAction.DeleteContent, "已删除这条消息") { repo.deleteMessage(r.targetId, reason) }
        }
    }

    fun hideClass(r: AdminReport, classId: Int, reason: String) {
        if (!adminReasonValid(reason)) return
        thenResolve(r, ReportAction.HideClass, "已隐藏班级") { repo.hideClass(classId, reason) }
    }

    fun punish(r: AdminReport, userId: Int, req: SanctionCreate) =
        thenResolve(r, ReportAction.Punish, "已处罚") { repo.sanction(userId, req) }

    fun dismiss(r: AdminReport) = thenResolve(r, ReportAction.Dismiss, "已驳回") { Result.success(Unit) }

    /** 按昵称找被举报内容的作者（昵称全站唯一，取完全一致的那个）。 */
    suspend fun findUser(nickname: String): Result<AdminUser?> = repo.users(nickname).map { exactUser(it, nickname) }

    /** 按班名找被举报内容所在的班（班名可能重复，交给管理员选）。 */
    suspend fun findClasses(name: String): Result<List<AdminClass>> =
        repo.classes(name, suspicious = false, includeHidden = false).map { l -> l.filter { it.name == name }.ifEmpty { l } }
}

// ---------------- 班级 ----------------

@HiltViewModel
class AdminClassesViewModel @Inject constructor(private val repo: AdminRepository) : AdminBaseViewModel() {
    var query by mutableStateOf("")
    var suspicious by mutableStateOf(false); private set
    var includeHidden by mutableStateOf(false); private set
    var page by mutableIntStateOf(1); private set
    var items by mutableStateOf<List<AdminClass>?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var merges by mutableStateOf<List<ClassMergeRecord>?>(null); private set
    var mergesError by mutableStateOf<String?>(null); private set

    init { search() }

    fun toggleSuspicious() { suspicious = !suspicious; search() }
    fun toggleHidden() { includeHidden = !includeHidden; search() }

    fun search(p: Int = 1) {
        loading = true; error = null; page = p
        viewModelScope.launch {
            repo.classes(query, suspicious, includeHidden, p).onSuccess { items = it }.onFailure { error = it.userMessage }
            loading = false
        }
    }

    fun loadMerges() {
        mergesError = null
        viewModelScope.launch { repo.classMerges().onSuccess { merges = it }.onFailure { mergesError = it.userMessage } }
    }

    fun hide(c: AdminClass, reason: String) {
        if (adminReasonValid(reason)) act("已隐藏「${c.name}」", { repo.hideClass(c.id, reason) }) { search(page) }
    }
    fun unhide(c: AdminClass) = act("已恢复「${c.name}」", { repo.unhideClass(c.id) }) { search(page) }
    fun rename(c: AdminClass, name: String, teacher: String?, reason: String) {
        if (name.isNotBlank() && adminReasonValid(reason)) act("已修改", { repo.patchClass(c.id, name, teacher, reason) }) { search(page) }
    }
    fun merge(from: AdminClass, into: AdminClass, reason: String) {
        if (from.id != into.id && adminReasonValid(reason)) {
            act("已把「${from.name}」合并进「${into.name}」，可在合并记录里撤销", { repo.mergeClasses(from.id, into.id, reason) }) {
                search(page); loadMerges()
            }
        }
    }
    /** 合并时搜目标班（同学期、未隐藏由服务器校验，409 merge_stale）。 */
    suspend fun searchForMerge(q: String): Result<List<AdminClass>> = repo.classes(q, suspicious = false, includeHidden = false)

    fun revert(m: ClassMergeRecord) = act("已撤销合并", { repo.revertClassMerge(m.id) }) { loadMerges(); search(page) }
}

// ---------------- 用户 ----------------

@HiltViewModel
class AdminUsersViewModel @Inject constructor(private val repo: AdminRepository) : AdminBaseViewModel() {
    var query by mutableStateOf("")
    var items by mutableStateOf<List<AdminUser>?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true; error = null
        viewModelScope.launch {
            repo.users(q).onSuccess { items = it }.onFailure { error = it.userMessage }
            loading = false
        }
    }
}

@HiltViewModel
class AdminUserViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: AdminRepository,
) : AdminBaseViewModel() {
    val id = saved.toRoute<Routes.AdminUserDetail>().id
    var user by mutableStateOf<AdminUser?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    init { load() }

    fun load() {
        error = null
        viewModelScope.launch { repo.user(id).onSuccess { user = it }.onFailure { error = it.userMessage } }
    }

    fun sanction(req: SanctionCreate) = act("已${sanctionLabel(req.kind)}", { repo.sanction(id, req) }) { load() }
    fun lift(sanctionId: Long) = act("已解除", { repo.lift(sanctionId) }) { load() }
    fun resetNickname(nickname: String, reason: String) {
        if (nickname.isNotBlank() && adminReasonValid(reason)) act("已改昵称", { repo.resetNickname(id, nickname, reason) }) { load() }
    }
    fun adjustPoints(classId: Int?, delta: Int?, reason: String) {
        if (!pointsAdjustValid(classId, delta, reason)) return
        val sign = if (delta!! > 0) "+$delta" else "$delta"
        act("已调整贡献值 $sign", { repo.adjustPoints(id, classId!!, delta, reason) }) { load() }
    }

    /** 调贡献值时选班：按班名搜（管理员看不到这个人加入了哪些班，只能自己搜）。 */
    suspend fun searchClasses(q: String): Result<List<AdminClass>> = repo.classes(q, suspicious = false, includeHidden = false)
}

// ---------------- 已删除内容 ----------------

@HiltViewModel
class AdminDeletedViewModel @Inject constructor(private val repo: AdminRepository) : AdminBaseViewModel() {
    var items by mutableStateOf<List<AdminDeletedSource>?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    init { load() }

    fun load() {
        error = null
        viewModelScope.launch { repo.deleted().onSuccess { items = it }.onFailure { error = it.userMessage } }
    }

    fun restore(s: AdminDeletedSource, liftBan: Boolean) = act("已恢复，贡献值已返还", { repo.restore(s.id, liftBan) }) { load() }
}

// ---------------- 公告 ----------------

@HiltViewModel
class AdminAnnounceViewModel @Inject constructor(private val repo: AdminRepository) : AdminBaseViewModel() {
    var title by mutableStateOf("")
    var body by mutableStateOf("")
    var days by mutableLongStateOf(ANNOUNCE_MIN_DAYS)
    var published by mutableStateOf(false); private set

    val canPublish: Boolean get() = title.isNotBlank() && body.isNotBlank() && days >= ANNOUNCE_MIN_DAYS

    fun publish() {
        if (!canPublish) return
        val at = Ddl.toApi(announcementEffectiveAt(Ddl.now(), days))
        act("公告已发布，所有人会收到通知", { repo.announce(title, body, at) }) { published = true; title = ""; body = "" }
    }
}

// ---------------- 操作日志 ----------------

@HiltViewModel
class AdminActionsViewModel @Inject constructor(private val repo: AdminRepository) : ViewModel() {
    var items by mutableStateOf<List<AdminAction>?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    init { load() }

    fun load() {
        error = null
        viewModelScope.launch { repo.actions().onSuccess { items = it }.onFailure { error = it.userMessage } }
    }
}
