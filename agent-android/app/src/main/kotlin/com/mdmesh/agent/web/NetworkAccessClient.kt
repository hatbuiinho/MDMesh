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

/** Separate in-memory admin session. Never persist admin passwords or cookies on the managed device. */
internal class NetworkAccessClient(private val context: Context) {
    @Volatile private var cookie = ""
    @Volatile private var sessionGeneration = 0
    private var adminUntil = 0L
    suspend fun devicePath(): String = "/public/agent/v1/network-access/${DeviceIdStore(context).current() ?: error("Máy chưa đăng ký") }"
    suspend fun device(path: String, body: JSONObject? = null): JSONObject {
        val secret = DeviceIdStore(context).secret() ?: error("Máy chưa đăng ký")
        return call(path, body, "Bearer $secret")
    }
    suspend fun login(username: String, password: String) {
        require(username.isNotBlank() && password.isNotEmpty()) { "Nhập tài khoản và mật khẩu." }
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
        if (user.optBoolean("twoFactor") || user.optBoolean("passwordReset")) { logout(); error("Tài khoản cần xác minh thêm. Hãy duyệt từ dashboard.") }
        adminUntil = android.os.SystemClock.elapsedRealtime() + 5 * 60_000
    }
    fun isAdmin() = cookie.isNotEmpty() && android.os.SystemClock.elapsedRealtime() < adminUntil
    suspend fun admin(path: String, body: JSONObject? = null): JSONObject {
        check(isAdmin()) { "Phiên admin đã khóa. Đăng nhập lại." }
        return call(path, body, sessionCookie = cookie)
    }
    fun clearSession(): String { sessionGeneration++; val previous = cookie; cookie = ""; adminUntil = 0; return previous }
    suspend fun logout() { val previous = clearSession(); if (previous.isNotEmpty()) runCatching { call("/public/auth/logout", JSONObject(), sessionCookie = previous) } }
    suspend fun invalidate(previous: String) { if (previous.isNotEmpty()) runCatching { call("/public/auth/logout", JSONObject(), sessionCookie = previous) } }
    private suspend fun call(path: String, body: JSONObject? = null, bearer: String? = null, captureCookie: Boolean = false, sessionCookie: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        val base = ServerConfigStore(context).baseUrl()
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
            if (captureCookie && generation == sessionGeneration) cookie = conn.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten().map { it.substringBefore(';') }.joinToString("; ")
            check(conn.responseCode in 200..299) { "Server trả mã ${conn.responseCode}" }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(text)
            if (result.optString("status") != "OK") throw NetworkAccessRejected(result.optString("message").takeUnless { it.isBlank() || it == "null" } ?: "Không được phép hoặc yêu cầu không hợp lệ.")
            check(!captureCookie || generation == sessionGeneration) { "Phiên đăng nhập đã đóng." }
            result
        } finally { conn.disconnect() }
    }
}

internal class NetworkAccessRejected(message: String) : Exception(message)
