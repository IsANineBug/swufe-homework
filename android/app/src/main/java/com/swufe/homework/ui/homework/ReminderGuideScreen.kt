package com.swufe.homework.ui.homework

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.swufe.homework.reminder.Notifications
import com.swufe.homework.reminder.ReminderPrefs
import com.swufe.homework.reminder.ReminderScheduler
import com.swufe.homework.ui.common.BackTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReminderGuideViewModel @Inject constructor(
    private val prefs: ReminderPrefs,
    val scheduler: ReminderScheduler,
) : ViewModel() {
    suspend fun shouldShow(): Boolean = !prefs.guideDone.first()
    fun done() { viewModelScope.launch { prefs.setGuideDone(); scheduler.rescheduleAll() } }
}

/** 首次进入主界面时自动打开一次提醒引导。 */
@Composable
fun FirstLaunchGuide(onShow: () -> Unit, vm: ReminderGuideViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { if (vm.shouldShow()) onShow() }
}

/**
 * 提醒设置引导：通知权限、精确闹钟、电池优化白名单、自启动（按品牌跳转，通用兜底到应用详情）。
 * requirements §十：首次打开时引导开启自启动和电池白名单。
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
    Scaffold(topBar = { BackTopBar("提醒设置", finish) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "作业提醒由手机本地闹钟触发（DDL 前 2 天 09:00、前 1 天 09:00、前 12 小时），不依赖推送。" +
                    "国产系统默认会清理后台，请把下面几项都打开，否则提醒可能不响。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Step("通知权限", "用通知栏消息提醒你，带「标记完成」按钮", notifOk) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOk) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openNotificationSettings(ctx)
            }
            Step("精确闹钟", "准点提醒，不被系统推迟", exactOk) { openExactAlarm(ctx) }
            Step("电池优化白名单", "允许后台运行，省电模式下也能提醒", batteryOk) { openBattery(ctx) }
            Step("自启动 / 后台运行", "开机和被清理后能重新设置闹钟（${Build.MANUFACTURER}）", null) { openAutoStart(ctx) }
            HorizontalDivider()
            Text("以后可以在「我的 → 提醒设置」再来这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Button(onClick = finish, modifier = Modifier.padding(top = 8.dp)) { Text("完成") }
        }
    }
}

@Composable
private fun Step(title: String, sub: String, ok: Boolean?, onClick: () -> Unit) {
    ListItem(
        leadingContent = {
            when (ok) {
                true -> Icon(Icons.Filled.CheckCircle, contentDescription = "已开启", tint = Color(0xFF2E7D32))
                false -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "未开启")
                null -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "需手动确认", tint = MaterialTheme.colorScheme.outline)
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        trailingContent = { OutlinedButton(onClick = onClick, enabled = ok != true) { Text(if (ok == true) "已开启" else "去设置") } },
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

@SuppressLint("BatteryLife") // 直接分发的 APK，提醒依赖后台闹钟，需要白名单（requirements §十）
private fun openBattery(ctx: Context) {
    if (ctx.tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))) return
    if (ctx.tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
    ctx.tryStart(appDetails(ctx))
}

/** 各品牌自启动管理页（按顺序尝试），都打不开就进应用详情页。 */
private val AUTO_START = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
    "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
)

private fun openAutoStart(ctx: Context) {
    for ((pkg, cls) in AUTO_START) {
        if (ctx.tryStart(Intent().setComponent(ComponentName(pkg, cls)))) return
    }
    ctx.tryStart(appDetails(ctx))
}
