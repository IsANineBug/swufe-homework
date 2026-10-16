package com.swufe.homework.reminder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.swufe.homework.data.api.ApiException
import java.util.concurrent.TimeUnit

private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** 定期 /sync（每 4 小时，联网时），并在打开 App、开机后各补一次。未登录时什么也不做。 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = applicationContext.reminderDeps().reminderSync().sync()
        val e = r.exceptionOrNull() as? ApiException
        return when {
            r.isSuccess -> Result.success()
            e?.code == "network" && runAttemptCount < 3 -> Result.retry()
            else -> Result.success() // 鉴权失败等由前台处理，不在后台反复重试
        }
    }

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val ONCE = "sync-once"

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS).setConstraints(NETWORK).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runOnce(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(NETWORK).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** 把完成状态写回服务器（通知栏「标记完成」用；断网时联网后自动重试）。 */
class DoneWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt(KEY_ID, -1).takeIf { it >= 0 } ?: return Result.failure()
        val done = inputData.getBoolean(KEY_DONE, true)
        val r = applicationContext.reminderDeps().reminderSync().pushDone(id, done)
        val e = r.exceptionOrNull() as? ApiException
        return when {
            r.isSuccess -> Result.success()
            e == null || e.code == "network" || e.http >= 500 -> if (runAttemptCount < 10) Result.retry() else Result.failure()
            else -> Result.failure() // 404（作业已删除/合并）等，放弃
        }
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_DONE = "done"

        fun enqueue(ctx: Context, id: Int, done: Boolean) {
            val req = OneTimeWorkRequestBuilder<DoneWorker>()
                .setConstraints(NETWORK)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ID to id, KEY_DONE to done))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("done-$id", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
