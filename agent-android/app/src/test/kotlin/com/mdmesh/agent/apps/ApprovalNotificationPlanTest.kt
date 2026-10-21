package com.mdmesh.agent.apps

import org.junit.Assert.*
import org.junit.Test

class ApprovalNotificationPlanTest {
    private val row = AppInstallRequest(1, "com.example.app", "Work app", status = "approved", reason = "Work")
    @Test fun onlyApprovedUninstalledAppsNotify() {
        val rows = listOf(row, row.copy(id = 2, status = "pending"), row.copy(id = 3, status = "rejected"),
            row.copy(id = 4, status = "revoked"), row.copy(id = 5, status = "stale"), row.copy(id = 6, installedAt = 123))
        val plan = approvalNotificationPlan(rows, emptySet(), emptySet())
        assertEquals(listOf(row), plan.post); assertEquals(1, plan.ready.size)
    }
    @Test fun repeatedSyncAndDismissedNotificationsDoNotNotifyAgain() {
        val plan = approvalNotificationPlan(listOf(row), setOf("1"), setOf("1"))
        assertTrue(plan.post.isEmpty()); assertEquals(listOf(row), plan.ready)
    }
    @Test fun revocationInstallationAndMissingRequestsCancelOldNotification() {
        val plan = approvalNotificationPlan(listOf(row.copy(status = "revoked"), row.copy(id = 2, installedAt = 123)), setOf("1", "2", "3"), setOf("1", "2", "3"))
        assertEquals(setOf("1", "2", "3"), plan.cancel); assertTrue(plan.ready.isEmpty())
    }
    @Test fun newRequestForSamePackageStillNotifies() {
        assertEquals(listOf(row.copy(id = 2)), approvalNotificationPlan(listOf(row.copy(id = 2)), setOf("1"), emptySet()).post)
    }
    @Test fun disabledPermissionCanRetryUntilSuccessfullyPosted() {
        assertEquals(listOf(row), approvalNotificationPlan(listOf(row), emptySet(), setOf("1")).post)
    }
}
