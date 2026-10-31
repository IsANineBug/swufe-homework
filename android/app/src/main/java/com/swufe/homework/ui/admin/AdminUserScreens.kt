package com.swufe.homework.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.swufe.homework.data.api.AdminClass
import com.swufe.homework.data.api.AdminUser
import com.swufe.homework.data.api.Sanction
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.common.SectionHeader
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic
import com.swufe.homework.ui.homework.MetaTag
import com.swufe.homework.ui.homework.MetaTags
import com.swufe.homework.ui.homework.Tag
import com.swufe.homework.util.Ddl
import kotlinx.coroutines.launch

// ======================= 用户列表 =======================

@Composable
internal fun UsersTab(snack: SnackbarHostState, onOpenUser: (Int) -> Unit) {
    val vm: AdminUsersViewModel = hiltViewModel()
    AdminToast(vm, snack)
    Column(Modifier.fillMaxSize()) {
        SearchRow(vm.query, { vm.query = it.take(20) }, "按昵称搜索", onSearch = vm::search)
        val items = vm.items
        when {
            vm.loading && items == null -> LoadingState(skeleton = true)
            items == null && vm.error != null -> ErrorState(vm.error ?: "", onRetry = vm::search)
            items == null -> EmptyState("输入昵称搜索用户", icon = Icons.Outlined.PersonSearch)
            items.isEmpty() -> EmptyState("没有找到「${vm.query.trim()}」", icon = Icons.Outlined.PersonSearch)
            else -> LazyColumn(contentPadding = PaddingValues(vertical = Spacing.sm)) {
                items(items, key = { it.id }) { u ->
                    ListItem(
                        headlineContent = { Text(u.nickname) },
                        supportingContent = { MetaTags(userFacts(u), Modifier.padding(top = Spacing.xs)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier.clickable { onOpenUser(u.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ======================= 用户详情 =======================

private sealed interface UserDialog {
    data object Sanction : UserDialog
    data class Lift(val s: com.swufe.homework.data.api.Sanction) : UserDialog
    data object Nickname : UserDialog
    data object Points : UserDialog
}

/**
 * 用户详情：只展示 AdminUser 的字段（昵称、角色、注册方式、邀请人、注册时间、是否封号、学期贡献值、近 30 天被删数、
 * 未处理举报数、最近 20 条处罚）。看不到也拿不到：密码、恢复码、token、加入了哪些班、课表、完成情况、提醒、IP。
 */
@Composable
fun AdminUserScreen(onBack: () -> Unit) {
    val vm: AdminUserViewModel = hiltViewModel()
    val snack = remember { SnackbarHostState() }
    AdminToast(vm, snack)
    var dialog by remember { mutableStateOf<UserDialog?>(null) }
    Scaffold(topBar = { BackTopBar("用户详情", onBack) }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        val u = vm.user
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                u == null && vm.error != null -> ErrorState(vm.error ?: "", onRetry = vm::load)
                u == null -> LoadingState()
                else -> UserDetail(u, vm.busy, onSanction = { dialog = UserDialog.Sanction }, onLift = { dialog = UserDialog.Lift(it) },
                    onNickname = { dialog = UserDialog.Nickname }, onPoints = { dialog = UserDialog.Points })
            }
        }
    }
    val u = vm.user ?: return
    when (val d = dialog) {
        null -> Unit
        UserDialog.Sanction -> SanctionDialog(u.nickname, onConfirm = { dialog = null; vm.sanction(it) }, onDismiss = { dialog = null })
        is UserDialog.Lift -> ConfirmDialog(
            title = "解除${sanctionLabel(d.s.kind)}？",
            text = "立即解除，对方恢复正常使用。" + if (d.s.kind == "disable") "解除封号后对方需要重新登录。" else "",
            confirmLabel = "解除",
            onConfirm = { dialog = null; vm.lift(d.s.id) },
            onDismiss = { dialog = null },
        )
        UserDialog.Nickname -> {
            var nick by rememberSaveable { mutableStateOf("") }
            val ok = nick.trim().length in 1..20 && nick.trim() != u.nickname
            ReasonConfirmDialog(
                title = "强制改昵称",
                text = "「${u.nickname}」会收到改名通知。新昵称同注册规则：1–20 字、不重名、不能用保留词。",
                confirmLabel = "改昵称",
                extraValid = ok,
                onConfirm = { reason -> dialog = null; vm.resetNickname(nick.trim(), reason) },
                onDismiss = { dialog = null },
                extra = {
                    OutlinedTextField(nick, { nick = it.take(20) }, label = { Text("新昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
            )
        }
        UserDialog.Points -> PointsDialog(u, vm, onDismiss = { dialog = null })
    }
}

@Composable
private fun UserDetail(
    u: AdminUser,
    busy: Boolean,
    onSanction: () -> Unit,
    onLift: (Sanction) -> Unit,
    onNickname: () -> Unit,
    onPoints: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(u.nickname, style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    if (u.isAdmin) Tag("管理员", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    if (u.disabled) Tag("已封号", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    MetaTag(registeredViaLabel(u.registeredVia))
                }
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = Corners.Card,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            ) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    u.inviterNickname?.let { InfoLine("邀请人", it) }
                    u.createdAt?.let { InfoLine("注册时间", Ddl.display(it)) }
                    InfoLine("本学期已结算贡献值", u.contributionTotal.toString())
                    InfoLine("近 30 天被删除的内容", "${u.deletedContent30d} 条")
                    InfoLine("针对 TA 的未处理举报", "${u.reportsAgainstOpen} 条")
                }
            }
        }
        if (!u.isAdmin) {
            item {
                Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = onSanction, enabled = !busy, shape = Corners.Button) { Text("处罚", color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onNickname, enabled = !busy, shape = Corners.Button) { Text("改昵称") }
                    OutlinedButton(onClick = onPoints, enabled = !busy, shape = Corners.Button) { Text("调贡献值") }
                }
            }
        }
        item { SectionHeader("处罚记录（最近 20 条）") }
        if (u.sanctions.isEmpty()) item {
            EmptyState("TA 没有被处罚过", icon = Icons.Outlined.VerifiedUser, fill = false)
        }
        items(u.sanctions, key = { it.id }) { s ->
            val active = sanctionActive(s)
            ListItem(
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(sanctionLabel(s.kind))
                        if (active) Tag("生效中", MaterialTheme.semantic.urgentContainer, MaterialTheme.semantic.onUrgentContainer)
                    }
                },
                supportingContent = {
                    Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        s.reason.takeIf { it.isNotBlank() }?.let { Text("原因：$it") }
                        MetaTags(sanctionFacts(s))
                    }
                },
                trailingContent = if (active) { { TextButton(onClick = { onLift(s) }, enabled = !busy) { Text("解除") } } } else null,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

internal fun registeredViaLabel(v: String): String = when (v) {
    "campus" -> "校园网注册"
    "invite" -> "邀请注册"
    "bootstrap" -> "初始账号"
    else -> v
}

/** 处罚的时间标签（开始、到期或永久、解除）；原因单独成行。 */
internal fun sanctionFacts(s: Sanction): List<String> = listOfNotNull(
    s.startsAt?.let { "开始 ${Ddl.display(it)}" },
    if (s.endsAt == null) "永久" else "到 ${Ddl.display(s.endsAt)}",
    s.liftedAt?.let { "已于 ${Ddl.display(it)} 解除" },
)

/** 调贡献值：选班（按班名搜，管理员看不到 TA 加入了哪些班）、加/扣、分值、必填原因。 */
@Composable
private fun PointsDialog(u: AdminUser, vm: AdminUserViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AdminClass>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var cls by remember { mutableStateOf<AdminClass?>(null) }
    var add by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    val n = amount.toIntOrNull()
    val delta = n?.let { if (add) it else -it }
    val search: () -> Unit = {
        if (q.isNotBlank()) scope.launch { err = null; vm.searchClasses(q).onSuccess { results = it }.onFailure { err = it.userMessage } }
    }
    ReasonConfirmDialog(
        title = "调整「${u.nickname}」的贡献值",
        text = "写进 TA 的贡献值明细（TA 能看到原因），不计入每日上限。",
        confirmLabel = "确认调整",
        extraValid = cls != null && delta != null && delta != 0 && delta in -POINTS_DELTA_MAX..POINTS_DELTA_MAX,
        onConfirm = { reason -> vm.adjustPoints(cls?.id, delta, reason); onDismiss() },
        onDismiss = onDismiss,
        extra = {
            SearchRow(q, { q = it }, "搜索班级", onSearch = search, padded = false)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            results.take(5).forEach { c -> ClassOption(c, selected = cls?.id == c.id) { cls = c } }
            ChoiceChips(listOf(true, false), add, { if (it) "加" else "扣" }) { add = it }
            OutlinedTextField(
                amount, { v -> amount = v.filter { it.isDigit() }.take(3) },
                label = { Text("分值（1–$POINTS_DELTA_MAX）") }, singleLine = true,
                isError = n != null && n !in 1..POINTS_DELTA_MAX,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
