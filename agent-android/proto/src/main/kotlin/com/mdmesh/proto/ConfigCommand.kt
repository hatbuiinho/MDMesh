package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Payload of `config.apply` (proto/payloads/config-apply.schema.json): the server-computed desired state of the
 * device's configuration. Every field defaults so a newer server can add keys freely.
 *
 * @property revision sha256 of the canonical document; reported back as `appliedConfigRevision` once applied.
 * @property policies only the keys the configuration manages (true = allowed/enabled).
 * @property kiosk present = ensure kiosk with this payload; absent = the configuration does not assert kiosk
 *   (exit only if the previously applied configuration did — see ConfigApplier).
 * @property location capture cadence, see [DeviceAction.LOCATION_MODE].
 */
@Serializable
data class ConfigApplyPayload(
    val revision: String = "",
    val configurationId: Int? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val kiosk: KioskApplyPayload? = null,
    val applications: ConfigApplications = ConfigApplications(),
    val location: ConfigLocation? = null,
    val appUsage: AppUsagePolicy? = null,
    val webAccess: WebAccessPolicy? = null,
)

@Serializable
data class WebAccessPolicy(
    val mode: String = "OFF",
    val domains: List<String> = emptyList(),
)

@Serializable
data class AppUsagePolicy(
    val timezone: String = "UTC",
    val rules: List<AppUsageRule> = emptyList(),
    val overrides: List<AppUsageGrant> = emptyList(),
)

@Serializable
data class AppUsageRule(
    val id: Int? = null,
    val packageName: String,
    val dailyLimitMinutes: Int? = null,
    val warningMinutes: Int = 5,
    val action: String = "suspend",
    val enabled: Boolean = true,
    val allowedWindows: List<AppUsageWindow> = emptyList(),
)

@Serializable
data class AppUsageWindow(val days: List<Int> = emptyList(), val from: String, val to: String)

@Serializable
data class AppUsageGrant(val packageName: String, val extraMinutes: Int, val expiresAt: Long)

@Serializable
data class AppUsageDailyReport(
    val packageName: String,
    val usageDate: String,
    val foregroundMs: Long,
    val limitReachedAt: Long? = null,
    val status: String,
)

@Serializable
data class ConfigApplications(
    val enforceAllowlist: Boolean = false,
    val allowedPackages: List<String> = emptyList(),
)

@Serializable
data class ConfigLocation(val mode: String = DeviceAction.LOCATION_PASSIVE)

/** JSON-encoded into `CommandResult.detail` (proto/payloads/config-apply-result.schema.json). */
@Serializable
data class ConfigApplyResult(
    val revision: String,
    /** keys: `policies.<key>`, `kiosk`, `location` → [ConfigOutcome] strings. */
    val outcomes: Map<String, String>,
)

object ConfigOutcome {
    const val APPLIED = "applied"
    const val UNSUPPORTED = "unsupported"
    fun failed(reason: String): String = "failed: $reason"
    fun isFailed(outcome: String): Boolean = outcome.startsWith("failed")
}
