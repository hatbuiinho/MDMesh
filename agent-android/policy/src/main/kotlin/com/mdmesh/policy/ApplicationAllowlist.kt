package com.mdmesh.policy

/** Pure calculation kept separate from DevicePolicyManager calls for deterministic tests. */
data class ApplicationAllowlistPlan(
    val hide: Set<String>,
    val restore: Set<String>,
    val retainManaged: Set<String>,
)

object ApplicationAllowlistPlanner {
    fun shouldHideNewPackage(
        enabled: Boolean,
        packageName: String,
        allowedPackages: Set<String>,
        protectedPackages: Set<String>,
    ): Boolean = enabled && packageName !in allowedPackages && packageName !in protectedPackages

    fun shouldHidePackage(
        enabled: Boolean,
        packageName: String,
        launchable: Boolean,
        allowedPackages: Set<String>,
        protectedPackages: Set<String>,
    ): Boolean = launchable && shouldHideNewPackage(enabled, packageName, allowedPackages, protectedPackages)

    fun plan(
        enabled: Boolean,
        launchablePackages: Set<String>,
        allowedPackages: Set<String>,
        protectedPackages: Set<String>,
        managedHiddenPackages: Set<String>,
    ): ApplicationAllowlistPlan {
        val desiredHidden = if (enabled) {
            // A hidden package no longer appears in PackageManager's launcher query. Keep packages
            // we previously hid in the desired set until they become allowed/protected (or the
            // Android-backed implementation establishes that they were uninstalled). Without this
            // union, the first full reconciliation after applyPackage() immediately restores the
            // package it just hid.
            (launchablePackages + managedHiddenPackages) - allowedPackages - protectedPackages
        } else {
            emptySet()
        }
        return ApplicationAllowlistPlan(
            hide = desiredHidden - managedHiddenPackages,
            restore = managedHiddenPackages - desiredHidden,
            retainManaged = managedHiddenPackages intersect desiredHidden,
        )
    }
}

data class ApplicationAllowlistResult(
    val supported: Boolean,
    val hidden: Int = 0,
    val restored: Int = 0,
    val skipped: Map<String, String> = emptyMap(),
) {
    /**
     * An enabled allowlist is a security boundary: unsupported or partially-applied enforcement
     * must keep config.apply open instead of acknowledging a revision the device did not reach.
     * When the policy is disabled, an unsupported implementation has no restriction to enforce.
     */
    fun outcome(enforcementRequired: Boolean = false): String = when {
        skipped.isNotEmpty() -> "failed: app allowlist incomplete; skipped=${skipped.size}"
        !supported && enforcementRequired -> "failed: app allowlist requires Device Owner"
        !supported -> "unsupported"
        else -> "applied: hidden=$hidden, restored=$restored"
    }
}

fun interface ApplicationAllowlist {
    fun apply(enabled: Boolean, allowedPackages: Set<String>): ApplicationAllowlistResult

    /** Enforce a package that has just been installed, before the deferred full reconciliation runs. */
    fun applyPackage(
        enabled: Boolean,
        allowedPackages: Set<String>,
        packageName: String,
    ): ApplicationAllowlistResult = apply(enabled, allowedPackages)
}
