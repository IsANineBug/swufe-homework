package com.swufe.homework.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.swufe.homework.data.api.SessionEvents
import com.swufe.homework.data.repo.AccountRepository
import com.swufe.homework.data.repo.AuthRepository
import com.swufe.homework.data.repo.UpdateInfo
import com.swufe.homework.data.repo.UpdateRepository
import com.swufe.homework.ui.admin.AdminGate
import com.swufe.homework.ui.admin.AdminScreen
import com.swufe.homework.ui.admin.AdminUserScreen
import com.swufe.homework.ui.auth.LoginScreen
import com.swufe.homework.ui.auth.RecoverScreen
import com.swufe.homework.ui.auth.RecoveryCodeScreen
import com.swufe.homework.ui.auth.RegisterScreen
import com.swufe.homework.ui.chat.ClassReportMenu
import com.swufe.homework.ui.common.CenterLoading
import com.swufe.homework.ui.common.UpdateDialog
import com.swufe.homework.ui.homework.ClassBody
import com.swufe.homework.ui.homework.DetailScreen
import com.swufe.homework.ui.homework.EditAssignmentScreen
import com.swufe.homework.ui.homework.FirstLaunchGuide
import com.swufe.homework.ui.homework.HomeScreen
import com.swufe.homework.ui.homework.MyPointsScreen
import com.swufe.homework.ui.homework.PointsRulesScreen
import com.swufe.homework.ui.homework.PublishScreen
import com.swufe.homework.ui.homework.ReminderGuideScreen
import com.swufe.homework.ui.main.AiJobSnackbarOverlay
import com.swufe.homework.ui.main.MainScreen
import com.swufe.homework.ui.main.openAiJob
import com.swufe.homework.ui.me.InvitesScreen
import com.swufe.homework.ui.me.SettingsScreen
import com.swufe.homework.ui.timetable.ClassPageScreen
import com.swufe.homework.ui.timetable.ImportScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val account: AccountRepository,
    private val updates: UpdateRepository,
    val session: SessionEvents,
) : ViewModel() {
    /** null = 还在读本地 token。 */
    val loggedIn = auth.loggedIn.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var update by mutableStateOf<UpdateInfo?>(null); private set

    init {
        // 启动时检查更新（GitHub Releases latest）
        viewModelScope.launch { update = updates.check(manual = false).getOrNull() }
    }

    fun dismissUpdate() { update = null }
    fun skipUpdate() { update?.let { v -> viewModelScope.launch { updates.skip(v.version) } }; update = null }

    fun onSessionExpired() { viewModelScope.launch { auth.clearLocal(); account.clear() } }
}

@Composable
fun AppNavHost(deepLinks: DeepLinks? = null, vm: AppViewModel = hiltViewModel()) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    LaunchedEffect(Unit) {
        vm.session.expired.collect {
            vm.onSessionExpired()
            nav.goTo(Routes.Login)
        }
    }

    // 通知点击进入作业详情（MainActivity 收到 intent extra 后放进 deepLinks）
    if (deepLinks != null && loggedIn == true) {
        LaunchedEffect(deepLinks) {
            deepLinks.assignment.collect { id ->
                if (id != null) {
                    nav.navigate(Routes.AssignmentDetail(id)) { launchSingleTop = true }
                    deepLinks.consume()
                }
            }
        }
    }

    vm.update?.let { UpdateDialog(it, onSkip = vm::skipUpdate, onDismiss = vm::dismissUpdate) }

    // 起始页只在首次读到登录状态时决定一次；之后登录/注册引起的状态变化不能重建导航图
    // （否则注册后会跳过恢复码页）。
    val first = loggedIn ?: run { CenterLoading(); return }
    val start: Any = remember { if (first) Routes.Main else Routes.Login }

    NavHost(navController = nav, startDestination = start) {
        composable<Routes.Login> {
            LoginScreen(
                onLoggedIn = { nav.goTo(Routes.Main) },
                onRegister = { nav.navigate(Routes.Register) },
                onRecover = { nav.navigate(Routes.Recover) },
            )
        }
        composable<Routes.Register> {
            RegisterScreen(
                onBack = { nav.popBackStack() },
                onRegistered = { code -> nav.navigate(Routes.RecoveryCode(code)) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<Routes.Recover> {
            RecoverScreen(
                onBack = { nav.popBackStack() },
                onRecovered = { code -> nav.navigate(Routes.RecoveryCode(code, fromRecover = true)) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<Routes.RecoveryCode> { entry ->
            val r = entry.toRoute<Routes.RecoveryCode>()
            RecoveryCodeScreen(r.code, r.fromRecover, onConfirmed = { loggedIn ->
                nav.goTo(if (loggedIn) Routes.Main else Routes.Login)
            })
        }
        composable<Routes.Main> {
            FirstLaunchGuide(onShow = { nav.navigate(Routes.ReminderGuide) { launchSingleTop = true } })
            MainScreen(
                onImport = { nav.navigate(Routes.TimetableImport) },
                onManual = { nav.navigate(Routes.TimetableManual) },
                onOpenClass = { nav.navigate(Routes.ClassPage(it)) },
                onSettings = { nav.navigate(Routes.Settings) },
                onInvites = { nav.navigate(Routes.Invites) },
                onLoggedOut = { nav.goTo(Routes.Login) },
                onPoints = { nav.navigate(Routes.MyPoints) },
                onRules = { nav.navigate(Routes.PointsRules) },
                onReminderGuide = { nav.navigate(Routes.ReminderGuide) },
                onAdmin = { nav.navigate(Routes.Admin) { launchSingleTop = true } },
                onOpenAiJob = { s, manual -> nav.openAiJob(s, manual) },
                home = { HomeScreen(onOpen = { nav.navigate(Routes.AssignmentDetail(it)) }) },
            )
        }
        composable<Routes.TimetableImport> {
            ImportScreen(manual = false, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable<Routes.TimetableManual> {
            ImportScreen(manual = true, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable<Routes.ClassPage> {
            ClassPageScreen(
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack() },
                body = { classId ->
                    ClassBody(
                        classId = classId,
                        onOpen = { nav.navigate(Routes.AssignmentDetail(it)) },
                        onPublish = { nav.navigate(Routes.Publish(classId)) },
                    )
                },
                actions = { classId -> ClassReportMenu(classId) },
            )
        }
        composable<Routes.AssignmentDetail> {
            DetailScreen(
                onBack = { nav.popBackStack() },
                onOpenClass = { nav.navigate(Routes.ClassPage(it)) { launchSingleTop = true } },
                onOpenAssignment = { nav.navigate(Routes.AssignmentDetail(it)) },
                onSupplement = { c, a -> nav.navigate(Routes.Publish(c, assignmentId = a)) },
                onEditSource = { c, a, s -> nav.navigate(Routes.Publish(c, assignmentId = a, sourceId = s)) },
                onEditAssignment = { nav.navigate(Routes.EditAssignment(it)) },
            )
        }
        composable<Routes.Publish> { entry ->
            val r = entry.toRoute<Routes.Publish>()
            PublishScreen(
                onBack = { nav.popBackStack() },
                onDone = { id ->
                    // 补充/编辑回到原详情页；新发布（或作为补充发到别的作业）进入那条作业
                    if (r.assignmentId == id) nav.popBackStack()
                    else nav.navigate(Routes.AssignmentDetail(id)) { popUpTo<Routes.Publish> { inclusive = true } }
                },
            )
        }
        composable<Routes.EditAssignment> {
            // 保存后回到详情页（详情页 onResume 会重新加载）
            EditAssignmentScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable<Routes.MyPoints> { MyPointsScreen(onBack = { nav.popBackStack() }, onRules = { nav.navigate(Routes.PointsRules) }) }
        composable<Routes.PointsRules> { PointsRulesScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.ReminderGuide> { ReminderGuideScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Settings> { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Invites> { InvitesScreen(onBack = { nav.popBackStack() }) }
        // WS11 管理：入口只在管理员的「我的」页出现；路由层再用 AdminGate 拦一次（重新拉 /me，非管理员看不到内容）
        composable<Routes.Admin> {
            AdminGate(onBack = { nav.popBackStack() }) {
                AdminScreen(onBack = { nav.popBackStack() }, onOpenUser = { nav.navigate(Routes.AdminUserDetail(it)) })
            }
        }
        composable<Routes.AdminUserDetail> {
            AdminGate(onBack = { nav.popBackStack() }) { AdminUserScreen(onBack = { nav.popBackStack() }) }
        }
    }
    // WS10 后台识图：识别结束的 Snackbar（非主界面时）与「AI 识别」通知点击
    if (loggedIn == true) AiJobSnackbarOverlay(nav, deepLinks)
}

/** 清空返回栈后跳转（登录、登出、会话过期）。 */
fun NavHostController.goTo(route: Any) {
    navigate(route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** 通知点击带来的待打开作业 id / 后台识图任务 kind（MainActivity 写入，导航图消费）。 */
class DeepLinks {
    private val _assignment = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val assignment: kotlinx.coroutines.flow.StateFlow<Int?> = _assignment
    fun open(id: Int) { _assignment.value = id }
    fun consume() { _assignment.value = null }

    private val _aiKind = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val aiKind: kotlinx.coroutines.flow.StateFlow<String?> = _aiKind
    fun openAi(kind: String) { _aiKind.value = kind }
    fun consumeAi() { _aiKind.value = null }
}
