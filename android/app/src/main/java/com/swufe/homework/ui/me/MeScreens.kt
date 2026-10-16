package com.swufe.homework.ui.me

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
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
    vm: MeViewModel = hiltViewModel(),
) {
    val me by vm.me.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopAppBar(title = { Text("我的") })
        ListItem(
            headlineContent = { Text(me?.nickname ?: "…", style = MaterialTheme.typography.titleLarge) },
            supportingContent = {
                val via = when (me?.registeredVia) { "campus" -> "校园网注册"; "invite" -> "邀请注册"; "bootstrap" -> "初始账号"; else -> "" }
                Text(listOf(if (me?.isAdmin == true) "管理员" else null, via.ifEmpty { null }).filterNotNull().joinToString(" · "))
            },
        )
        ErrorText(vm.error)
        HorizontalDivider()
        NavRow("设置", "昵称、榜单匿名", onSettings)
        NavRow("我的邀请码", me?.let { "剩余名额 ${it.invitesRemaining}" }, onInvites)
        NavRow("我的积分", "每一笔积分的来源", onPoints)
        NavRow("积分规则", null, onRules)
        NavRow("提醒设置", "通知、精确闹钟、自启动、电池白名单", onReminderGuide)
        ListItem(
            headlineContent = { Text("检查更新") },
            supportingContent = { Text(vm.updateMsg ?: "当前版本 ${BuildConfig.VERSION_NAME}") },
            modifier = Modifier.clickable(enabled = !vm.checking) { vm.checkUpdate() },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("退出登录", color = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { confirmLogout = true },
        )
    }
    vm.update?.let { UpdateDialog(it, onSkip = vm::skipUpdate, onDismiss = vm::dismissUpdate) }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("退出后本机的课表缓存会清空，本地提醒也会停止。") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout(onLoggedOut) }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
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
                    Text("开启后在周榜、学期榜上显示为「匿名同学」，不影响积分和排名，作业页照常显示昵称。", style = MaterialTheme.typography.bodySmall)
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
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("每人最多邀请 5 位同学，未使用的邀请码也占名额。被邀请的同学如果发布虚假内容被删，你会收到提醒。", style = MaterialTheme.typography.bodyMedium)
            }
            item { Text("剩余名额：${inv?.remaining ?: "…"}", style = MaterialTheme.typography.titleMedium) }
            item {
                LoadingButton("生成邀请码", vm.loading, enabled = (inv?.remaining ?: 0) > 0) { vm.create() }
            }
            item { ErrorText(vm.error) }
            items(inv?.codes.orEmpty(), key = { it.code }) { c ->
                ListItem(
                    headlineContent = { Text(c.code, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text(c.usedByNickname?.let { "已被「$it」使用" } ?: "未使用") },
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
