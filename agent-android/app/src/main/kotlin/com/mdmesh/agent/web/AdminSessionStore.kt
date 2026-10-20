package com.mdmesh.agent.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mdmesh.core.config.ServerConfigStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted, server-bound admin session for the entire Agent. No passwords. */
internal class AdminSessionStore private constructor(context:Context) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("admin_session",Context.MODE_PRIVATE)
    data class Snapshot(val cookie:String="",val name:String="",val server:String="")
    private var value=runCatching {
        val data=prefs.getString("data",null) ?: return@runCatching Snapshot()
        val iv=prefs.getString("iv",null) ?: return@runCatching Snapshot()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
        val json=JSONObject(String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8))
        Snapshot(json.getString("cookie"),json.getString("name"),json.getString("server"))
    }.getOrDefault(Snapshot())
    private var generation=0
    @Synchronized fun snapshot():Snapshot {
        if(value.cookie.isNotEmpty()&&value.server!=ServerConfigStore(app).baseUrl())clear()
        return value
    }
    @Synchronized fun beginLogin():Int { clear();return generation }
    @Synchronized fun save(expected:Int,cookie:String,name:String,server:String) {
        check(expected==generation&&server==ServerConfigStore(app).baseUrl()){ "Phiên đăng nhập đã đóng hoặc máy chủ đã thay đổi." }
        check(cookie.isNotBlank()){ "Server không cấp phiên đăng nhập." }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val data=JSONObject().put("cookie",cookie).put("name",name).put("server",server).toString()
        check(prefs.edit().putString("data",Base64.encodeToString(cipher.doFinal(data.toByteArray()),Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit())
        value=Snapshot(cookie,name,server)
    }
    @Synchronized fun clearIf(cookie:String) { if(value.cookie==cookie)clear() }
    @Synchronized fun clear():String {
        generation++;val previous=value.cookie;value=Snapshot()
        check(prefs.edit().clear().commit());return previous
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mdmesh.admin.session",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mdmesh.admin.session",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        @Volatile private var instance:AdminSessionStore?=null
        fun get(context:Context):AdminSessionStore = instance ?: synchronized(this){instance ?: AdminSessionStore(context).also { instance=it }}
    }
}
