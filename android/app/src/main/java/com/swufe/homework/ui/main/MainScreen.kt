package com.swufe.homework.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.swufe.homework.ui.me.MeScreen
import com.swufe.homework.ui.nav.MainTab
import com.swufe.homework.ui.timetable.TimetableScreen

/**
 * 底部导航外壳。[home] 由 WS5 提供首页（/feed）；WS4 默认放占位。
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
    home: @Composable () -> Unit = { HomePlaceholder() },
) {
    // WS5 接入首页后默认打开首页（作业列表）
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    Scaffold(
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
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                MainTab.Home -> home()
                MainTab.Timetable -> TimetableScreen(onImport, onManual, onOpenClass)
                MainTab.Me -> MeScreen(onSettings, onInvites, onLoggedOut, onPoints = onPoints, onRules = onRules, onReminderGuide = onReminderGuide)
            }
        }
    }
}

@Composable
private fun HomePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("首页作业列表开发中（WS5）", color = MaterialTheme.colorScheme.outline)
    }
}
