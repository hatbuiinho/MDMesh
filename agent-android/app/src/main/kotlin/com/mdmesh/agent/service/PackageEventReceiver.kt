package com.mdmesh.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.core.sync.ConfigReapplyWorker
import com.mdmesh.proto.EventType

/** Records app install/uninstall events into the telemetry [EventLog]. */
class PackageEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart
        // ACTION_PACKAGE_ADDED fires on update too; EXTRA_REPLACING distinguishes a fresh install.
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED ->
                if (!replacing) runCatching { EventLog(context).record(EventType.APP_INSTALLED, pkg) }
            Intent.ACTION_PACKAGE_REMOVED ->
                if (!replacing) runCatching { EventLog(context).record(EventType.APP_UNINSTALLED, pkg) }
        }
        // A newly installed launcher app must not remain usable until the next periodic check-in.
        // Removal also prunes the set of packages whose hidden state MDMesh owns.
        runCatching { ConfigReapplyWorker.scheduleNow(context) }
    }
}
