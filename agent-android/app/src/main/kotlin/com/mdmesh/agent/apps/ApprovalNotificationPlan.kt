package com.mdmesh.agent.apps

internal data class ApprovalNotificationPlan(
    val ready: List<AppInstallRequest>, val post: List<AppInstallRequest>, val cancel: Set<String>,
)

internal fun approvalNotificationPlan(rows: List<AppInstallRequest>, seen: Set<String>, previousActive: Set<String>): ApprovalNotificationPlan {
    val ready = rows.filter { it.status == "approved" && it.installedAt == null }.distinctBy { it.id }
    val ids = ready.map { it.id.toString() }.toSet()
    return ApprovalNotificationPlan(ready, ready.filter { it.id.toString() !in seen }, previousActive - ids)
}
