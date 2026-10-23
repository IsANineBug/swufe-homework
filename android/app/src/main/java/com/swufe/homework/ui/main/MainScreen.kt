package com.swufe.homework.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.swufe.homework.data.repo.AiJobState
import com.swufe.homework.data.repo.AiJobStatus
import com.swufe.homework.data.repo.AiJobTracker
import com.swufe.homework.reminder.aiJobLabel
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.StatusBanner
import com.swufe.homework.ui.me.MeScreen
import com.swufe.homework.ui.nav.DeepLinks
import com.swufe.homework.ui.nav.MainTab
import com.swufe.homework.ui.nav.Routes
import com.swufe.homework.ui.timetable.TimetableScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 底部导航外壳。[home] 由 WS5 提供首页（/feed）；WS4 默认放占位。
 * 顶部显示后台识图状态条（[AiJobTracker]），识别结束时在本页弹 Snackbar。
 * [onOpenAiJob]：点状态条/Snackbar 进确认页；manual = true 表示点的是失败后的「手动添加」。
 */
@Composable
fun MainScreen(
    onImport: () -> Unit,
    onManual: () -> Unit,
    onOpenClass: (Int) -> Unit,
    onSettings: () -> Unit,
    onInvites: () -> Unit,
    onLoggedOut: () -> Unit,
    onPoints: () -> Unit = {},
    onRules: () -> Unit = {},
    onReminderGuide: () -> Unit = {},
    onOpenAiJob: (AiJobState, manual: Boolean) -> Unit = { _, _ -> },
    home: @Composable () -> Unit = { HomePlaceholder() },
    ai: AiJobsViewModel = hiltViewModel(),
) {
    // WS5 接入首页后默认打开首页（作业列表）
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    val jobs by ai.jobs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val open: (AiJobState, Boolean) -> Unit = { s, manual -> ai.beforeOpen(s, manual); onOpenAiJob(s, manual) }
    AiJobSnackbarEffect(ai, snackbar, show = { true }, onOpen = open)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) { MainTab.Home -> Icons.Filled.Home; MainTab.Timetable -> Icons.Filled.CalendarMonth; MainTab.Me -> Icons.Filled.Person },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            jobs.sortedBy { it.kind }.forEach { s -> AiJobBanner(s, ai, open) }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    MainTab.Home -> home()
                    MainTab.Timetable -> TimetableScreen(onImport, onManual, onOpenClass)
                    MainTab.Me -> MeScreen(onSettings, onInvites, onLoggedOut, onPoints = onPoints, onRules = onRules, onReminderGuide = onReminderGuide)
                }
            }
        }
    }
}

@Composable
private fun HomePlaceholder() {
    EmptyState("首页作业列表开发中")
}

// ---------------- 后台识图 ----------------

@HiltViewModel
class AiJobsViewModel @Inject constructor(val tracker: AiJobTracker) : ViewModel() {
    val jobs = tracker.jobs
    val events = tracker.events
    fun dismiss(s: AiJobState) { viewModelScope.launch { tracker.dismiss(s.jobId) } }

    /** 课表识别失败后点「手动添加」：任务不要了，再进手动添加页。作业的失败原因留给发布页显示。 */
    fun beforeOpen(s: AiJobState, manual: Boolean) {
        if (manual && s.kind == AiJobTracker.PARSE_TIMETABLE) dismiss(s)
    }
}

/** 状态条：「课表识别中…」/「课表识别好了 · 去确认 ›」/ 失败原因 +「手动添加」。整条可点，进对应确认页。 */
@Composable
private fun AiJobBanner(s: AiJobState, ai: AiJobsViewModel, onOpen: (AiJobState, Boolean) -> Unit) {
    val what = aiJobLabel(s.kind)
    when (s.status) {
        AiJobStatus.Running -> StatusBanner("${what}识别中…", BannerTone.Progress, onClick = { onOpen(s, false) })
        AiJobStatus.Done -> StatusBanner(
            "${what}识别好了", BannerTone.Success,
            actionLabel = "去确认 ›", onAction = { onOpen(s, false) }, onClick = { onOpen(s, false) },
            onDismiss = { ai.dismiss(s) },
        )
        AiJobStatus.Failed, AiJobStatus.Timeout -> StatusBanner(
            "$what${s.message}", BannerTone.Error,
            actionLabel = "手动添加", onAction = { onOpen(s, true) }, onClick = { onOpen(s, true) },
            onDismiss = { ai.dismiss(s) },
        )
    }
}

/**
 * 识别结束时弹 Snackbar（只在所在页面处于前台时收集；App 在后台时由 AiJobNotifier 发系统通知）。
 * [show] 决定这一条要不要在这里弹（例如用户正在对应确认页上就不弹，确认页自己会接住结果）。
 */
@Composable
fun AiJobSnackbarEffect(
    ai: AiJobsViewModel,
    host: SnackbarHostState,
    show: (AiJobState) -> Boolean,
    onOpen: (AiJobState, manual: Boolean) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentShow by rememberUpdatedState(show)
    val currentOpen by rememberUpdatedState(onOpen)
    val scope = rememberCoroutineScope()
    LaunchedEffect(ai, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ai.events.collect { s ->
                if (!currentShow(s)) return@collect
                val what = aiJobLabel(s.kind)
                val ok = s.status == AiJobStatus.Done
                // 不阻塞收集：后来的事件会替换当前这条
                scope.launch {
                    host.currentSnackbarData?.dismiss()
                    val r = host.showSnackbar(
                        message = if (ok) "${what}识别好了" else "$what${s.message}",
                        actionLabel = if (ok) "去确认" else "手动添加",
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) currentOpen(s, !ok)
                }
            }
        }
    }
}

/**
 * 放在 AppNavHost 的 NavHost 之后：
 * - 不在主界面（主界面自己弹）、也不在对应确认页时，识别结束弹 Snackbar；
 * - 处理「AI 识别」通知的点击（[DeepLinks.aiKind]）。
 */
@Composable
fun AiJobSnackbarOverlay(nav: NavHostController, deepLinks: DeepLinks?, ai: AiJobsViewModel = hiltViewModel()) {
    val entry by nav.currentBackStackEntryAsState()
    val host = remember { SnackbarHostState() }
    val open: (AiJobState, Boolean) -> Unit = { s, manual -> ai.beforeOpen(s, manual); nav.openAiJob(s, manual) }
    AiJobSnackbarEffect(ai, host, show = { s -> entry?.let { !it.destination.hasRoute<Routes.Main>() && !isConfirmPageFor(it, s) } ?: false }, onOpen = open)
    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(host) }

    if (deepLinks != null) {
        LaunchedEffect(deepLinks) {
            deepLinks.aiKind.collect { kind ->
                if (kind != null) {
                    ai.tracker.restore()
                    ai.tracker.job(kind)?.let { s ->
                        val manual = s.status == AiJobStatus.Failed || s.status == AiJobStatus.Timeout
                        ai.beforeOpen(s, manual)
                        nav.openAiJob(s, manual)
                    }
                    deepLinks.consumeAi()
                }
            }
        }
    }
}

/** 当前页是不是会自己接住这个任务结果的确认页。 */
private fun isConfirmPageFor(entry: NavBackStackEntry, s: AiJobState): Boolean {
    val d = entry.destination
    return when (s.kind) {
        AiJobTracker.PARSE_TIMETABLE -> d.hasRoute<Routes.TimetableImport>()
        AiJobTracker.PARSE_ASSIGNMENT -> d.hasRoute<Routes.Publish>() && runCatching { entry.toRoute<Routes.Publish>() == publishRoute(s) }.getOrDefault(false)
        else -> false
    }
}

/** 作业草稿页的路由参数放在任务 context 里（class_id / assignment_id / source_id）。 */
fun publishRoute(s: AiJobState): Routes.Publish? {
    val classId = s.context["class_id"]?.toIntOrNull() ?: return null
    return Routes.Publish(
        classId = classId,
        assignmentId = s.context["assignment_id"]?.toIntOrNull() ?: -1,
        sourceId = s.context["source_id"]?.toIntOrNull() ?: -1,
    )
}

/**
 * 进对应确认页。课表：完成/进行中 → 导入页（从 tracker 读结果）；失败「手动添加」→ 手动添加页（调用方先 [AiJobsViewModel.beforeOpen] 放弃任务）。
 * 作业：都进发布页（发布页从 tracker 取结果或失败原因，并恢复识别前填的文字和图片）。
 */
fun NavHostController.openAiJob(s: AiJobState, manual: Boolean) {
    when (s.kind) {
        AiJobTracker.PARSE_TIMETABLE ->
            navigate(if (manual) Routes.TimetableManual else Routes.TimetableImport) { launchSingleTop = true }
        AiJobTracker.PARSE_ASSIGNMENT -> publishRoute(s)?.let { navigate(it) { launchSingleTop = true } }
    }
}
