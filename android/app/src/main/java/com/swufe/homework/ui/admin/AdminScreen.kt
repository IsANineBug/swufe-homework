package com.swufe.homework.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swufe.homework.data.api.AdminClass
import com.swufe.homework.data.api.AdminReport
import com.swufe.homework.data.api.AdminUser
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic
import com.swufe.homework.ui.homework.MetaTags
import com.swufe.homework.ui.homework.SourceByline
import com.swufe.homework.ui.homework.Tag
import com.swufe.homework.util.Ddl
import kotlinx.coroutines.launch

/** 管理页的分区。 */
enum class AdminTab(val label: String) { Reports("举报"), Classes("班级"), Users("用户"), Deleted("已删除"), Announce("公告"), Log("操作日志") }

/**
 * 管理（只有管理员可见，入口在「我的」；路由由 [AdminGate] 拦截）。
 * 隐私底线：这里只展示 api.yaml 的 AdminUser / AdminClass / AdminReport 字段，没有任何浏览频道、看课表/完成情况/提醒的入口。
 */
@Composable
fun AdminScreen(onBack: () -> Unit, onOpenUser: (Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    Scaffold(
        topBar = { BackTopBar("管理", onBack) },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = Spacing.lg) {
                AdminTab.entries.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label) }) }
            }
            when (AdminTab.entries[tab]) {
                AdminTab.Reports -> ReportsTab(snack, onOpenUser)
                AdminTab.Classes -> ClassesTab(snack)
                AdminTab.Users -> UsersTab(snack, onOpenUser)
                AdminTab.Deleted -> DeletedTab(snack)
                AdminTab.Announce -> AnnounceTab(snack)
                AdminTab.Log -> ActionsTab()
            }
        }
    }
}

// ======================= 举报 =======================

/** 正在进行的举报处置（弹窗状态）。 */
private sealed interface ReportDialogState {
    data class Delete(val r: AdminReport) : ReportDialogState
    data class Hide(val r: AdminReport, val candidates: List<AdminClass>) : ReportDialogState
    data class Punish(val r: AdminReport, val user: AdminUser) : ReportDialogState
    data class Dismiss(val r: AdminReport) : ReportDialogState
}

@Composable
private fun ReportsTab(snack: SnackbarHostState, onOpenUser: (Int) -> Unit) {
    val vm: AdminReportsViewModel = hiltViewModel()
    AdminToast(vm, snack)
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<ReportDialogState?>(null) }

    /** 处罚、隐藏班级需要先查到对象（举报里只有昵称、班名）。 */
    fun start(r: AdminReport, a: ReportAction) {
        when (a) {
            ReportAction.DeleteContent -> dialog = ReportDialogState.Delete(r)
            ReportAction.Dismiss -> dialog = ReportDialogState.Dismiss(r)
            ReportAction.Punish -> {
                val u = r.userTarget()
                if (u != null) { dialog = ReportDialogState.Punish(r, u); return }
                val nick = r.authorNickname() ?: return
                scope.launch {
                    vm.findUser(nick)
                        .onSuccess { found -> if (found != null) dialog = ReportDialogState.Punish(r, found) else snack.showSnackbar("找不到昵称为「$nick」的用户（可能已改名）") }
                        .onFailure { snack.showSnackbar(it.userMessage) }
                }
            }
            ReportAction.HideClass -> {
                val c = r.classTarget()
                if (c != null) {
                    // 举报的就是这个班：target_id 即班级 id
                    dialog = ReportDialogState.Hide(r, listOf(AdminClass(id = r.targetId.toInt(), name = c.name, memberCount = c.memberCount, assignmentCount = c.assignmentCount, createdByNickname = c.createdByNickname)))
                    return
                }
                val name = r.className() ?: return
                scope.launch {
                    vm.findClasses(name)
                        .onSuccess { l -> if (l.isEmpty()) snack.showSnackbar("找不到「$name」（可能已隐藏或改名）") else dialog = ReportDialogState.Hide(r, l) }
                        .onFailure { snack.showSnackbar(it.userMessage) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = !vm.handled, onClick = { vm.showHandled(false) }, label = { Text("未处理") })
            FilterChip(selected = vm.handled, onClick = { vm.showHandled(true) }, label = { Text("已处理") })
        }
        val items = vm.items
        when {
            items == null && vm.error != null -> ErrorState(vm.error ?: "", onRetry = vm::load)
            items == null -> LoadingState(skeleton = true)
            items.isEmpty() -> EmptyState(if (vm.handled) "还没有处理过的举报" else "没有待处理的举报", icon = Icons.Outlined.Flag)
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(items, key = { "${it.targetKind}-${it.targetId}-${it.id}" }) { r ->
                    ReportCard(r, busy = vm.busy, onAction = { start(r, it) }, onOpenUser = onOpenUser)
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is ReportDialogState.Delete -> {
            var penalize by rememberSaveable { mutableStateOf(true) }
            val isSource = d.r.targetKind == "source"
            ReasonConfirmDialog(
                title = if (isSource) "删除这条作业内容？" else "删除这条消息？",
                text = if (isSource) "内容会对所有人隐藏，作者收到通知；之后可以在「已删除」里恢复。" else "消息会对所有人隐藏，作者收到通知。",
                confirmLabel = "删除",
                onConfirm = { reason -> dialog = null; vm.deleteContent(d.r, reason, penalize) },
                onDismiss = { dialog = null },
                extra = if (!isSource) null else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("同时处罚（收回贡献值并扣 10，计入禁发次数）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(checked = penalize, onCheckedChange = { penalize = it })
                        }
                    }
                },
            )
        }
        is ReportDialogState.Hide -> {
            var chosen by rememberSaveable { mutableIntStateOf(if (d.candidates.size == 1) d.candidates[0].id else -1) }
            ReasonConfirmDialog(
                title = "隐藏班级？",
                text = "隐藏后成员的课表里不再显示它，搜索、导入都找不到，频道和作业只读不可见；数据保留，可在「班级」里恢复。",
                confirmLabel = "隐藏",
                onConfirm = { reason -> dialog = null; vm.hideClass(d.r, chosen, reason) },
                onDismiss = { dialog = null },
                extraValid = chosen > 0,
                extra = {
                    if (d.candidates.size > 1) Text("有 ${d.candidates.size} 个同名的班，选一个：", style = MaterialTheme.typography.labelMedium)
                    d.candidates.take(8).forEach { c -> ClassOption(c, selected = chosen == c.id) { chosen = c.id } }
                },
            )
        }
        is ReportDialogState.Punish -> SanctionDialog(
            nickname = d.user.nickname,
            onConfirm = { req -> dialog = null; vm.punish(d.r, d.user.id, req) },
            onDismiss = { dialog = null },
        )
        is ReportDialogState.Dismiss -> ConfirmDialog(
            title = "驳回这条举报？",
            text = "不做处置，这个对象的所有未处理举报一起关掉。",
            confirmLabel = "驳回",
            onConfirm = { dialog = null; vm.dismiss(d.r) },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ReportCard(r: AdminReport, busy: Boolean, onAction: (ReportAction) -> Unit, onOpenUser: (Int) -> Unit) {
    var open by rememberSaveable(r.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(reportKindLabel(r.targetKind), style = MaterialTheme.typography.titleSmall)
                Tag("${r.reporters.size} 人举报", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            if (r.handledAt != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Tag(resolutionLabel(r.resolution), MaterialTheme.semantic.successContainer, MaterialTheme.semantic.onSuccessContainer)
                    Text(Ddl.display(r.handledAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ReportTargetView(r, expanded = open, onOpenUser = onOpenUser)
            val shown = if (open) r.reporters else r.reporters.take(2)
            shown.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SourceByline(p.nickname, p.createdAt)
                    Text(
                        p.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!open && r.reporters.size > shown.size) {
                Text("还有 ${r.reporters.size - shown.size} 条举报，点开查看", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            val actions = reportActions(r)
            if (actions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(actions) { a ->
                        if (a == ReportAction.Dismiss) TextButton(onClick = { onAction(a) }, enabled = !busy) { Text(a.label) }
                        else OutlinedButton(onClick = { onAction(a) }, enabled = !busy, shape = Corners.Button) {
                            Text(a.label, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** 被举报对象的内容。消息展开后带前后各 5 条上下文（举报接口给的，不能浏览频道其他部分）。 */
@Composable
private fun ReportTargetView(r: AdminReport, expanded: Boolean, onOpenUser: (Int) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (r.targetKind) {
        "source" -> r.sourceTarget()?.let { t ->
            TargetByline(t.className, t.authorNickname, null)
            QuoteBlock {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(t.body.ifBlank { "（无文字）" }, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    if (t.thumbUrls.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            items(t.thumbUrls) { u ->
                                AsyncImage(u, contentDescription = "附件缩略图", contentScale = ContentScale.Crop, modifier = Modifier.size(Spacing.xl * 3))
                            }
                        }
                    }
                }
            }
        } ?: GoneText()
        "message" -> r.messageTarget()?.let { t ->
            TargetByline(t.className, t.authorNickname, t.createdAt)
            if (!expanded) {
                QuoteBlock { Text(t.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            } else {
                // 上下文按 id 升序；被举报的那条插在中间并高亮
                val before = t.context.filter { it.id < r.targetId }
                val after = t.context.filter { it.id > r.targetId }
                Text("上下文（前后各最多 5 条）", style = MaterialTheme.typography.labelSmall, color = muted)
                before.forEach { ContextLine(it.authorNickname, it.body) }
                QuoteBlock { Text("${t.authorNickname}：${t.body}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                after.forEach { ContextLine(it.authorNickname, it.body) }
            }
        } ?: GoneText()
        "class" -> r.classTarget()?.let { t ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                MetaTags(
                    listOfNotNull(
                        "${t.memberCount} 人",
                        "${t.assignmentCount} 份作业",
                        "创建者 ${t.createdByNickname}".takeIf { t.createdByNickname.isNotBlank() },
                    ),
                )
            }
        } ?: GoneText()
        "user" -> r.userTarget()?.let { u ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(u.nickname, style = MaterialTheme.typography.bodyLarge)
                MetaTags(userFacts(u))
                TextButton(onClick = { onOpenUser(u.id) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("查看用户详情") }
            }
        } ?: GoneText()
        else -> GoneText()
    }
}

/** 被举报内容的出处：班名（主色小字）+ 作者与时间。 */
@Composable
private fun TargetByline(className: String, author: String, createdAt: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (className.isNotBlank()) {
            Text(className, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SourceByline(author, createdAt)
    }
}

@Composable
private fun ContextLine(nick: String, body: String) {
    Text("$nick：$body", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GoneText() {
    Text("对象已不存在（可能已过保留期被清理）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ======================= 班级 =======================

private sealed interface ClassDialog {
    data class Hide(val c: AdminClass) : ClassDialog
    data class Unhide(val c: AdminClass) : ClassDialog
    data class Rename(val c: AdminClass) : ClassDialog
    data class Merge(val from: AdminClass) : ClassDialog
    data class Revert(val m: com.swufe.homework.data.api.ClassMergeRecord) : ClassDialog
}

@Composable
private fun ClassesTab(snack: SnackbarHostState) {
    val vm: AdminClassesViewModel = hiltViewModel()
    AdminToast(vm, snack)
    var showMerges by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ClassDialog?>(null) }
    LaunchedEffect(showMerges) { if (showMerges && vm.merges == null) vm.loadMerges() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = !showMerges, onClick = { showMerges = false }, label = { Text("班级") })
            FilterChip(selected = showMerges, onClick = { showMerges = true }, label = { Text("合并记录") })
        }
        if (showMerges) MergesList(vm, onRevert = { dialog = ClassDialog.Revert(it) })
        else {
            SearchRow(vm.query, { vm.query = it }, "按班名搜索", onSearch = { vm.search() })
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(selected = vm.suspicious, onClick = vm::toggleSuspicious, label = { Text("可疑") })
                FilterChip(selected = vm.includeHidden, onClick = vm::toggleHidden, label = { Text("含已隐藏") })
            }
            val items = vm.items
            when {
                items == null && vm.error != null -> ErrorState(vm.error ?: "", onRetry = { vm.search(vm.page) })
                items == null -> LoadingState(skeleton = true)
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (items.isEmpty()) item { EmptyState(if (vm.suspicious) "没有可疑的班级" else "没有找到班级", icon = Icons.Outlined.Groups, fill = false) }
                    items(items, key = { it.id }) { c ->
                        ClassCard(c, vm.busy, onHide = { dialog = ClassDialog.Hide(c) }, onUnhide = { dialog = ClassDialog.Unhide(c) },
                            onRename = { dialog = ClassDialog.Rename(c) }, onMerge = { dialog = ClassDialog.Merge(c) })
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { vm.search(vm.page - 1) }, enabled = vm.page > 1 && !vm.loading) { Text("上一页") }
                            Text("第 ${vm.page} 页", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                            TextButton(onClick = { vm.search(vm.page + 1) }, enabled = items.size >= ADMIN_CLASS_PAGE && !vm.loading) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is ClassDialog.Hide -> ReasonConfirmDialog(
            title = "隐藏「${d.c.name}」？",
            text = "隐藏后成员的课表里不再显示它，搜索、导入都找不到，频道和作业只读不可见；数据保留，可以恢复。",
            confirmLabel = "隐藏",
            onConfirm = { reason -> dialog = null; vm.hide(d.c, reason) },
            onDismiss = { dialog = null },
        )
        is ClassDialog.Unhide -> ConfirmDialog(
            title = "恢复「${d.c.name}」？",
            text = "成员的课表、搜索、频道和作业都会恢复。",
            confirmLabel = "恢复",
            onConfirm = { dialog = null; vm.unhide(d.c) },
            onDismiss = { dialog = null },
        )
        is ClassDialog.Rename -> RenameClassDialog(d.c, onDismiss = { dialog = null }) { name, teacher, reason ->
            dialog = null; vm.rename(d.c, name, teacher, reason)
        }
        is ClassDialog.Merge -> MergeClassDialog(d.from, vm, onDismiss = { dialog = null })
        is ClassDialog.Revert -> ConfirmDialog(
            title = "撤销这次合并？",
            text = "「${d.m.fromName}」会从「${d.m.intoName}」里拆回来：时段、作业、贡献值和成员按合并前的快照恢复。",
            confirmLabel = "撤销合并",
            destructive = true,
            onConfirm = { dialog = null; vm.revert(d.m) },
            onDismiss = { dialog = null },
        )
    }
}

/** /admin/classes 每页 50 条。 */
const val ADMIN_CLASS_PAGE = 50

@Composable
private fun ClassCard(c: AdminClass, busy: Boolean, onHide: () -> Unit, onUnhide: () -> Unit, onRename: () -> Unit, onMerge: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(classTitle(c), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (c.hidden) Tag("已隐藏", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
            MetaTags(classFacts(c.copy(hidden = false)) + "${c.slotCount} 个时段")
            c.createdAt?.let { Text("创建于 ${Ddl.display(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextButton(onClick = onRename, enabled = !busy) { Text("改名") }
                if (!c.hidden) TextButton(onClick = onMerge, enabled = !busy) { Text("合并") }
                if (c.hidden) TextButton(onClick = onUnhide, enabled = !busy) { Text("恢复") }
                else TextButton(onClick = onHide, enabled = !busy) { Text("隐藏", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun RenameClassDialog(c: AdminClass, onDismiss: () -> Unit, onConfirm: (String, String?, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(c.name) }
    var teacher by rememberSaveable { mutableStateOf(c.teacher.orEmpty()) }
    val nameOk = name.trim().length in 1..60
    ReasonConfirmDialog(
        title = "修改班级信息",
        text = "用于修正识别错误的课名或教师。",
        confirmLabel = "保存",
        destructive = false,
        extraValid = nameOk && (name.trim() != c.name || teacher.trim() != c.teacher.orEmpty()),
        onConfirm = { reason -> onConfirm(name.trim(), teacher.trim(), reason) },
        onDismiss = onDismiss,
        extra = {
            androidx.compose.material3.OutlinedTextField(name, { name = it.take(60) }, label = { Text("班名") }, singleLine = true, isError = !nameOk, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.OutlinedTextField(teacher, { teacher = it.take(60) }, label = { Text("教师（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
    )
}

/** 合并两个班：搜目标班，再二次确认（必填原因），提示可在合并记录里撤销。 */
@Composable
private fun MergeClassDialog(from: AdminClass, vm: AdminClassesViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf(from.name) }
    var results by remember { mutableStateOf<List<AdminClass>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var into by remember { mutableStateOf<AdminClass?>(null) }
    val repoSearch: () -> Unit = {
        scope.launch {
            err = null
            vm.searchForMerge(q).onSuccess { l -> results = l.filter { it.id != from.id && !it.hidden } }.onFailure { err = it.userMessage }
        }
    }
    LaunchedEffect(Unit) { repoSearch() }
    ReasonConfirmDialog(
        title = "合并「${from.name}」",
        text = "把这个班的成员、时段和作业并进选中的班（两班须同学期、都未隐藏）。合并后可以在「合并记录」里撤销。",
        confirmLabel = "确认合并",
        extraValid = into != null,
        onConfirm = { reason -> into?.let { vm.merge(from, it, reason) }; onDismiss() },
        onDismiss = onDismiss,
        extra = {
            SearchRow(q, { q = it }, "搜索要并入的班", onSearch = repoSearch, padded = false)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (results.isEmpty()) Text("没有可选的班，换个班名再搜", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            results.take(6).forEach { c -> ClassOption(c, selected = into?.id == c.id) { into = c } }
            into?.let { Text("将把「${from.name}」并入「${it.name}」", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun MergesList(vm: AdminClassesViewModel, onRevert: (com.swufe.homework.data.api.ClassMergeRecord) -> Unit) {
    val items = vm.merges
    when {
        items == null && vm.mergesError != null -> ErrorState(vm.mergesError ?: "", onRetry = vm::loadMerges)
        items == null -> LoadingState(skeleton = true)
        items.isEmpty() -> EmptyState("还没有班级合并记录", icon = Icons.Outlined.Groups)
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Text("最近 100 次班级合并（自动和手动），含已撤销的。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.md, bottom = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text("「${m.fromName}」并入「${m.intoName}」", style = MaterialTheme.typography.titleSmall)
                            SourceByline(m.triggeredByNickname?.let { "由 $it 触发" } ?: "管理员合并", m.createdAt)
                            m.undoneAt?.let { Tag("已于 ${Ddl.display(it)} 撤销", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (m.undoneAt == null) TextButton(onClick = { onRevert(m) }, enabled = !vm.busy) { Text("撤销", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
