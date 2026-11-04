package com.swufe.homework.ui.homework

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.swufe.homework.reminder.Notifications
import com.swufe.homework.reminder.ReminderPrefs
import com.swufe.homework.reminder.ReminderScheduler
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.SectionHeader
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.swufe.homework.ui.theme.semantic

@HiltViewModel
class ReminderGuideViewModel @Inject constructor(
    private val prefs: ReminderPrefs,
    val scheduler: ReminderScheduler,
) : ViewModel() {
    suspend fun shouldShow(): Boolean = !prefs.guideDone.first()
    /** 一进入引导就记为已展示：无论怎么离开（含系统返回键/手势），都不会再次自动弹出。 */
    suspend fun markShown() = prefs.setGuideDone()
    fun done() { viewModelScope.launch { prefs.setGuideDone(); scheduler.rescheduleAll() } }
}

/** 首次进入主界面时自动打开一次提醒引导。 */
@Composable
fun FirstLaunchGuide(onShow: () -> Unit, vm: ReminderGuideViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { if (vm.shouldShow()) { vm.markShown(); onShow() } }
}

/**
 * 提醒设置引导：通知权限、准时提醒（系统的「闹钟和提醒」权限）、电池优化白名单。
 * requirements 十 / 十三.5：只发通知不响铃；v2 去掉自启动引导（代价：部分国产系统重启手机后要打开一次 App 提醒才重新排好）。
 */
@Composable
fun ReminderGuideScreen(onBack: () -> Unit, vm: ReminderGuideViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    // 回到本页时（tick 变化）重新读取各项状态
    val notifOk = remember(tick) { Notifications.canPost(ctx) }
    val exactOk = remember(tick) { vm.scheduler.canExact() }
    val batteryOk = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    val finish = { vm.done(); onBack() }
    BackHandler { finish() }
    Scaffold(topBar = { BackTopBar("提醒设置", finish) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                "作业提醒由手机本地定时发出（DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时），只发通知、不响铃，不依赖推送。" +
                    "国产系统默认会清理后台，请把下面几项都打开，否则提醒可能不准时。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            SectionHeader("需要开启（已开 ${listOf(notifOk, exactOk, batteryOk).count { it }}/3）")
            Step("通知权限", "用通知栏消息提醒你，带「标记完成」按钮", notifOk) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOk) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openNotificationSettings(ctx)
            }
            Step("准时提醒", "准点提醒不被推迟（系统里叫「闹钟和提醒」）", exactOk) { openExactAlarm(ctx) }
            Step("电池优化白名单", "允许后台运行，省电模式下也能提醒", batteryOk) { openBattery(ctx) }
            HorizontalDivider(Modifier.padding(vertical = Spacing.sm))
            Text(
                "部分手机重启后，要打开一次 App 提醒才会重新排好。以后可以在「我的」里的「提醒设置」再来这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
            Button(onClick = finish, shape = Corners.Button, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) { Text("完成") }
        }
    }
}

@Composable
private fun Step(title: String, sub: String, ok: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            if (ok) Icon(Icons.Filled.CheckCircle, contentDescription = "已开启", tint = MaterialTheme.semantic.success)
            else Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "未开启", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = {
            if (ok) Tag("已开启", MaterialTheme.semantic.successContainer, MaterialTheme.semantic.onSuccessContainer)
            else OutlinedButton(onClick = onClick, shape = Corners.Button) { Text("去设置") }
        },
    )
}

private fun Context.tryStart(i: Intent): Boolean = try {
    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

private fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

private fun openNotificationSettings(ctx: Context) {
    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    if (!ctx.tryStart(i)) ctx.tryStart(appDetails(ctx))
}

private fun openExactAlarm(ctx: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ctx.tryStart(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    ) return
    ctx.tryStart(appDetails(ctx))
}

@SuppressLint("BatteryLife") // 直接分发的 APK，提醒依赖后台 AlarmManager 闹钟，需要白名单（requirements §十）
private fun openBattery(ctx: Context) {
    if (ctx.tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))) return
    if (ctx.tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
    ctx.tryStart(appDetails(ctx))
}
