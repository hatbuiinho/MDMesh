package com.mdmesh.agent.web

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mdmesh.core.web.WebAccessManager
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.WebAccessPolicy
import java.net.URI

class AndroidWebAccessManager(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val serverConfig: ServerConfigStore,
) : WebAccessManager {
    override suspend fun apply(policy: WebAccessPolicy?): String = runCatching {
        check(dpm.isDeviceOwnerApp(context.packageName)) { "device owner required" }
        val protectedHost = runCatching { URI(serverConfig.baseUrl()).host }.getOrNull()
        WebFilterConfig.save(context, policy, protectedHost)
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
    fun save(context: Context, policy: WebAccessPolicy?, protectedHost: String?) {
        val domains = normalized(policy?.domains.orEmpty())
        val protected = normalized(listOfNotNull(protectedHost))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", policy?.mode ?: "OFF")
            .putStringSet("domains", domains)
            .putStringSet("protectedDomains", protected)
            .apply()
    }
    fun load(context: Context): WebFilterPolicy {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WebFilterPolicy(
            p.getString("mode", "OFF") ?: "OFF",
            p.getStringSet("domains", emptySet()) ?: emptySet(),
            p.getStringSet("protectedDomains", emptySet()) ?: emptySet(),
        )
    }

    internal fun normalized(domains: Collection<String>): Set<String> = domains
        .asSequence().map(DomainMatcher::normalize).filter(String::isNotEmpty).toSet()
}

internal data class WebFilterPolicy(
    val mode: String,
    val domains: Set<String>,
    val protectedDomains: Set<String>,
) {
    private val matcher = DomainMatcher(domains)
    private val protectedMatcher = DomainMatcher(protectedDomains)

    fun blocks(host: String): Boolean {
        if (protectedMatcher.contains(host)) return false
        val listed = matcher.contains(host)
        return mode == "ALLOWLIST" && !listed || mode == "BLOCKLIST" && listed
    }
}
