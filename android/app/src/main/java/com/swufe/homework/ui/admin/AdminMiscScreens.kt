package com.swufe.homework.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import com.swufe.homework.data.api.AdminDeletedSource
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.InlineNotice
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingButton
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.homework.MetaTag
import com.swufe.homework.ui.homework.SourceByline
import com.swufe.homework.ui.homework.Tag
import com.swufe.homework.util.Ddl

// ======================= 已删除内容 =======================

internal fun deletedReasonLabel(r: String?): String = when (r) {
    "vote" -> "投票删除"
    "admin" -> "管理员删除"
    "author" -> "作者自删"
    null -> "已删除"
    else -> r
}

@Composable
internal fun DeletedTab(snack: SnackbarHostState) {
    val vm: AdminDeletedViewModel = hiltViewModel()
    AdminToast(vm, snack)
    var restoring by remember { mutableStateOf<AdminDeletedSource?>(null) }
    val items = vm.items
    when {
        items == null && vm.error != null -> ErrorState(vm.error ?: "", onRetry = vm::load)
        items == null -> LoadingState(skeleton = true)
        items.isEmpty() -> EmptyState("最近 30 天没有被删除的内容", icon = Icons.Outlined.DeleteOutline)
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Text("最近 30 天被投票或管理员删除的作业内容（最多 200 条）。恢复后返还贡献值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Tag(deletedReasonLabel(s.deletedReason), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                            SourceByline(s.authorNickname, s.deletedAt, Modifier.weight(1f))
                        }
                        QuoteBlock {
                            Text(s.body.ifBlank { "（无文字）" }, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.attachments.isNotEmpty()) MetaTag("${s.attachments.size} 张图片")
                        if (s.deletedReason != "author") {
                            TextButton(onClick = { restoring = s }, enabled = !vm.busy) { Text("恢复") }
                        }
                    }
                }
            }
        }
    }
    restoring?.let { s ->
        var liftBan by rememberSaveable { mutableStateOf(true) }
        ConfirmDialog(
            title = "恢复这条内容？",
            text = "内容重新可见，作废的贡献值恢复、扣的 10 分返还，作者收到通知。",
            confirmLabel = "恢复",
            onConfirm = { restoring = null; vm.restore(s, liftBan) },
            onDismiss = { restoring = null },
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("同时解除作者在该班的禁发", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = liftBan, onCheckedChange = { liftBan = it })
                }
            },
        )
    }
}

// ======================= 公告 =======================

@Composable
internal fun AnnounceTab(snack: SnackbarHostState) {
    val vm: AdminAnnounceViewModel = hiltViewModel()
    AdminToast(vm, snack)
    var confirming by rememberSaveable { mutableStateOf(false) }
    val effective = announcementEffectiveAt(Ddl.now(), vm.days)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text("发布规则变更公告。生效时间至少在 7 天后，发布后所有账号都会收到通知。", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(vm.title, { vm.title = it.take(60) }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vm.body, { vm.body = it.take(2000) }, label = { Text("正文") }, minLines = 5, modifier = Modifier.fillMaxWidth())
        Text("几天后生效", style = MaterialTheme.typography.labelMedium)
        ChoiceChips(listOf(7L, 10L, 14L, 30L), vm.days, { "$it 天" }) { vm.days = it }
        InlineNotice("将于 ${Ddl.display(effective)} 生效", BannerTone.Info)
        LoadingButton("发布公告", vm.busy, enabled = vm.canPublish) { confirming = true }
    }
    if (confirming) {
        ConfirmDialog(
            title = "发布公告？",
            text = "「${vm.title.trim()}」将发给所有账号，发布后不能撤回。",
            confirmLabel = "发布",
            onConfirm = { confirming = false; vm.publish() },
            onDismiss = { confirming = false },
        )
    }
}

// ======================= 操作日志 =======================

@Composable
internal fun ActionsTab() {
    val vm: AdminActionsViewModel = hiltViewModel()
    val items = vm.items
    when {
        items == null && vm.error != null -> ErrorState(vm.error ?: "", onRetry = vm::load)
        items == null -> LoadingState(skeleton = true)
        items.isEmpty() -> EmptyState("还没有管理操作", icon = Icons.Outlined.History)
        else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item { Text("最近 200 条管理操作，所有管理写操作都会记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.id }) { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(actionLabel(a.action), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            a.createdAt?.let { Text(Ddl.display(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        MetaTag("${reportKindLabel(a.targetKind)} #${a.targetId}")
                        a.reason?.takeIf { it.isNotBlank() }?.let { Text("原因：$it", style = MaterialTheme.typography.bodyMedium) }
                        a.payload?.toString()?.takeIf { it != "null" && it != "{}" }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
