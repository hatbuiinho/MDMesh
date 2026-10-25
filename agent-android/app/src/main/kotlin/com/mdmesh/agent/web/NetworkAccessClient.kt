package com.mdmesh.agent.web

import android.content.Context
import android.util.Base64
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.DeviceIdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val cookie=result.getString("_sessionCookie")
        // Old servers may not offer refresh yet. Preserve their valid cookie without storing a password.
        val registered=try { call("/public/agent-admin-session/register",JSONObject().put("installationId",session.installation()),captureCookie=true,sessionCookie=cookie,expectedServer=server) }
            catch(e:NetworkHttpError){if(e.code==404)null else {invalidate(cookie,server);throw e}}
        val data=registered?.optJSONObject("data")
        val currentCookie=registered?.optString("_sessionCookie")?.takeIf{it.isNotBlank()} ?: cookie
        try {
            session.save(generation,currentCookie,name,server,data?.optString("refreshToken").orEmpty(),data?.optLong("refreshExpiresAt") ?: 0,data?.optLong("accessExpiresAt") ?: 0)
        } catch(e:Exception){
            data?.optString("refreshToken")?.takeIf{it.isNotEmpty()}?.let{revoke(it,server)}
            invalidate(currentCookie,server);throw e
        }
    }
    suspend fun receive(token: String, revoke: Boolean = false): JSONObject = call(
        "/public/network-access-notifications" + if (revoke) "/revoke" else "",
        if (revoke) JSONObject() else null, "Bearer $token",
    )
    fun isAdmin() = session.snapshot().cookie.isNotEmpty()
    val refreshExpiresAt:Long get()=session.snapshot().refreshExpiresAt
    suspend fun admin(path: String, body: JSONObject? = null, method: String? = null): JSONObject = AdminSessionRecovery(
        session::snapshot,session::clearIf,::renew,::validCookie,authMutex,::maybeUpgrade,
    ).run { state -> call(path,body,sessionCookie=state.cookie,expectedServer=state.server,method=method) }
    private suspend fun maybeUpgrade(state:AdminSessionStore.Snapshot):AdminSessionStore.Snapshot {
        if(state.refreshToken.isNotEmpty() || upgradeServer==state.server && System.currentTimeMillis()<upgradeAfter)return state
        val result=try {
            call("/public/agent-admin-session/register",JSONObject().put("installationId",session.installation()),captureCookie=true,sessionCookie=state.cookie,expectedServer=state.server)
        } catch(e:NetworkHttpError){
            when(e.code){404->{upgradeServer=state.server;upgradeAfter=System.currentTimeMillis()+300000;return state};401->{session.clearIf(state.cookie);error("Phiên admin đã hết hạn. Đăng nhập lại.")};else->throw e}
        }
        val data=result.getJSONObject("data");val cookie=result.getString("_sessionCookie");val refresh=data.getString("refreshToken")
        try{session.save(state.generation,cookie,state.name,state.server,refresh,data.getLong("refreshExpiresAt"),data.getLong("accessExpiresAt"))}
        catch(e:Exception){revoke(refresh,state.server);invalidate(cookie,state.server);throw e}
        return session.snapshot()
    }
    private suspend fun validCookie(state:AdminSessionStore.Snapshot):Boolean = try {
        call("/public/agent-admin-session/status",sessionCookie=state.cookie,expectedServer=state.server)
        true
    } catch(e:NetworkHttpError) {
        when(e.code){401->false;404->true;else->throw e}
    }
    private suspend fun renew(state:AdminSessionStore.Snapshot):AdminSessionStore.Snapshot {
        check(session.snapshot().generation==state.generation){"Phiên đăng nhập đã thay đổi."}
        val result=try {
            call("/public/agent-admin-session/refresh",JSONObject(),bearer="Bearer ${state.refreshToken}",captureCookie=true,expectedServer=state.server)
        } catch(e:NetworkHttpError){
            if(e.code in setOf(401,403))session.clearIf(state.cookie)
            throw e
        }
        val data=result.getJSONObject("data")
        val cookie=result.getString("_sessionCookie")
        val refresh=data.getString("refreshToken")
        try {
            session.save(state.generation,cookie,state.name,state.server,refresh,data.getLong("refreshExpiresAt"),data.getLong("accessExpiresAt"))
        } catch(e:Exception){revoke(refresh,state.server);invalidate(cookie,state.server);throw e}
        return session.snapshot()
    }
    fun clearSession(): String = session.clear()
    suspend fun logout() {
        val state=session.snapshot();session.clear()
        authMutex.withLock {
            if(state.refreshToken.isNotEmpty())revoke(state.refreshToken,state.server)
            if(state.cookie.isNotEmpty())invalidate(state.cookie,state.server)
        }
    }
    private suspend fun revoke(token:String,server:String){runCatching{call("/public/agent-admin-session/revoke",JSONObject(),bearer="Bearer $token",expectedServer=server)}}
    private suspend fun invalidate(previous: String,server:String) { if (previous.isNotEmpty()) runCatching { call("/public/auth/logout", JSONObject(), sessionCookie = previous,expectedServer=server) } }
    private suspend fun call(path: String, body: JSONObject? = null, bearer: String? = null, captureCookie: Boolean = false, sessionCookie: String = "", expectedServer:String?=null, method:String?=null): JSONObject = withContext(Dispatchers.IO) {
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
                conn.requestMethod = method ?: "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if(conn.responseCode !in 200..299)throw NetworkHttpError(conn.responseCode)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val result = if(text.isBlank())JSONObject().put("status","OK") else JSONObject(text)
            if (result.optString("status") != "OK") throw NetworkAccessRejected(result.optString("message").takeUnless { it.isBlank() || it == "null" } ?: "Không được phép hoặc yêu cầu không hợp lệ.")
            if(captureCookie)result.put("_sessionCookie",conn.headerFields.filterKeys { it?.equals("Set-Cookie",true)==true }.values.flatten().map { it.substringBefore(';') }.joinToString("; "))
            result
        } finally { conn.disconnect() }
    }
    companion object { private val authMutex=Mutex();private var upgradeServer="";private var upgradeAfter=0L }
}

internal class NetworkHttpError(val code:Int):Exception("Server trả mã $code")
internal class NetworkAccessRejected(message: String) : Exception(message)
