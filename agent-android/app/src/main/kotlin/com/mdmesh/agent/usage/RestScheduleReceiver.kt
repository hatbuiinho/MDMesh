package com.mdmesh.agent.usage

import android.content.*
import com.mdmesh.core.usage.AppUsageManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class RestScheduleReceiver : BroadcastReceiver() {
    @Inject lateinit var usage: AppUsageManager
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { usage.evaluateAndReport() }
            catch (e: Exception) { android.util.Log.w("RestSchedule", "Local reconcile failed", e) }
            finally { pending.finish() }
        }
    }
}
