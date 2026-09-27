package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.sync.ConfigReapplyWorker
import com.mdmesh.policy.ApplicationAllowlist
import com.mdmesh.proto.EventType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Records app install/uninstall events into the telemetry [EventLog]. */
@AndroidEntryPoint
class PackageEventReceiver : BroadcastReceiver() {
    @Inject lateinit var configStateStore: ConfigStateStore
    @Inject lateinit var applicationAllowlist: ApplicationAllowlist

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart
        // ACTION_PACKAGE_ADDED fires on update too; EXTRA_REPLACING distinguishes a fresh install.
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> if (!replacing) {
                runCatching { EventLog(context).record(EventType.APP_INSTALLED, pkg) }
                // Close the WorkManager scheduling window: hide a newly installed launcher app
                // synchronously, then let the worker reconcile the complete package inventory.
                if (pkg != null) runCatching {
                    configStateStore.load()?.applications?.let { apps ->
                        applicationAllowlist.applyPackage(
                            apps.enforceAllowlist,
                            apps.allowedPackages.toSet(),
                            pkg,
                        )
                    }
                }.onFailure { Log.w(TAG, "immediate allowlist enforcement failed for $pkg", it) }
            }
            Intent.ACTION_PACKAGE_REMOVED ->
                if (!replacing) runCatching { EventLog(context).record(EventType.APP_UNINSTALLED, pkg) }
        }
        // A newly installed launcher app must not remain usable until the next periodic check-in.
        // Removal also prunes the set of packages whose hidden state MDMesh owns.
        runCatching { ConfigReapplyWorker.scheduleNow(context) }
    }

    private companion object { const val TAG = "PackageEventReceiver" }
}
