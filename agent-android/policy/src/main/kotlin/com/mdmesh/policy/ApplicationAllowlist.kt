package com.mdmesh.policy

/** Pure calculation kept separate from DevicePolicyManager calls for deterministic tests. */
data class ApplicationAllowlistPlan(
    val hide: Set<String>,
    val restore: Set<String>,
    val retainManaged: Set<String>,
)

object ApplicationAllowlistPlanner {
    fun shouldHidePackage(
        enabled: Boolean,
        packageName: String,
        launchable: Boolean,
        allowedPackages: Set<String>,
        protectedPackages: Set<String>,
    ): Boolean = enabled && launchable && packageName !in allowedPackages && packageName !in protectedPackages

    fun plan(
        enabled: Boolean,
        launchablePackages: Set<String>,
        allowedPackages: Set<String>,
        protectedPackages: Set<String>,
        managedHiddenPackages: Set<String>,
    ): ApplicationAllowlistPlan {
        val desiredHidden = if (enabled) {
            launchablePackages - allowedPackages - protectedPackages
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
    fun outcome(): String = when {
        !supported -> "unsupported"
        skipped.isEmpty() -> "applied: hidden=$hidden, restored=$restored"
        else -> "applied: hidden=$hidden, restored=$restored, skipped=${skipped.size}"
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
