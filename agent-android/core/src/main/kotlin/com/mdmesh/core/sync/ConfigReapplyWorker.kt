package com.mdmesh.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mdmesh.core.config.ConfigApplier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * After boot / self-update, re-assert the last fully-applied desired-state document BEFORE the first check-in,
 * so restrictions and kiosk are back even if the network is not. No network constraint; runs once.
 */
@HiltWorker
class ConfigReapplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val applier: ConfigApplier,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { applier.reapplyPersisted() }
            .onFailure { Log.w(TAG, "config re-apply failed", it) }
        return Result.success() // never retry-storm: the next check-in reconciles anyway
    }

    companion object {
        private const val TAG = "ConfigReapplyWorker"
        fun scheduleNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                // A package broadcast must not be discarded behind an older queued reconciliation.
                "config-reapply", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ConfigReapplyWorker>().build(),
            )
        }

        /**
         * PackageManager can briefly report an incomplete launcher inventory after PACKAGE_ADDED.
         * Reconcile once more after it settles so a package missed by the synchronous fast path
         * cannot remain usable until the next periodic check-in.
         */
        fun scheduleAfterPackageSettles(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConfigReapplyWorker>()
                .setInitialDelay(PACKAGE_SETTLE_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "config-reapply-package-settle", ExistingWorkPolicy.REPLACE, request,
            )
        }

        private const val PACKAGE_SETTLE_DELAY_SECONDS = 5L
    }
}
