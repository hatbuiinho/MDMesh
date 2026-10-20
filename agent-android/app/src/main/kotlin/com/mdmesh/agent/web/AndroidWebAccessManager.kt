package com.mdmesh.agent.web

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.mdmesh.core.web.WebAccessManager
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.WebAccessPolicy
import com.mdmesh.policy.PackageSuspensionManager
import com.mdmesh.policy.SuspensionReason
import java.net.URI

class AndroidWebAccessManager(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val serverConfig: ServerConfigStore,
    private val suspensionManager: PackageSuspensionManager,
) : WebAccessManager {
    override suspend fun apply(policy: WebAccessPolicy?): String = runCatching {
        check(dpm.isDeviceOwnerApp(context.packageName)) { "device owner required" }
        val protectedHost = runCatching { URI(serverConfig.baseUrl()).host }.getOrNull()
        require(policy == null || policy.mode == "OFF" || policy.mode == "ALLOWLIST" || policy.mode == "BLOCKLIST") { "invalid mode" }
        require(policy == null || policy.mode != "ALLOWLIST" || policy.domains.isNotEmpty()) { "empty allowlist" }
        require(policy == null || policy.scope == "ALL" || policy.scope == "SELECTED") { "invalid web filter scope" }
        if (policy != null && policy.mode != "OFF" && policy.scope == "SELECTED") {
            require(policy.packages.isNotEmpty()) { "no selected apps" }
        }
        require(policy == null || policy.rules.size <= 50) { "too many network rules" }
        policy?.rules?.forEach { r ->
            require(r.mode in setOf("BLOCKLIST", "ALLOWLIST") && r.scope in setOf("ALL", "SELECTED")) { "invalid network rule" }
            require(r.scope != "SELECTED" || r.packages.isNotEmpty()) { "network group requires apps" }
            require(r.mode != "ALLOWLIST" || r.domains.isNotEmpty()) { "empty network allowlist" }
        }
        WebFilterConfig.save(context, policy, protectedHost)
        if (policy == null || policy.mode == "OFF" && policy.rules.isEmpty()) {
            dpm.setAlwaysOnVpnPackage(admin, null, false)
            context.stopService(Intent(context, WebFilterService::class.java))
            check(suspensionManager.setReason(VPN_DIALOGS, SuspensionReason.WEB_FILTER_VPN, false)) {
                "VPN management dialogs could not be restored"
            }
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            check(!dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_VPN)) {
                "VPN configuration restriction was not removed"
            }
        } else {
            // DNS-only split tunnel keeps application traffic on Android's kernel path; lockdown would
            // incorrectly block traffic outside the single routed DNS address.
            if (dpm.getAlwaysOnVpnPackage(admin) != context.packageName) {
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
            }
            // Bind our always-on VPN before restricting Settings. On Android 12+, adding this
            // restriction can clear a user-configured VPN; the Device Owner VPN must already exist.
            if (!dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_VPN)) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_VPN)
            }
            check(dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_VPN)) {
                "VPN configuration restriction was not applied"
            }
            // Realme's notification opens VpnDialogs directly and can disconnect the VPN despite
            // DISALLOW_CONFIG_VPN. Suspend that UI while filtering, retaining the system notification.
            // Shared suspension ownership keeps app allowlist/usage policy from undoing this guard.
            check(suspensionManager.setReason(VPN_DIALOGS, SuspensionReason.WEB_FILTER_VPN, true)) {
                "VPN management dialogs could not be blocked"
            }
            val expected = WebFilterConfig.load(context)
            WebFilterRuntime.failure = null
            ContextCompat.startForegroundService(context, Intent(context, WebFilterService::class.java))
            kotlinx.coroutines.withTimeout(12_000) {
                while (WebFilterRuntime.applied != expected) {
                    WebFilterRuntime.failure?.let { error(it) }
                    kotlinx.coroutines.delay(50)
                }
            }
        }
        ConfigOutcome.APPLIED
    }.getOrElse { ConfigOutcome.failed(it.message ?: "web filter") }

    private companion object { const val VPN_DIALOGS = "com.android.vpndialogs" }
}

internal object WebFilterConfig {
    private const val PREFS = "web_filter"
    fun save(context: Context, policy: WebAccessPolicy?, protectedHost: String?) {
        val domains = normalized(policy?.domains.orEmpty())
        val protected = normalized(listOfNotNull(protectedHost))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("policy", com.mdmesh.proto.ProtocolJson.json.encodeToString(com.mdmesh.proto.WebAccessPolicy.serializer(), policy ?: com.mdmesh.proto.WebAccessPolicy()))
            .putString("mode", policy?.mode ?: "OFF")
            .putStringSet("domains", domains)
            .putStringSet("protectedDomains", protected)
            .putString("scope", policy?.scope ?: "ALL")
            .putStringSet("packages", policy?.packages?.toSet() ?: emptySet())
            .apply()
    }
    fun load(context: Context): WebFilterPolicy {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wire = runCatching { com.mdmesh.proto.ProtocolJson.json.decodeFromString(com.mdmesh.proto.WebAccessPolicy.serializer(), p.getString("policy", "{}") ?: "{}") }.getOrDefault(com.mdmesh.proto.WebAccessPolicy())
        return WebFilterPolicy(
            p.getString("mode", "OFF") ?: "OFF",
            p.getStringSet("domains", emptySet()) ?: emptySet(),
            p.getStringSet("protectedDomains", emptySet()) ?: emptySet(),
            p.getString("scope", "ALL") ?: "ALL",
            p.getStringSet("packages", emptySet()) ?: emptySet(),
            wire.policyHash, wire.rules, wire.grants,
        )
    }

    internal fun normalized(domains: Collection<String>): Set<String> = domains
        .asSequence().map(DomainMatcher::normalize).filter(String::isNotEmpty).toSet()
}
