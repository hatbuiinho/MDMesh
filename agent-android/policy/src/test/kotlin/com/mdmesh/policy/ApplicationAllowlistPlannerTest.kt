package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationAllowlistPlannerTest {
    @Test fun hidesOnlyLaunchableDisallowedPackages() {
        val plan = ApplicationAllowlistPlanner.plan(
            enabled = true,
            launchablePackages = setOf("allowed", "blocked", "agent", "home"),
            allowedPackages = setOf("allowed"),
            protectedPackages = setOf("agent", "home"),
            managedHiddenPackages = emptySet(),
        )
        assertEquals(setOf("blocked"), plan.hide)
        assertEquals(emptySet<String>(), plan.restore)
    }

    @Test fun changingTheAllowlistRestoresOnlyPackagesManagedByMdmesh() {
        val plan = ApplicationAllowlistPlanner.plan(
            enabled = true,
            launchablePackages = setOf("newlyAllowed", "stillBlocked", "externallyHidden"),
            allowedPackages = setOf("newlyAllowed"),
            protectedPackages = emptySet(),
            managedHiddenPackages = setOf("newlyAllowed", "stillBlocked", "uninstalled"),
        )
        assertEquals(setOf("externallyHidden"), plan.hide)
        assertEquals(setOf("newlyAllowed", "uninstalled"), plan.restore)
        assertEquals(setOf("stillBlocked"), plan.retainManaged)
    }

    @Test fun disablingRestoresEveryPackageManagedByMdmesh() {
        val plan = ApplicationAllowlistPlanner.plan(
            enabled = false,
            launchablePackages = setOf("a", "b"),
            allowedPackages = emptySet(),
            protectedPackages = emptySet(),
            managedHiddenPackages = setOf("a", "old"),
        )
        assertEquals(emptySet<String>(), plan.hide)
        assertEquals(setOf("a", "old"), plan.restore)
    }
}
