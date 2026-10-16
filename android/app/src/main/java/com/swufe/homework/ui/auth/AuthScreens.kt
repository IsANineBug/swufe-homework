package com.swufe.homework.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.common.FormColumn
import com.swufe.homework.ui.common.LoadingButton
import com.swufe.homework.ui.common.PasswordField
import androidx.activity.compose.BackHandler

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onRegister: () -> Unit, onRecover: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    Scaffold { pad ->
        FormColumn(Modifier.padding(pad).verticalScroll(rememberScrollState()).imePadding()) {
            Spacer(Modifier.height(48.dp))
            Text("西财作业", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("同学共享作业和 DDL", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(vm.nickname, { vm.nickname = it }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordField(vm.password, { vm.password = it }, "密码")
            ErrorText(vm.error)
            LoadingButton("登录", vm.loading) { vm.submit(onLoggedIn) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRegister) { Text("注册新账号") }
                TextButton(onClick = onRecover) { Text("忘记密码？用恢复码找回") }
            }
        }
    }
}

@Composable
fun RegisterScreen(onBack: () -> Unit, onRegistered: (String) -> Unit, vm: RegisterViewModel = hiltViewModel()) {
    Scaffold(topBar = { BackTopBar("注册", onBack) }) { pad ->
        FormColumn(Modifier.padding(pad).verticalScroll(rememberScrollState()).imePadding()) {
            when (vm.campus) {
                null -> Text("正在检测是否在校园网…", style = MaterialTheme.typography.bodyMedium)
                true -> Text("你正在使用西财校园网，可直接注册。", color = MaterialTheme.colorScheme.tertiary)
                false -> Text("当前不在西财校园网，需要同学的邀请码才能注册。连上校园网后可免邀请码注册。", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(vm.nickname, { vm.nickname = it.take(20) }, label = { Text("昵称（1–20 字，不需要实名）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordField(vm.password, { vm.password = it }, "密码（至少 8 位）")
            PasswordField(vm.password2, { vm.password2 = it }, "再输一次密码")
            if (vm.campus != true) {
                OutlinedTextField(vm.inviteCode, { vm.inviteCode = it.trim() }, label = { Text("邀请码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vm.campus == false) TextButton(onClick = vm::checkCampus) { Text("已连上校园网？重新检测") }
            }
            ErrorText(vm.error)
            LoadingButton("注册", vm.loading, enabled = vm.campus != null) { vm.submit(onRegistered) }
        }
    }
}

@Composable
fun RecoverScreen(onBack: () -> Unit, onRecovered: (String) -> Unit, vm: RecoverViewModel = hiltViewModel()) {
    Scaffold(topBar = { BackTopBar("用恢复码找回", onBack) }) { pad ->
        FormColumn(Modifier.padding(pad).verticalScroll(rememberScrollState()).imePadding()) {
            Text("恢复码是注册时显示的 XXXX-XXXX-XXXX-XXXX。找回后会生成新的恢复码，旧的作废。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(vm.nickname, { vm.nickname = it }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.code, { vm.code = it }, label = { Text("恢复码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PasswordField(vm.password, { vm.password = it }, "新密码（至少 8 位）")
            PasswordField(vm.password2, { vm.password2 = it }, "再输一次新密码")
            ErrorText(vm.error)
            LoadingButton("重设密码", vm.loading) { vm.submit(onRecovered) }
        }
    }
}

/**
 * 强制展示恢复码：必须勾选「我已保存」才能继续，且屏蔽返回键。恢复码不在本地保存。
 */
@Composable
fun RecoveryCodeScreen(
    code: String,
    fromRecover: Boolean,
    onConfirmed: (loggedIn: Boolean) -> Unit,
    vm: RecoveryCodeViewModel = hiltViewModel(),
) {
    var saved by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    BackHandler(enabled = true) { /* 未确认前不允许返回 */ }
    Scaffold { pad ->
        FormColumn(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(32.dp))
            Text(if (fromRecover) "密码已重设" else "注册成功", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "这是你的恢复码。忘记密码时只能靠它找回账号，它只显示这一次，App 和服务器都不会再给你看。请抄下来或截图保存到安全的地方。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
            OutlinedButton(onClick = { clip.setText(AnnotatedString(code)); copied = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (copied) "已复制" else "复制恢复码")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(saved, { saved = it })
                Text("我已妥善保存恢复码")
            }
            LoadingButton(if (fromRecover) "去登录" else "进入 App", loading = false, enabled = saved) {
                vm.confirm(fromRecover, onConfirmed)
            }
        }
    }
}
