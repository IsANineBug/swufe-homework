package com.swufe.homework.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.swufe.homework.data.api.Assignment
import com.swufe.homework.data.api.Attachment
import com.swufe.homework.data.api.Source
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.util.Ddl
import com.swufe.homework.ui.theme.semantic
import com.swufe.homework.ui.chat.ReportDialog
import com.swufe.homework.ui.chat.ReportKind
import com.swufe.homework.ui.chat.ReportTarget

/** 删除投票弹窗文案（requirements §七、workstreams WS5 第 4 条），原样展示。 */
const val DELETE_VOTE_WARNING = "此功能用于删除错误或虚假的内容。达到人数后将删除该内容，并扣除发布者贡献值。请确认内容有误后再使用此功能。"
const val DELETE_CONFIRM_SECONDS = 3

@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onOpenClass: (Int) -> Unit,
    onOpenAssignment: (Int) -> Unit,
    onSupplement: (classId: Int, assignmentId: Int) -> Unit,
    onEditSource: (classId: Int, assignmentId: Int, sourceId: Int) -> Unit,
    onEditAssignment: (assignmentId: Int) -> Unit = {},
    vm: DetailViewModel = hiltViewModel(),
) {
    val votedMerges by vm.votedMerges.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }

    var viewing by remember { mutableStateOf<Attachment?>(null) }
    var deleting by remember { mutableStateOf<Source?>(null) }
    var removingMine by remember { mutableStateOf<Source?>(null) }
    var merging by rememberSaveable { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }

    Scaffold(
        topBar = {
            BackTopBar("作业详情", onBack) {
                // 首发者（服务器 is_first_poster，按用户 id）可改标题、DDL、说明
                val cur = vm.a
                if (cur != null && canEditAssignment(cur, vm.gone)) {
                    TextButton(onClick = { onEditAssignment(cur.id) }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" 编辑")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            vm.a?.let { a ->
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { merging = true; vm.loadMergeTargets() }, modifier = Modifier.weight(1f)) { Text("提议合并") }
                    FilledTonalButton(onClick = { onSupplement(a.classId, a.id) }, modifier = Modifier.weight(1f)) { Text("补充") }
                    Button(onClick = vm::toggleDone, modifier = Modifier.weight(1f)) { Text(if (a.done) "取消完成" else "标记完成") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorText(vm.error)
            if (vm.gone) {
                Text("这条作业已被删除或合并到其他作业。", modifier = Modifier.padding(16.dp))
                return@Column
            }
            val a = vm.a ?: return@Column
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Header(a, onOpenClass) }
                if (a.pendingMerges.any { it.status == "pending" }) {
                    items(a.pendingMerges.filter { it.status == "pending" }, key = { "m${it.id}" }) { p ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val other = if (p.fromId == a.id) p.toId else p.fromId
                                Text(
                                    if (p.fromId == a.id) "有同学提议把这条作业合并到另一条（只搬来源，不删内容）" else "有同学提议把另一条作业合并到这里",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onOpenAssignment(other) }) { Text("查看那条") }
                                    Spacer(Modifier.weight(1f))
                                    when (mergeVoteState(p, votedMerges, viewingFrom = p.fromId == a.id, iAmFirstPoster = a.isFirstPoster)) {
                                        MergeVoteState.CanAccept ->
                                            Button(onClick = { vm.acceptMerge(p.id) }) { Text("我是发布者，同意") }
                                        MergeVoteState.CanVote ->
                                            OutlinedButton(onClick = { vm.voteMerge(p.id) }) { Text("赞同 ${p.votes}/${p.neededVotes}") }
                                        // 服务器 voted_by_me（提议人本人为 true）或本机记录：「已同意」并禁用，避免 409 already_voted
                                        MergeVoteState.Voted ->
                                            OutlinedButton(onClick = {}, enabled = false) { Text("已同意 ${p.votes}/${p.neededVotes}") }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Text("来源（${a.sources.size}）", style = MaterialTheme.typography.titleMedium) }
                items(a.sources, key = { "s${it.id}" }) { s ->
                    SourceCard(
                        s, a,
                        mine = vm.isMine(s),
                        onImage = { viewing = it },
                        onThank = { vm.thank(s) },
                        onDeleteVote = { deleting = s },
                        onEdit = { onEditSource(a.classId, a.id, s.id) },
                        onRemoveMine = { removingMine = s },
                        onReport = { reporting = ReportTarget(ReportKind.Source, s.id.toLong(), s.body.take(40)) },
                    )
                }
            }
        }
    }

    viewing?.let { ImageViewer(it) { viewing = null } }
    reporting?.let { t -> ReportDialog(t) { reporting = null } }
    deleting?.let { s -> DeleteVoteDialog(onConfirm = { deleting = null; vm.voteDelete(s) }, onDismiss = { deleting = null }) }
    removingMine?.let { s ->
        AlertDialog(
            onDismissRequest = { removingMine = null },
            title = { Text("删除自己的内容？") },
            text = { Text("删除后这条内容的贡献值作废，不额外扣分。") },
            confirmButton = { TextButton(onClick = { removingMine = null; vm.deleteMine(s, onBack) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { removingMine = null }) { Text("取消") } },
        )
    }
    if (merging) {
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text("合并到哪一条？") },
            text = {
                val list = vm.mergeTargets
                Column(Modifier.heightIn(max = 420.dp)) {
                    Text("发现两条其实是同一份作业时使用。这条的来源会搬到目标作业下，不删除任何内容。", style = MaterialTheme.typography.bodySmall)
                    when {
                        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        list.isEmpty() -> Text("这个班没有其他作业。", modifier = Modifier.padding(top = 12.dp))
                        else -> LazyColumn {
                            items(list, key = { it.id }) { t ->
                                ListItem(
                                    headlineContent = { Text(t.title) },
                                    supportingContent = { DdlLine(t.ddl, t.ddlConflict, t.ddlUncertain) },
                                    modifier = Modifier.clickable { merging = false; vm.propose(t.id) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun Header(a: Assignment, onOpenClass: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            a.className, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onOpenClass(a.classId) },
        )
        Text(a.title, style = MaterialTheme.typography.headlineSmall)
        DdlLine(a.ddl, a.ddlConflict, a.ddlUncertain)
        if (a.ddlConflict) {
            Text("补充内容里的 DDL 和这里不一致，请核对老师的通知。", color = ConflictRed, style = MaterialTheme.typography.bodySmall)
        }
        if (a.ddlUncertain) {
            Text("DDL 待确认：发布者或识别结果拿不准，请以老师通知为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semantic.warning)
        }
        Text(
            listOfNotNull(
                "首发：${a.firstPosterNickname}".takeIf { a.firstPosterNickname.isNotEmpty() },
                "已合并 ${a.mergedFrom.size} 条重复作业".takeIf { a.mergedFrom.isNotEmpty() },
                "已完成（仅自己可见）".takeIf { a.done },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SourceCard(
    s: Source,
    a: Assignment,
    mine: Boolean,
    onImage: (Attachment) -> Unit,
    onThank: () -> Unit,
    onDeleteVote: () -> Unit,
    onEdit: () -> Unit,
    onRemoveMine: () -> Unit,
    onReport: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(if (s.isFirst) "首发" else "补充", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    " ${s.authorNickname}" + (s.createdAt?.let { " · ${Ddl.display(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (mine) {
                            DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onRemoveMine() })
                        } else {
                            // v2 举报入口：作业来源（与「内容有误，投票删除」不同，交给管理员处理广告、辱骂等）
                            DropdownMenuItem(
                                text = { Text("举报") },
                                leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                                onClick = { menu = false; onReport() },
                            )
                        }
                    }
                }
            }
            if (s.body.isNotBlank()) SelectionContainer { Text(s.body, style = MaterialTheme.typography.bodyLarge) }
            s.statedDdl?.let { sd ->
                val differs = Ddl.parse(sd)?.toInstant() != Ddl.parse(a.ddl)?.toInstant()
                Text(
                    "TA 看到的 DDL：${Ddl.display(sd)}" + if (differs) "（与作业不一致）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (differs) ConflictRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val images = s.attachments.filter { it.kind == "image" }
            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images, key = { it.id }) { att ->
                        AsyncImage(
                            model = absoluteUrl(att.thumbUrl ?: att.url),
                            contentDescription = "图片，点击放大",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).clickable { onImage(att) },
                        )
                    }
                }
            }
            s.attachments.filter { it.kind != "image" }.forEach { f ->
                Text("附件（PDF，${f.bytes / 1024} KB）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onThank, enabled = !mine && !s.thankedByMe) {
                    Icon(if (s.thankedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" 谢谢 ${s.thanksCount}")
                }
                Spacer(Modifier.weight(1f))
                if (!mine) {
                    TextButton(
                        onClick = onDeleteVote,
                        enabled = !s.votedDeleteByMe,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Report, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (s.votedDeleteByMe) " 已投删除" else " 内容有误，投票删除")
                    }
                }
            }
        }
    }
}

/** 红色警示 → 固定文案 → 确认按钮倒数 3 秒后才能点（通用 ConfirmDialog 的倒数变体）。 */
@Composable
fun DeleteVoteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "投票删除",
        text = DELETE_VOTE_WARNING,
        confirmLabel = "确认删除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        countdownSeconds = DELETE_CONFIRM_SECONDS,
        icon = Icons.Filled.Report,
    )
}

/** 全屏看图，双指缩放、拖动。 */
@Composable
private fun ImageViewer(att: Attachment, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val st = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = absoluteUrl(att.url),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().transformable(st).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
