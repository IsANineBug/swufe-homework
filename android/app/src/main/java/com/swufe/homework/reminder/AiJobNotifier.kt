package com.swufe.homework.reminder

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.swufe.homework.data.repo.AiJobState
import com.swufe.homework.data.repo.AiJobStatus
import com.swufe.homework.data.repo.AiJobTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台识图结束时：App 在后台（ProcessLifecycleOwner 未到 STARTED）就发「AI 识别」渠道的系统通知；
 * 在前台时由界面弹 Snackbar（MainScreen / AiJobSnackbarOverlay），这里不发。
 * 同时负责 App 启动时恢复未完成的任务（[start] 在 Application.onCreate 调用）。
 */
@Singleton
class AiJobNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tracker: AiJobTracker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch { tracker.restore() }
        scope.launch {
            tracker.events.collect { s ->
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (!foreground) notify(s)
            }
        }
        // 结果被取走/放弃、或同种任务重新开始后，撤掉旧通知
        scope.launch {
            tracker.jobs.collect { list ->
                AiJobTracker.TRACKED_KINDS.filter { k -> list.none { it.kind == k && it.finished } }
                    .forEach { Notifications.cancelAi(ctx, it) }
            }
        }
    }

    private fun notify(s: AiJobState) {
        val what = aiJobLabel(s.kind)
        val (title, body) = when (s.status) {
            AiJobStatus.Done -> "${what}识别好了" to "点开去确认"
            AiJobStatus.Failed -> "${what}识别失败" to "${s.message}，可以手动添加"
            AiJobStatus.Timeout -> "${what}识别超时" to AiJobTracker.TIMEOUT_MESSAGE
            AiJobStatus.Running -> return
        }
        Notifications.showAiResult(ctx, s.kind, title, body)
    }
}

/** 「课表」/「作业」：状态条、通知、Snackbar 共用。 */
fun aiJobLabel(kind: String) = if (kind == AiJobTracker.PARSE_TIMETABLE) "课表" else "作业"
