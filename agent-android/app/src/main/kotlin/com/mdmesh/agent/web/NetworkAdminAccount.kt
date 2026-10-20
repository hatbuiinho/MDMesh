package com.mdmesh.agent.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mdmesh.core.config.ServerConfigStore
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Receive-only subscription is independent from the shared privileged admin session. */
internal class NetworkAdminAccount(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("network_admin_notifications", Context.MODE_PRIVATE)
    fun installation(): String = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installation", it).commit() }
    fun name(): String = prefs.getString("account", "Admin").orEmpty()
    fun expiresAt() = prefs.getLong("expiresAt", 0)
    fun token(): String? = runCatching {
        if (expiresAt() <= System.currentTimeMillis() || prefs.getString("server", null) != ServerConfigStore(app).baseUrl()) return null
        val encoded = prefs.getString("token", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun save(result: JSONObject) {
        val token = result.getString("token")
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        check(prefs.edit().putString("token", encoded).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("server", ServerConfigStore(app).baseUrl()).putString("account", result.getString("account"))
            .putLong("expiresAt", result.getLong("expiresAt")).putInt("notifiedId", 0).commit())
    }
    fun clear() { check(prefs.edit().remove("token").remove("iv").remove("expiresAt").remove("account").remove("server").remove("notifiedId").commit()) }
    fun notifiedId() = prefs.getInt("notifiedId", 0)
    fun notified(id: Int) { prefs.edit().putInt("notifiedId", id).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object { private const val ALIAS = "mdmesh.network.receive" }
}
