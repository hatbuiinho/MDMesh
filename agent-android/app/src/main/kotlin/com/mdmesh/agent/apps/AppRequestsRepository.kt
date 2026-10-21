package com.mdmesh.agent.apps

import android.content.Context
import com.mdmesh.agent.web.AdminSessionStore
import com.mdmesh.agent.web.NetworkAccessClient
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.sync.CheckInWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

internal interface AppRequestsRepository {
    fun account(): AgentAdminAccount
    suspend fun requests(admin: Boolean, page: Int): List<AppInstallRequest>
    suspend fun search(query: String): List<CatalogApp>
    suspend fun submit(app: CatalogApp, reason: String): List<AppInstallRequest>
    suspend fun decide(id: Int, action: RequestDecision, note: String)
    suspend fun history(id: Int): List<AppRequestAudit>
    suspend fun approvedPackage(id: Int): String
}

internal class AndroidAppRequestsRepository(context: Context) : AppRequestsRepository {
    private val app = context.applicationContext
    private val client = NetworkAccessClient(app)
    override fun account(): AgentAdminAccount {
        val state = AdminSessionStore.get(app).snapshot()
        return AgentAdminAccount(state.cookie.isNotBlank(), "${state.server}:${state.generation}")
    }
    private suspend fun path() = "/public/agent/v1/app-requests/${DeviceIdStore(app).current() ?: error("Máy chưa đăng ký. Hãy đăng ký từ trang chính của Agent.")}"
    private fun JSONObject.clean(key: String) = optString(key).trim().takeUnless { it == "null" }.orEmpty()
    private fun JSONArray?.objects() = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    private fun JSONObject.request() = AppInstallRequest(
        id = getInt("id"), packageName = getString("packageName"), appName = clean("appName").ifEmpty { getString("packageName") },
        developer = clean("developer"), deviceName = clean("deviceName"), deviceNumber = clean("deviceNumber"),
        status = clean("status"), reason = clean("reason"), createdAt = optLong("createdAt"),
        installedAt = if (isNull("installedAt")) null else optLong("installedAt"),
        decisionBy = clean("decisionBy"), decisionNote = clean("decisionNote"),
    )
    private fun JSONObject.requestRows() = optJSONArray("data").objects().map { it.request() }
    override suspend fun requests(admin: Boolean, page: Int): List<AppInstallRequest> = withContext(Dispatchers.IO) {
        if (admin) return@withContext client.admin("/private/app-requests?page=$page").requestRows()
        personalRequests()
    }
    private suspend fun personalRequests(): List<AppInstallRequest> = personalMutex.withLock {
        val scope = AppApprovalNotifications.scope(app) ?: error("Máy chưa đăng ký")
        val base = path()
        var rows = client.device(base).requestRows()
        var changed = false
        for (row in rows.filter { it.status == "approved" && it.installedAt == null }) {
            if (runCatching { app.packageManager.getPackageInfo(row.packageName, 0) }.isSuccess) {
                client.device("$base/${row.id}/installed", JSONObject())
                changed = true
            }
        }
        if (changed) rows = client.device(base).requestRows()
        if (rows.any { it.status == "approved" }) CheckInWorker.scheduleNow(app)
        check(AppApprovalNotifications.scope(app) == scope) { "Thiết bị hoặc máy chủ đã thay đổi" }
        AppApprovalNotifications.update(app, scope, rows)
        rows
    }
    private companion object { val personalMutex = Mutex() }
    override suspend fun search(query: String): List<CatalogApp> = client.device(path()+"/search?q="+URLEncoder.encode(query, "UTF-8"))
        .optJSONArray("data").objects().map { CatalogApp(it.getString("packageName"), it.clean("name").ifEmpty { it.getString("packageName") }, it.clean("summary")) }
    override suspend fun submit(app: CatalogApp, reason: String): List<AppInstallRequest> = client.device(path(), JSONObject()
        .put("packageName", app.packageName).put("appName", app.name.take(200)).put("developer", app.developer.take(300)).put("reason", reason)).requestRows()
    override suspend fun decide(id: Int, action: RequestDecision, note: String) {
        client.admin("/private/app-requests/$id/decision", JSONObject().put("action", action.action).put("note", note))
    }
    override suspend fun history(id: Int): List<AppRequestAudit> = client.admin("/private/app-requests/$id/history").optJSONArray("data").objects().map {
        AppRequestAudit(it.clean("status"), it.clean("actor"), it.clean("note"), it.optLong("createdat", it.optLong("createdAt")))
    }
    override suspend fun approvedPackage(id: Int): String {
        val scope = AppApprovalNotifications.scope(app) ?: error("Máy chưa đăng ký")
        val request = client.device(path()).requestRows().firstOrNull { it.id == id }
        check(AppApprovalNotifications.scope(app) == scope) { "Thiết bị hoặc máy chủ đã thay đổi" }
        check(request?.status == "approved") { "Quyền đã thay đổi. Hãy làm mới yêu cầu." }
        CheckInWorker.scheduleNow(app)
        return request.packageName
    }
}
