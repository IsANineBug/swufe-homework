package com.swufe.homework.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import com.swufe.homework.data.api.Restriction
import com.swufe.homework.ui.admin.showAdminEntry
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.common.SectionHeader
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.util.Ddl
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swufe.homework.ui.homework.MetaTag
import com.swufe.homework.ui.homework.Tag
import com.swufe.homework.ui.theme.semantic
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swufe.homework.BuildConfig
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.common.FormColumn
import com.swufe.homework.ui.common.LoadingButton
import com.swufe.homework.ui.common.UpdateDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeScreen(
    onSettings: () -> Unit,
    onInvites: () -> Unit,
    onLoggedOut: () -> Unit,
    onPoints: () -> Unit = {},
    onRules: () -> Unit = {},
    onReminderGuide: () -> Unit = {},
    onAdmin: () -> Unit = {},
    vm: MeViewModel = hiltViewModel(),
) {
    val me by vm.me.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text("我的") })
        val m = me
        when {
            m != null -> {
                val via = when (m.registeredVia) { "campus" -> "校园网注册"; "invite" -> "邀请注册"; "bootstrap" -> "初始账号"; else -> "" }
                ProfileHeader(m.nickname, isAdmin = m.isAdmin, via = via)
                m.restrictions.forEach { RestrictionCard(it) }
                vm.error?.let { Box(Modifier.padding(horizontal = Spacing.lg)) { ErrorText(it) } }
            }
            vm.error != null -> ErrorState(vm.error ?: "", onRetry = vm::refresh, fill = false)
            else -> LoadingState(fill = false)
        }
        SectionHeader("账号")
        NavRow("设置", "昵称、榜单匿名", onSettings)
        NavRow("我的邀请码", me?.let { "剩余名额 ${it.invitesRemaining}" }, onInvites)
        SectionHeader("贡献值")
        NavRow("我的贡献值", "每一笔贡献值的来源", onPoints)
        NavRow("贡献值规则", null, onRules)
        // WS11：只有 /me.role=admin 才出现「管理」入口（路由层另有 AdminGate 拦截）
        if (showAdminEntry(me)) {
            SectionHeader("管理")
            NavRow("管理", "举报、班级、用户、已删除内容、公告、操作日志", onAdmin)
        }
        SectionHeader("提醒与更新")
        NavRow("提醒设置", "通知、准时提醒、电池白名单", onReminderGuide)
        ListItem(
            headlineContent = { Text("检查更新") },
            supportingContent = { Text(vm.updateMsg ?: "当前版本 ${BuildConfig.VERSION_NAME}") },
            trailingContent = if (vm.checking) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
            modifier = Modifier.clickable(enabled = !vm.checking) { vm.checkUpdate() },
        )
        HorizontalDivider(Modifier.padding(top = Spacing.sm))
        ListItem(
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text("退出登录", color = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { confirmLogout = true },
        )
        Spacer(Modifier.height(Spacing.xl))
    }
    vm.update?.let { UpdateDialog(it, onSkip = vm::skipUpdate, onDismiss = vm::dismissUpdate) }
    if (confirmLogout) {
        ConfirmDialog(
            title = "退出登录？",
            text = "退出后本机的课表缓存会清空，本地提醒也会停止。",
            confirmLabel = "退出",
            onConfirm = { confirmLogout = false; vm.logout(onLoggedOut) },
            onDismiss = { confirmLogout = false },
            destructive = true,
        )
    }
}

/** 「你被禁言到 10月30日 周五 12:00，原因：…」（api.yaml Me.restrictions）。 */
internal fun restrictionText(r: Restriction): String {
    val what = when (r.kind) { "mute" -> "禁言"; "post_ban" -> "禁发"; else -> "限制使用" }
    val until = r.endsAt?.let { Ddl.display(it) }?.takeIf { it.isNotBlank() }
    val head = if (until != null) "你被${what}到 $until" else "你被$what"
    return if (r.reason.isBlank()) head else "$head，原因：${r.reason}"
}

/** 「我的」页头：昵称首字的圆形头像 + 昵称 + 身份标签。 */
@Composable
private fun ProfileHeader(nickname: String, isAdmin: Boolean, via: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                nickname.take(1),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(nickname, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (isAdmin) Tag("管理员", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                if (via.isNotEmpty()) MetaTag(via)
            }
        }
    }
}

@Composable
private fun RestrictionCard(r: Restriction) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = Corners.Card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Filled.Block, contentDescription = null)
            Text(restrictionText(r), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NavRow(title: String, sub: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val me by vm.me.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("设置", onBack) }) { pad ->
        FormColumn(Modifier.padding(pad)) {
            OutlinedTextField(vm.nickname, { vm.nickname = it.take(20); }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LoadingButton(if (vm.saved) "已保存" else "保存昵称", vm.saving) { vm.saveNickname() }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("榜单匿名", style = MaterialTheme.typography.titleMedium)
                    Text("开启后在周榜、学期榜上显示为「匿名同学」，不影响贡献值和排名，作业页照常显示昵称。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = me?.leaderboardAnonymous == true, onCheckedChange = vm::setAnonymous, enabled = me != null)
            }
            ErrorText(vm.error)
        }
    }
}

@Composable
fun InvitesScreen(onBack: () -> Unit, vm: InvitesViewModel = hiltViewModel()) {
    val clip = LocalClipboardManager.current
    val inv = vm.invites
    Scaffold(topBar = { BackTopBar("我的邀请码", onBack) }) { pad ->
        if (inv == null) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (vm.error != null) ErrorState(vm.error ?: "", onRetry = vm::load) else LoadingState()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            item {
                Text("每人最多邀请 5 位同学，未使用的邀请码也占名额。被邀请的同学如果发布虚假内容被删，你会收到提醒。", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.sm)) {
                    Text("${inv.remaining}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("个剩余名额", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = Spacing.sm))
                }
            }
            item {
                LoadingButton("生成邀请码", vm.loading, enabled = inv.remaining > 0) { vm.create() }
            }
            item { ErrorText(vm.error) }
            if (inv.codes.isEmpty()) {
                item { EmptyState("生成一个邀请码发给同学，TA 不在校园网也能注册。", icon = Icons.Filled.PersonAdd, fill = false, title = "还没有生成邀请码") }
            }
            items(inv.codes, key = { it.code }) { c ->
                ListItem(
                    headlineContent = { Text(c.code, fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        val used = c.usedByNickname
                        if (used != null) MetaTag("已被「$used」使用")
                        else Tag("未使用", MaterialTheme.semantic.successContainer, MaterialTheme.semantic.onSuccessContainer)
                    },
                    trailingContent = {
                        if (c.usedByNickname == null) {
                            IconButton(onClick = { clip.setText(AnnotatedString(c.code)) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "复制邀请码") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
