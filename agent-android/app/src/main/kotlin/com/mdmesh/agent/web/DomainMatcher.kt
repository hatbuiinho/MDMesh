package com.mdmesh.agent.web

/** Immutable, allocation-light suffix matcher. A rule also covers all of its subdomains. */
class DomainMatcher(domains: Collection<String>) {
    private val rules = domains.asSequence().map(::normalize).filter(String::isNotEmpty).toHashSet()

    fun contains(host: String): Boolean {
        var candidate = normalize(host)
        while (candidate.isNotEmpty()) {
            if (candidate in rules) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    companion object {
        fun normalize(value: String): String = value.trim().lowercase().removePrefix("*.").trimEnd('.')
    }
}
