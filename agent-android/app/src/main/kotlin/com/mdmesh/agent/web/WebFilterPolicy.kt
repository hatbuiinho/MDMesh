package com.mdmesh.agent.web
import com.mdmesh.proto.NetworkRule
import com.mdmesh.proto.NetworkGrant

/** Missing ownership never inherits an app-specific grant. Overlapping policies deny by default. */
internal data class WebFilterPolicy(
    val mode: String, val domains: Set<String>, val protectedDomains: Set<String>,
    val scope: String = "ALL", val packages: Set<String> = emptySet(),
    val policyHash: String = "", val rules: List<NetworkRule> = emptyList(), val grants: List<NetworkGrant> = emptyList(),
) {
    private val matcher = DomainMatcher(domains)
    private val protectedMatcher = DomainMatcher(protectedDomains)
    private val compiled = rules.map { it to DomainMatcher(it.domains) }
    val allApps: Boolean get() = mode != "OFF" && scope == "ALL" || rules.any { it.scope == "ALL" }
    val filteredPackages: Set<String> get() = (if (mode != "OFF" && scope == "SELECTED") packages else emptySet()) + rules.flatMap { it.packages }
    fun blockedRules(host: String, pkg: String?): List<String> {
        if (protectedMatcher.contains(host)) return emptyList()
        val denied = ArrayList<String>()
        if ((pkg == null || scope == "ALL" || pkg in packages) && denies(mode, matcher.contains(host))) denied += "Chính sách mạng của cấu hình"
        compiled.forEach { (rule, match) ->
            if ((pkg == null || rule.scope == "ALL" || pkg in rule.packages) && denies(rule.mode, match.contains(host))) denied += rule.name
        }
        return denied
    }
    fun blocks(host: String, pkg: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        if (blockedRules(host, pkg).isEmpty()) return false
        val normalized = DomainMatcher.normalize(host)
        return grants.none { g ->
            g.domain == normalized && (g.expiresAt?.let { it > now } ?: true) &&
                ("*" in g.packages || pkg != null && pkg in g.packages)
        }
    }
    private fun denies(mode: String, listed: Boolean) = mode == "ALLOWLIST" && !listed || mode == "BLOCKLIST" && listed
}

/** A config is acknowledged only after its packet reader is actually serving the tunnel. */
internal object WebFilterRuntime {
    @Volatile var applied: WebFilterPolicy? = null
    @Volatile var failure: String? = null
}
