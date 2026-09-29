package com.mdmesh.agent.web

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mdmesh.core.web.WebAccessManager
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.WebAccessPolicy
import com.mdmesh.agent.BuildConfig
import java.net.URI

class AndroidWebAccessManager(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : WebAccessManager {
    override suspend fun apply(policy: WebAccessPolicy?): String = runCatching {
        check(dpm.isDeviceOwnerApp(context.packageName)) { "device owner required" }
        WebFilterConfig.save(context, policy)
        if (policy == null || policy.mode == "OFF") {
            dpm.setAlwaysOnVpnPackage(admin, null, false)
            context.stopService(Intent(context, WebFilterService::class.java))
        } else {
            require(policy.mode == "ALLOWLIST" || policy.mode == "BLOCKLIST") { "invalid mode" }
            require(policy.mode != "ALLOWLIST" || policy.domains.isNotEmpty()) { "empty allowlist" }
            // DNS-only split tunnel keeps application traffic on Android's kernel path; lockdown would
            // incorrectly block traffic outside the single routed DNS address.
            dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
            ContextCompat.startForegroundService(context, Intent(context, WebFilterService::class.java))
        }
        ConfigOutcome.APPLIED
    }.getOrElse { ConfigOutcome.failed(it.message ?: "web filter") }
}

internal object WebFilterConfig {
    private const val PREFS = "web_filter"
    fun save(context: Context, policy: WebAccessPolicy?) {
        val protectedHost = runCatching { URI(BuildConfig.MDM_BASE_URL).host }.getOrNull()
        val domains = policy?.domains.orEmpty().map(DomainMatcher::normalize).filter(String::isNotEmpty).toMutableSet()
        protectedHost?.let(DomainMatcher::normalize)?.takeIf(String::isNotEmpty)?.let {
            if (policy?.mode == "ALLOWLIST") domains.add(it) else domains.remove(it)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", policy?.mode ?: "OFF")
            .putStringSet("domains", domains).apply()
    }
    fun load(context: Context): Pair<String, Set<String>> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (p.getString("mode", "OFF") ?: "OFF") to (p.getStringSet("domains", emptySet()) ?: emptySet())
    }
}
