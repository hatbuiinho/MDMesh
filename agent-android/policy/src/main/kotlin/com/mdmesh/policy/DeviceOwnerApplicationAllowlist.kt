package com.mdmesh.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Reversible app allowlist for dedicated devices. It manages only launcher-visible packages and
 * remembers ownership of each hide operation, so disabling/changing the policy never unhides an app
 * hidden by the OEM, another administrator, or the user.
 */
class DeviceOwnerApplicationAllowlist(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : ApplicationAllowlist {
    private val prefs = context.getSharedPreferences("mdm_app_allowlist", Context.MODE_PRIVATE)

    override fun apply(enabled: Boolean, allowedPackages: Set<String>): ApplicationAllowlistResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return ApplicationAllowlistResult(false)

        val managed = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()
        val suspended = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty().toMutableSet()
        // Older agents could forget ownership when Android rejected an unhide operation. While the
        // policy is off, recover every package hidden by this device owner so those devices converge
        // instead of remaining permanently hidden after the switch is cleared.
        if (!enabled) managed.addAll(hiddenByThisAdminPackages())
        // The pure planner deliberately retains packages already hidden by MDMesh because hidden
        // apps disappear from launcher queries. Prune packages that really no longer exist, and
        // discard stale ownership if another actor has made a managed package visible again. The
        // latter is important: otherwise the planner retains the package forever and never re-hides it.
        managed.removeAll { !isInstalled(it) }
        suspended.removeAll { !isInstalled(it) }
        if (enabled) managed.removeAll { pkg ->
            !runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
        }
        val launchable = launchablePackages()
        val protected = protectedPackages()
        val plan = ApplicationAllowlistPlanner.plan(
            enabled = enabled,
            launchablePackages = launchable,
            allowedPackages = allowedPackages,
            protectedPackages = protected,
            managedHiddenPackages = managed,
        )
        val skipped = linkedMapOf<String, String>()
        val desiredSuspended = if (enabled) {
            (launchable + managed + suspended) - allowedPackages - protected
        } else {
            emptySet()
        }
        for (pkg in (suspended - desiredSuspended).sorted()) {
            if (!isInstalled(pkg)) {
                suspended.remove(pkg)
                continue
            }
            val failure = setSuspended(pkg, false)
            if (failure == null && !isSuspended(pkg)) {
                suspended.remove(pkg)
            } else {
                skipped[pkg] = failure ?: "Package remains suspended after restore"
            }
        }
        var restored = 0
        for (pkg in plan.restore.sorted()) {
            if (!isInstalled(pkg)) {
                managed.remove(pkg)
                continue
            }
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                .onSuccess { changed ->
                    val stillHidden = runCatching {
                        dpm.isApplicationHidden(admin, pkg)
                    }.getOrDefault(!changed)
                    if (!stillHidden) {
                        managed.remove(pkg)
                        restored++
                    } else {
                        skipped[pkg] = "Android refused to restore the package"
                    }
                }
                .onFailure { skipped[pkg] = it.message ?: it.javaClass.simpleName }
        }

        var hidden = 0
        for (pkg in (desiredSuspended - suspended).sorted()) {
            // Respect suspension owned by another policy/admin; do not claim it for restoration.
            if (isSuspended(pkg)) continue
            val failure = setSuspended(pkg, true)
            if (failure == null && isSuspended(pkg)) {
                suspended.add(pkg)
            } else {
                skipped[pkg] = failure ?: "Package remains launchable after suspension"
            }
        }
        for (pkg in plan.hide.sorted()) {
            // Do not take ownership of packages that were already hidden outside MDMesh.
            val alreadyHidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            if (alreadyHidden) continue
            runCatching { dpm.setApplicationHidden(admin, pkg, true) }
                .onSuccess { changed ->
                    if (changed || runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)) {
                        managed.add(pkg)
                        hidden++
                    } else {
                        skipped[pkg] = "Android refused to hide the package"
                    }
                }
                .onFailure { skipped[pkg] = it.message ?: it.javaClass.simpleName }
        }

        // Verify the converged state instead of trusting the mutator's return value or our cached
        // ownership. A successful config revision must mean every launcher-visible disallowed app
        // is actually hidden and every package being restored is actually visible.
        if (enabled) {
            val expectedHidden = (launchable + managed + suspended) - allowedPackages - protected
            for (pkg in expectedHidden.sorted()) {
                val actuallyHidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
                if (!actuallyHidden) skipped.putIfAbsent(pkg, "Package remains visible after enforcement")
                if (!isSuspended(pkg)) skipped.putIfAbsent(pkg, "Package remains launchable after enforcement")
            }
        } else {
            for (pkg in managed.sorted()) {
                val stillHidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(true)
                if (stillHidden) skipped.putIfAbsent(pkg, "Package remains hidden after restore")
            }
        }
        prefs.edit().putStringSet(KEY_HIDDEN, managed).apply()
        prefs.edit().putStringSet(KEY_SUSPENDED, suspended).apply()
        return ApplicationAllowlistResult(true, hidden, restored, skipped)
    }

    override fun applyPackage(
        enabled: Boolean,
        allowedPackages: Set<String>,
        packageName: String,
    ): ApplicationAllowlistResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return ApplicationAllowlistResult(false)
        // Do not gate the PACKAGE_ADDED fast path on launcher discovery. PackageManager may not
        // resolve the new app's launcher activity yet (and Leanback/OEM launchers may not expose a
        // CATEGORY_LAUNCHER activity at all). An enabled allowlist is deny-by-default for every new
        // package; existing packages remain scoped by the full reconciliation's launcher inventory.
        if (!ApplicationAllowlistPlanner.shouldHideNewPackage(
                enabled,
                packageName,
                allowedPackages,
                protectedPackages(),
            )) return ApplicationAllowlistResult(true)

        val managed = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()
        val suspended = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty().toMutableSet()
        val alreadyHidden = runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)
        val alreadySuspended = isSuspended(packageName)
        val failures = mutableListOf<String>()
        if (!alreadySuspended) {
            val failure = setSuspended(packageName, true)
            if (failure == null && isSuspended(packageName)) suspended.add(packageName)
            else failures += failure ?: "Android refused to suspend the package"
        }
        var hiddenCount = 0
        if (!alreadyHidden) {
            runCatching { dpm.setApplicationHidden(admin, packageName, true) }
                .onSuccess { changed ->
                    if (changed || runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)) {
                        managed.add(packageName)
                        hiddenCount = 1
                    } else failures += "Android refused to hide the package"
                }
                .onFailure { failures += it.message ?: it.javaClass.simpleName }
        }
        prefs.edit()
            .putStringSet(KEY_HIDDEN, managed)
            .putStringSet(KEY_SUSPENDED, suspended)
            .apply()
        return ApplicationAllowlistResult(
            supported = true,
            hidden = hiddenCount,
            skipped = failures.takeIf { it.isNotEmpty() }
                ?.let { mapOf(packageName to it.joinToString("; ")) }
                .orEmpty(),
        )
    }

    private fun launchablePackages(): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun hiddenByThisAdminPackages(): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .asSequence()
            .map { it.packageName }
            .filter { pkg -> runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false) }
            .toSet()
    }.getOrDefault(emptySet())

    private fun protectedPackages(): Set<String> {
        val protected = linkedSetOf(context.packageName, SYSTEM_UI)
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )?.activityInfo?.packageName
        }.getOrNull()?.let(protected::add)
        return protected
    }

    private fun isInstalled(pkg: String): Boolean {
        // Some PackageManager implementations omit a DPC-hidden package from ordinary lookups.
        // DPM is authoritative for packages hidden by this administrator.
        if (runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)) return true
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
            true
        }.getOrDefault(false)
    }

    private fun isSuspended(pkg: String): Boolean = runCatching {
        context.packageManager.isPackageSuspended(pkg)
    }.getOrDefault(false)

    /** @return null on success, otherwise the platform's refusal/error. */
    private fun setSuspended(pkg: String, value: Boolean): String? = runCatching {
        val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), value)
        if (failed.isEmpty()) null else "Android refused to ${if (value) "suspend" else "restore"} the package"
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private companion object {
        const val KEY_HIDDEN = "hidden_by_mdmesh"
        const val KEY_SUSPENDED = "suspended_by_mdmesh_allowlist"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
