package com.swufe.homework.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.ApiException
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.ChatRepository
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 举报对象（api.yaml POST /reports 的 target_kind）。 */
enum class ReportKind(val api: String, val label: String) {
    Source("source", "这条内容"),
    Message("message", "这条消息"),
    Class("class", "这个班"),
    User("user", "这位同学"),
}

/** [preview] 只用于弹窗里提示举报的是什么（如消息前几十个字），不上传。 */
data class ReportTarget(val kind: ReportKind, val id: Long, val preview: String? = null)

/**
 * 「举报这个人」（用户昵称处）：target_kind=user、target_id=作者 id。
 * 作者 id 缺失（服务器还没带 author_id）或是自己时返回 null，界面不显示入口。
 */
fun userReportTarget(authorId: Int?, nickname: String, isMine: Boolean): ReportTarget? =
    if (authorId == null || authorId <= 0 || isMine) null else ReportTarget(ReportKind.User, authorId.toLong(), nickname.ifBlank { null })

const val REPORT_USER_LABEL = "举报这个人"

const val REPORT_REASON_MAX = 200
const val REPORT_DONE_TEXT = "已举报，管理员会尽快处理。举报人只有管理员看得到。"
const val ALREADY_REPORTED_TEXT = "你已经举报过，管理员处理中"

fun reportReasonValid(reason: String): Boolean = chatLength(reason) in 1..REPORT_REASON_MAX

/** 举报结果：Done = 关闭表单、显示结果；Retry = 留在表单显示错误（网络、每日上限等）。 */
sealed interface ReportOutcome {
    data class Done(val message: String) : ReportOutcome
    data class Retry(val message: String) : ReportOutcome
}

/** 纯函数，便于单测：409 already_reported 视为「已经举报过」，不算失败。 */
fun reportOutcome(result: Result<Unit>): ReportOutcome = result.fold(
    onSuccess = { ReportOutcome.Done(REPORT_DONE_TEXT) },
    onFailure = { e ->
        if ((e as? ApiException)?.code == "already_reported") ReportOutcome.Done(ALREADY_REPORTED_TEXT)
        else ReportOutcome.Retry(e.userMessage)
    },
)

@HiltViewModel
class ReportViewModel @Inject constructor(private val repo: ChatRepository) : ViewModel() {
    var submitting by mutableStateOf(false); private set
    var outcome by mutableStateOf<ReportOutcome?>(null); private set

    fun submit(t: ReportTarget, reason: String) {
        if (submitting || !reportReasonValid(reason)) return
        submitting = true
        outcome = null
        viewModelScope.launch {
            outcome = reportOutcome(repo.report(t.kind.api, t.id, reason.trim()))
            submitting = false
        }
    }

    fun reset() { outcome = null }
}

/**
 * 统一的举报弹窗（作业来源、频道消息、班级、用户）：填理由 1–200 字 → POST /reports。
 * 结果在弹窗里展示，调用方不需要自己的 Snackbar。
 */
@Composable
fun ReportDialog(target: ReportTarget, onDismiss: () -> Unit) {
    val vm: ReportViewModel = hiltViewModel(key = "report-${target.kind.api}-${target.id}")
    var reason by rememberSaveable(target) { mutableStateOf("") }
    val close = { vm.reset(); onDismiss() }
    val done = vm.outcome as? ReportOutcome.Done
    if (done != null) {
        AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
            title = { Text("举报${target.kind.label}") },
            text = { Text(done.message) },
            confirmButton = { TextButton(onClick = close) { Text("知道了") } },
        )
        return
    }
    val len = chatLength(reason)
    AlertDialog(
        onDismissRequest = { if (!vm.submitting) close() },
        icon = { Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("举报${target.kind.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                target.preview?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "「$it」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("举报会交给管理员处理，被举报的人看不到是谁举报的。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("理由（必填）") },
                    placeholder = { Text("如：广告、辱骂、虚假作业信息") },
                    minLines = 2,
                    maxLines = 5,
                    isError = len > REPORT_REASON_MAX,
                    supportingText = { Text("$len/$REPORT_REASON_MAX") },
                    modifier = Modifier.fillMaxWidth(),
                )
                (vm.outcome as? ReportOutcome.Retry)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.submit(target, reason) },
                enabled = reportReasonValid(reason) && !vm.submitting,
                shape = Corners.Button,
            ) {
                if (vm.submitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("提交举报")
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !vm.submitting) { Text("取消") } },
    )
}

/**
 * 班级页顶栏的「更多」菜单：举报这个班。放在 WS10 的 ClassPageScreen 的 actions 槽里。
 */
@Composable
fun ClassReportMenu(classId: Int) {
    var menu by remember { mutableStateOf(false) }
    var reporting by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("举报这个班") },
                leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                onClick = { menu = false; reporting = true },
            )
        }
    }
    if (reporting) ReportDialog(ReportTarget(ReportKind.Class, classId.toLong())) { reporting = false }
}
