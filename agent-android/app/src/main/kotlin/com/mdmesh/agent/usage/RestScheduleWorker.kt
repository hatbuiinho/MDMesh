package com.mdmesh.agent.usage

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.mdmesh.core.usage.AppUsageManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class RestScheduleWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters,
    private val usage: AppUsageManager,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { usage.evaluateAndReport(); Result.success() } catch (_: Exception) { Result.retry() }
    companion object {
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork("rest-schedule-reconcile", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RestScheduleWorker>(15, TimeUnit.MINUTES).build())
            wm.enqueueUniqueWork("rest-schedule-startup", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RestScheduleWorker>().build())
        }
    }
}
