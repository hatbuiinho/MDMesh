package com.mdmesh.agent.apps

internal data class CatalogApp(val packageName: String, val name: String, val developer: String = "")

internal data class AppInstallRequest(
    val id: Int,
    val packageName: String,
    val appName: String,
    val developer: String = "",
    val deviceName: String = "",
    val deviceNumber: String = "",
    val status: String,
    val reason: String,
    val createdAt: Long = 0,
    val installedAt: Long? = null,
    val decisionBy: String = "",
    val decisionNote: String = "",
) {
    val deviceLabel: String get() = com.mdmesh.agent.ui.requestDeviceLabel(deviceName, deviceNumber)
}

internal data class AppRequestAudit(val status: String, val actor: String, val note: String, val createdAt: Long)
internal data class AgentAdminAccount(val signedIn: Boolean, val identity: String)
internal enum class RequestTab { PENDING, APPROVED, HISTORY }
internal enum class RequestDecision(val action: String) { APPROVE("approve"), REJECT("reject"), REVOKE("revoke") }

internal sealed interface AppRequestSheet {
    data object Picker : AppRequestSheet
    data class Form(val app: CatalogApp) : AppRequestSheet
    data class Details(val request: AppInstallRequest) : AppRequestSheet
}

internal data class AppRequestsState(
    val admin: Boolean = false,
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val working: Boolean = false,
    val message: String? = null,
    val requests: List<AppInstallRequest> = emptyList(),
    val tab: RequestTab = RequestTab.PENDING,
    val page: Int = 1,
    val sheet: AppRequestSheet? = null,
    val query: String = "",
    val searching: Boolean = false,
    val searchResults: List<CatalogApp> = emptyList(),
    val searchMessage: String? = null,
    val reason: String = "",
    val note: String = "",
    val history: List<AppRequestAudit> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,
)

internal fun requestStatusLabel(status: String): String = when (status) {
    "pending" -> "Chờ duyệt"
    "approved" -> "Đã duyệt"
    "rejected" -> "Từ chối"
    "revoked" -> "Đã thu hồi"
    "stale" -> "Cấu hình đã thay đổi"
    else -> status
}

internal fun visibleAppRequests(state: AppRequestsState): List<AppInstallRequest> = when {
    state.admin && !state.signedIn -> emptyList()
    !state.admin -> state.requests
    state.tab == RequestTab.PENDING -> state.requests.filter { it.status == "pending" }
    state.tab == RequestTab.APPROVED -> state.requests.filter { it.status == "approved" }
    else -> state.requests.filter { it.status !in setOf("pending", "approved") }
}
