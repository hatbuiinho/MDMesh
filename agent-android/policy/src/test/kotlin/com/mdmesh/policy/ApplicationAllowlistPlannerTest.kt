package com.mdmesh.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationAllowlistPlannerTest {
    @Test fun newlyInstalledLaunchablePackageIsHiddenUnlessAllowedOrProtected() {
        assertEquals(true, ApplicationAllowlistPlanner.shouldHidePackage(
            true, "com.example.new", true, setOf("com.example.allowed"), setOf("com.mdmesh.agent"),
        ))
        assertEquals(false, ApplicationAllowlistPlanner.shouldHidePackage(
            true, "com.example.allowed", true, setOf("com.example.allowed"), emptySet(),
        ))
        assertEquals(false, ApplicationAllowlistPlanner.shouldHidePackage(
            true, "com.mdmesh.agent", true, emptySet(), setOf("com.mdmesh.agent"),
        ))
        assertEquals(false, ApplicationAllowlistPlanner.shouldHidePackage(
            false, "com.example.new", true, emptySet(), emptySet(),
        ))
    }

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
