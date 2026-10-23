package com.swufe.homework

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.swufe.homework.data.repo.ReminderSync
import com.swufe.homework.reminder.Notifications
import com.swufe.homework.ui.nav.AppNavHost
import com.swufe.homework.ui.nav.DeepLinks
import com.swufe.homework.ui.theme.SwufeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 单 Activity；所有页面在 [AppNavHost] 里。通知点击带 assignment_id 进入作业详情，带 ai_job_kind 进识图确认页。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var reminderSync: ReminderSync
    private val deepLinks = DeepLinks()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { SwufeTheme { AppNavHost(deepLinks) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // 前台打开时同步一次（未登录时 sync 直接返回）
        lifecycleScope.launch { reminderSync.sync() }
    }

    private fun handle(i: Intent?) {
        val id = i?.getIntExtra(Notifications.EXTRA_ASSIGNMENT_ID, -1) ?: -1
        if (id >= 0) deepLinks.open(id)
        i?.getStringExtra(Notifications.EXTRA_AI_KIND)?.let { deepLinks.openAi(it) }
    }
}
