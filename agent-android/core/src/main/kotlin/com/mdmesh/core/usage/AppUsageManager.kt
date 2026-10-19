package com.mdmesh.core.usage

import com.mdmesh.proto.AppUsageDailyReport
import com.mdmesh.proto.AppUsagePolicy

interface AppUsageManager {
    fun currentPolicy(): AppUsagePolicy? = null
    fun blockedReason(packageName: String): String? = null
    /** Persist desired policy and converge suspension state. */
    fun apply(policy: AppUsagePolicy?): String
    /** Re-evaluate time/window limits and return aggregate reports for check-in. */
    fun evaluateAndReport(): List<AppUsageDailyReport>
}

object UnsupportedAppUsageManager : AppUsageManager {
    override fun apply(policy: AppUsagePolicy?) = if (policy == null) "applied" else "unsupported"
    override fun evaluateAndReport() = emptyList<AppUsageDailyReport>()
}
