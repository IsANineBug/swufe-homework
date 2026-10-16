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
import com.swufe.homework.ui.auth.LoginScreen
import com.swufe.homework.ui.auth.RecoverScreen
import com.swufe.homework.ui.auth.RecoveryCodeScreen
import com.swufe.homework.ui.auth.RegisterScreen
import com.swufe.homework.ui.common.CenterLoading
import com.swufe.homework.ui.common.UpdateDialog
import com.swufe.homework.ui.main.MainScreen
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
fun AppNavHost(vm: AppViewModel = hiltViewModel()) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    LaunchedEffect(Unit) {
        vm.session.expired.collect {
            vm.onSessionExpired()
            nav.goTo(Routes.Login)
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
            MainScreen(
                onImport = { nav.navigate(Routes.TimetableImport) },
                onManual = { nav.navigate(Routes.TimetableManual) },
                onOpenClass = { nav.navigate(Routes.ClassPage(it)) },
                onSettings = { nav.navigate(Routes.Settings) },
                onInvites = { nav.navigate(Routes.Invites) },
                onLoggedOut = { nav.goTo(Routes.Login) },
            )
        }
        composable<Routes.TimetableImport> {
            ImportScreen(manual = false, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable<Routes.TimetableManual> {
            ImportScreen(manual = true, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
        }
        composable<Routes.ClassPage> {
            ClassPageScreen(onBack = { nav.popBackStack() }, onLeft = { nav.popBackStack() })
        }
        composable<Routes.Settings> { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable<Routes.Invites> { InvitesScreen(onBack = { nav.popBackStack() }) }
    }
}

/** 清空返回栈后跳转（登录、登出、会话过期）。 */
fun NavHostController.goTo(route: Any) {
    navigate(route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}
