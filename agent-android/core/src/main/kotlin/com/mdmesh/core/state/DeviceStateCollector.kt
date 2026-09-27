package com.mdmesh.core.state

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.mdmesh.core.power.PowerModeStore
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.proto.AgentDeviceStateDto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the compact device-state snapshot piggybacked on each check-in. */
@Singleton
class DeviceStateCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val powerModeStore: PowerModeStore,
    private val configStateStore: ConfigStateStore,
) : DeviceStateSource {
    override fun snapshot(): AgentDeviceStateDto {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val kiosk = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        return AgentDeviceStateDto(
            battery = batteryPercent(level, scale),
            charging = charging,
            locked = false, // best-effort; refined by event-driven sync when the transport lands
            kioskActive = kiosk,
            androidRelease = Build.VERSION.RELEASE ?: "",
            lastBootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            agentVersion = installedVersionName(),
            agentVersionCode = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            }.getOrNull(),
            agentSignatureChecksum = installedSignatureChecksum(),
            agentPackageName = context.packageName,
            powerMode = powerModeStore.get(),
            appliedConfigRevision = configStateStore.revision(),
        )
    }

    /** The actually-installed agent versionName (read from PackageManager — accurate after self-update). */
    private fun installedVersionName(): String? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installedSignatureChecksum(): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.singleOrNull() ?: return@runCatching null
        android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }.getOrNull()

    companion object {
        /** Pure: percent from raw level/scale, or -1 when scale is unknown. */
        fun batteryPercent(level: Int, scale: Int): Int =
            if (scale <= 0 || level < 0) -1 else (level * 100 / scale)
    }
}
