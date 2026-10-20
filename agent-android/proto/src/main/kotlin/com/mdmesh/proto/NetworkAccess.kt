package com.mdmesh.proto
import kotlinx.serialization.Serializable
@Serializable data class NetworkRule(
    val id: String, val name: String, val mode: String = "BLOCKLIST",
    val scope: String = "SELECTED", val packages: List<String> = emptyList(), val domains: List<String> = emptyList(),
)
@Serializable data class NetworkGrant(
    val requestId: Int, val domain: String, val packages: List<String> = emptyList(), val expiresAt: Long? = null,
)
