package com.mdmesh.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

enum class SuspensionReason { APPLICATION_ALLOWLIST, APP_USAGE }

/** Coordinates every MDMesh feature that controls Android's single package-suspended bit. */
interface PackageSuspensionManager {
    fun setReason(packageName: String, reason: SuspensionReason, active: Boolean): Boolean
    fun packagesFor(reason: SuspensionReason): Set<String>
    fun isSuspended(packageName: String): Boolean
}

class DeviceOwnerPackageSuspensionManager(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : PackageSuspensionManager {
    private val prefs = context.getSharedPreferences("mdm_package_suspension", Context.MODE_PRIVATE)

    @Synchronized
    override fun setReason(packageName: String, reason: SuspensionReason, active: Boolean): Boolean {
        if (active && isProtected(packageName)) return false
        val reasons = SuspensionReason.entries.associateWith { load(it).toMutableSet() }.toMutableMap()
        val owned = reasons.getValue(reason)
        val changed = if (active) owned.add(packageName) else owned.remove(packageName)
        if (!changed) return if (active) ensureSuspended(packageName) else true

        val shouldSuspend = reasons.values.any { packageName in it }
        val applied = setPlatformSuspended(packageName, shouldSuspend)
        if (!applied) {
            if (active) owned.remove(packageName) else owned.add(packageName)
            return false
        }
        persist(reason, owned)
        return true
    }

    @Synchronized
    override fun packagesFor(reason: SuspensionReason): Set<String> = load(reason).toSet()

    override fun isSuspended(packageName: String): Boolean = runCatching {
        context.packageManager.isPackageSuspended(packageName)
    }.getOrDefault(false)

    private fun ensureSuspended(packageName: String): Boolean =
        isSuspended(packageName) || setPlatformSuspended(packageName, true)

    private fun setPlatformSuspended(packageName: String, suspended: Boolean): Boolean = runCatching {
        dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended).isEmpty() &&
            isSuspended(packageName) == suspended
    }.getOrDefault(false)

    private fun load(reason: SuspensionReason): Set<String> =
        prefs.getStringSet(reason.name, emptySet()).orEmpty()

    private fun persist(reason: SuspensionReason, packages: Set<String>) {
        // commit is intentional: ownership must reach disk before another feature can remove a reason.
        prefs.edit().putStringSet(reason.name, packages).commit()
    }

    private fun isProtected(packageName: String): Boolean {
        if (packageName == context.packageName || packageName == SYSTEM_UI) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return packageName == launcher
    }

    private companion object { const val SYSTEM_UI = "com.android.systemui" }
}
