package com.mdmesh.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

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
        val plan = ApplicationAllowlistPlanner.plan(
            enabled = enabled,
            launchablePackages = launchablePackages(),
            allowedPackages = allowedPackages,
            protectedPackages = protectedPackages(),
            managedHiddenPackages = managed,
        )
        val skipped = linkedMapOf<String, String>()
        var restored = 0
        for (pkg in plan.restore.sorted()) {
            if (!isInstalled(pkg)) {
                managed.remove(pkg)
                continue
            }
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                .onSuccess { managed.remove(pkg); restored++ }
                .onFailure { skipped[pkg] = it.message ?: it.javaClass.simpleName }
        }

        var hidden = 0
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
        prefs.edit().putStringSet(KEY_HIDDEN, managed).apply()
        return ApplicationAllowlistResult(true, hidden, restored, skipped)
    }

    override fun applyPackage(
        enabled: Boolean,
        allowedPackages: Set<String>,
        packageName: String,
    ): ApplicationAllowlistResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return ApplicationAllowlistResult(false)
        // PACKAGE_ADDED is delivered after PackageManager has committed the package. Query this
        // package directly: a broad launcher query can briefly return a stale cached result here.
        val launchable = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
        if (!ApplicationAllowlistPlanner.shouldHidePackage(
                enabled,
                packageName,
                launchable,
                allowedPackages,
                protectedPackages(),
            )) return ApplicationAllowlistResult(true)

        val managed = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toMutableSet()
        val alreadyHidden = runCatching { dpm.isApplicationHidden(admin, packageName) }.getOrDefault(false)
        if (alreadyHidden) return ApplicationAllowlistResult(true)

        return runCatching { dpm.setApplicationHidden(admin, packageName, true) }
            .fold(
                onSuccess = { changed ->
                    val hidden = changed || runCatching {
                        dpm.isApplicationHidden(admin, packageName)
                    }.getOrDefault(false)
                    if (hidden) {
                        managed.add(packageName)
                        prefs.edit().putStringSet(KEY_HIDDEN, managed).apply()
                        ApplicationAllowlistResult(true, hidden = 1)
                    } else {
                        ApplicationAllowlistResult(
                            true,
                            skipped = mapOf(packageName to "Android refused to hide the package"),
                        )
                    }
                },
                onFailure = {
                    ApplicationAllowlistResult(
                        true,
                        skipped = mapOf(packageName to (it.message ?: it.javaClass.simpleName)),
                    )
                },
            )
    }

    private fun launchablePackages(): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
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

    private fun isInstalled(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val KEY_HIDDEN = "hidden_by_mdmesh"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
