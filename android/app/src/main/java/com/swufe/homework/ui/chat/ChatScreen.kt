package com.swufe.homework.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.swufe.homework.data.api.Message
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.util.Ddl
import kotlinx.coroutines.flow.map

/**
 * 班级页「交流」Tab。
 * - 进入时拉最新 50 条；之后只在本页可见（Lifecycle ≥ STARTED 且这个 Tab 在组合里）时每 12 秒 after_id 拉新消息；
 *   切到别的 Tab、离开班级页、App 切后台都会立即取消轮询（[pollWhileVisible]）。不用 WebSocket、前台服务或后台任务。
 * - 往上滑到顶用 before_id 翻页。
 */
@Composable
fun ChatTab(classId: Int, vm: ChatViewModel = hiltViewModel(key = "chat-$classId")) {
    LaunchedEffect(classId) { vm.bind(classId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        pollWhileVisible(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }) { vm.fetchNewer() }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }

    var deleting by remember { mutableStateOf<Message?>(null) }
    var reporting by remember { mutableStateOf<ReportTarget?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.loading && vm.messages.isEmpty() -> LoadingState(message = "正在加载消息…")
                    vm.loadError != null && vm.messages.isEmpty() -> ErrorState(vm.loadError ?: "", onRetry = vm::loadLatest)
                    vm.messages.isEmpty() && vm.pending.isEmpty() ->
                        EmptyState("还没有人说话。班级频道只有本班同学看得到，消息保留 30 天。", icon = Icons.Outlined.Forum)
                    else -> MessageList(vm, onDelete = { deleting = it }, onReport = { reporting = it })
                }
            }
            vm.pollError?.let {
                Text(
                    "刷新失败：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
            Composer(vm)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.xl * 3))
    }

    deleting?.let { m ->
        ConfirmDialog(
            title = "删除这条消息？",
            text = "删除后大家都看不到这条消息。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = { deleting = null; vm.delete(m) },
            onDismiss = { deleting = null },
        )
    }
    reporting?.let { t -> ReportDialog(t) { reporting = null } }
}

@Composable
private fun MessageList(vm: ChatViewModel, onDelete: (Message) -> Unit, onReport: (ReportTarget) -> Unit) {
    val state = rememberLazyListState()
    // reverseLayout：索引 0 在最底部（最新）。显示顺序 = 发送中的（最新）→ 已确认的倒序。
    val pendingRev = vm.pending.asReversed()
    val msgsRev = vm.messages.asReversed()
    val total = pendingRev.size + msgsRev.size
    val nearTop by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 5 } }
    LaunchedEffect(nearTop, vm.messages.firstOrNull()?.id) { if (nearTop && vm.hasOlder) vm.loadOlder() }
    // 新消息到达（或自己刚发）时，如果本来就在底部，跟着滚到底
    val newest = vm.pending.lastOrNull()?.localId ?: vm.messages.lastOrNull()?.id
    LaunchedEffect(newest) { if (state.firstVisibleItemIndex <= 2) state.animateScrollToItem(0) }

    LazyColumn(
        state = state,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(pendingRev, key = { "p${it.localId}" }) { p ->
            Bubble(nickname = "我", time = "发送中…", body = p.body, mine = true, pending = true)
        }
        items(msgsRev, key = { "m${it.id}" }) { m ->
            MessageItem(m, onDelete = { onDelete(m) }, onReport = onReport)
        }
        if (vm.loadingOlder) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(Spacing.xl)) }
            }
        } else if (!vm.hasOlder && total > 0) {
            item(key = "top") {
                Text(
                    "更早的消息已不保留（频道只保留 30 天、每班最近 500 条）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun MessageItem(m: Message, onDelete: () -> Unit, onReport: (ReportTarget) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // 「举报这个人」：服务器带了 author_id（PR #19）且不是自己时才有
    val userTarget = userReportTarget(m.authorId, m.authorNickname, m.isMine)
    Box(Modifier.fillMaxWidth()) {
        Bubble(
            nickname = if (m.isMine) "我" else m.authorNickname,
            time = m.createdAt?.let { Ddl.display(it) }.orEmpty(),
            body = m.body,
            mine = m.isMine,
            onLongClick = { menu = true },
            // 点别人的昵称也弹出同一个菜单（含「举报这个人」）
            onNicknameClick = if (m.isMine) null else ({ menu = true }),
        )
        Box(Modifier.align(if (m.isMine) Alignment.TopEnd else Alignment.TopStart)) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.isMine) {
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("举报这条消息") },
                        leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                        onClick = { menu = false; onReport(ReportTarget(ReportKind.Message, m.id, m.body.take(40))) },
                    )
                    userTarget?.let { t ->
                        DropdownMenuItem(
                            text = { Text(REPORT_USER_LABEL) },
                            leadingIcon = { Icon(Icons.Outlined.PersonOff, contentDescription = null) },
                            onClick = { menu = false; onReport(t) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    nickname: String,
    time: String,
    body: String,
    mine: Boolean,
    pending: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onNicknameClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().alpha(if (pending) 0.6f else 1f),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            listOf(nickname, time).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (onNicknameClick != null) Modifier.clickable(onClickLabel = "更多", onClick = onNicknameClick) else Modifier,
        )
        Surface(
            shape = Corners.Card,
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = BUBBLE_MAX).let { mod ->
                if (onLongClick != null) mod.combinedClickable(onClick = {}, onLongClick = onLongClick, onLongClickLabel = if (mine) "删除" else "举报")
                else mod
            },
        ) {
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm))
        }
    }
}

private val BUBBLE_MAX = Spacing.xl * 12

@Composable
private fun Composer(vm: ChatViewModel) {
    val muted = vm.mutedNotice
    val archived = vm.archivedMessage
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        if (muted != null || archived != null) {
            // 禁言 / 班级已隐藏：输入框换成说明
            Row(
                Modifier.fillMaxWidth().padding(Spacing.lg).semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(muted ?: archived ?: "", style = MaterialTheme.typography.bodyMedium)
            }
            return@Surface
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            if (vm.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val len = chatLength(vm.input)
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    placeholder = { Text("说点什么（本班同学可见）") },
                    maxLines = 4,
                    isError = len > CHAT_MAX_LEN,
                    supportingText = {
                        Row(Modifier.fillMaxWidth()) {
                            if (vm.cooldown > 0) {
                                Text(
                                    cooldownLabel(vm.cooldown), color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            } else {
                                Text("", Modifier.weight(1f))
                            }
                            Text("$len/$CHAT_MAX_LEN")
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::send, enabled = vm.canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (vm.cooldown > 0) cooldownLabel(vm.cooldown) else "发送")
                }
            }
        }
    }
}
