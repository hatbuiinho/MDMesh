package com.mdmesh.agent.web
import android.content.Context
import androidx.work.*

class NetworkRequestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val client = NetworkAccessClient(applicationContext)
        return try {
            val path = client.devicePath()
            for (row in NetworkAccessStore.outbox(applicationContext)) {
                // A policy change is a terminal user-visible error, not a silent widening of scope.
                if (row.optString("policyHash") != WebFilterConfig.load(applicationContext).policyHash) {
                    row.put("error", "Chính sách đã thay đổi. Gửi lại từ sự kiện mới.")
                    NetworkAccessStore.sent(applicationContext, row.getString("key"))
                    NetworkAccessStore.recordFailure(applicationContext, row)
                    continue
                }
                try {
                    val result = client.device(path, row)
                    result.optJSONArray("data")?.let { NetworkAccessStore.requests(applicationContext, it) }
                } catch (e: NetworkAccessRejected) {
                    row.put("error", e.message)
                    NetworkAccessStore.recordFailure(applicationContext, row)
                }
                NetworkAccessStore.sent(applicationContext, row.getString("key"))
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
    companion object {
        fun enqueueIfPending(context: Context) {
            if (NetworkAccessStore.outbox(context).isNotEmpty()) enqueue(context)
        }
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("network-access-requests", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<NetworkRequestWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
