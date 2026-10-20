package com.mdmesh.agent.web

import android.content.Context
import android.util.Base64
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.DeviceIdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Enrollment, receive-only and shared admin credentials remain separate. */
internal class NetworkAccessClient(private val context: Context) {
    private val session=AdminSessionStore.get(context)
    val accountName:String get()=session.snapshot().name
    suspend fun devicePath(): String = "/public/agent/v1/network-access/${DeviceIdStore(context).current() ?: error("Máy chưa đăng ký") }"
    suspend fun device(path: String, body: JSONObject? = null): JSONObject {
        val secret = DeviceIdStore(context).secret() ?: error("Máy chưa đăng ký")
        return call(path, body, "Bearer $secret")
    }
    suspend fun login(username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "Nhập tài khoản và mật khẩu." }
        val generation=session.beginLogin()
        val server=ServerConfigStore(context).baseUrl()
        val options = call("/public/auth/options").optJSONObject("data")
        val hashed = MessageDigest.getInstance("MD5").digest(password.toByteArray()).joinToString("") { "%02X".format(it) }
        val publicKey = options?.optString("publicKey").orEmpty()
        val encoded = if (publicKey.isNotEmpty()) {
            val bytes = Base64.decode(publicKey.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", ""), Base64.DEFAULT)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE, key)
            Base64.encodeToString(cipher.doFinal(hashed.toByteArray()), Base64.NO_WRAP)
        } else hashed
        val result = call("/public/auth/login", JSONObject().put("login", username).put("password", encoded), captureCookie = true)
        val user = result.optJSONObject("data") ?: error("Không đăng nhập được")
        if (user.optBoolean("twoFactor") || user.optBoolean("passwordReset")) { invalidate(result.optString("_sessionCookie"),server); error("Tài khoản cần xác minh thêm. Hãy duyệt từ dashboard.") }
        val name=user.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: user.optString("login", username)
        session.save(generation,result.getString("_sessionCookie"),name,server)
    }
    suspend fun receive(token: String, revoke: Boolean = false): JSONObject = call(
        "/public/network-access-notifications" + if (revoke) "/revoke" else "",
        if (revoke) JSONObject() else null, "Bearer $token",
    )
    fun isAdmin() = session.snapshot().cookie.isNotEmpty()
    suspend fun admin(path: String, body: JSONObject? = null): JSONObject {
        check(isAdmin()) { "Phiên admin đã khóa. Đăng nhập lại." }
        val state=session.snapshot()
        return call(path, body, sessionCookie = state.cookie, expectedServer=state.server)
    }
    fun clearSession(): String = session.clear()
    suspend fun logout() { val state=session.snapshot();clearSession();if(state.cookie.isNotEmpty())runCatching {call("/public/auth/logout",JSONObject(),sessionCookie=state.cookie,expectedServer=state.server)} }
    private suspend fun invalidate(previous: String,server:String) { if (previous.isNotEmpty()) runCatching { call("/public/auth/logout", JSONObject(), sessionCookie = previous,expectedServer=server) } }
    private suspend fun call(path: String, body: JSONObject? = null, bearer: String? = null, captureCookie: Boolean = false, sessionCookie: String = "", expectedServer:String?=null): JSONObject = withContext(Dispatchers.IO) {
        val base = ServerConfigStore(context).baseUrl()
        check(expectedServer==null || base==expectedServer){"Máy chủ đã thay đổi. Đăng nhập lại."}
        require(URL(base).protocol == "https") { "Truy cập và đăng nhập yêu cầu server HTTPS." }
        val conn = URL("$base/rest$path").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", bearer)
            if (sessionCookie.isNotEmpty()) conn.setRequestProperty("Cookie", sessionCookie)
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if(conn.responseCode in setOf(401,403)&&sessionCookie.isNotEmpty()) {
                session.clearIf(sessionCookie)
                error("Phiên admin đã hết hạn. Đăng nhập lại từ Tài khoản quản trị.")
            }
            check(conn.responseCode in 200..299) { "Server trả mã ${conn.responseCode}" }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(text)
            if (result.optString("status") != "OK") throw NetworkAccessRejected(result.optString("message").takeUnless { it.isBlank() || it == "null" } ?: "Không được phép hoặc yêu cầu không hợp lệ.")
            if(captureCookie)result.put("_sessionCookie",conn.headerFields.filterKeys { it?.equals("Set-Cookie",true)==true }.values.flatten().map { it.substringBefore(';') }.joinToString("; "))
            result
        } finally { conn.disconnect() }
    }
}

internal class NetworkAccessRejected(message: String) : Exception(message)
