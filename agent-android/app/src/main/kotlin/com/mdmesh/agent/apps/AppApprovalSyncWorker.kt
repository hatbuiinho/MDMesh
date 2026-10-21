package com.mdmesh.agent.apps

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Durable fallback independent of the admin session and of opening the requests screen. */
class AppApprovalSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (AppApprovalNotifications.scope(applicationContext) != null)
            AndroidAppRequestsRepository(applicationContext).requests(false, 1)
        else AppApprovalNotifications.update(applicationContext, "", emptyList())
        Result.success()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.retry()
    }
    companion object {
        private val constraints get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("app-approval-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AppApprovalSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
        }
        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("app-approval-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AppApprovalSyncWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
