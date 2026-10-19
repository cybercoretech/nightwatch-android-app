package dev.cybercore.nightwatch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One machine running nightwatchd. The token lives in [TokenVault]. */
data class Host(val id: String, val label: String, val baseUrl: String)

/** The hosts this phone watches, in the order they were added. */
class HostStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val vault = TokenVault(app)

    fun list(): List<Host> = runCatching {
        JSONArray(prefs.getString(KEY, "[]")).objects().map { Host(it.getString("id"), it.getString("label"), it.getString("url")) }
    }.getOrDefault(emptyList())

    fun get(id: String): Host? = list().firstOrNull { it.id == id }

    fun token(id: String): String? = vault.read(id)

    fun api(host: Host): NightwatchApi? = token(host.id)?.let { NightwatchApi(host.baseUrl, it) }

    fun add(label: String, baseUrl: String, token: String): Host {
        val host = Host(UUID.randomUUID().toString().take(8), label, baseUrl)
        vault.store(host.id, token)
        save(list() + host)
        return host
    }

    fun rename(id: String, label: String) = save(list().map { if (it.id == id) it.copy(label = label) else it })

    fun remove(id: String) {
        vault.clear(id)
        save(list().filterNot { it.id == id })
        app.getSharedPreferences("alerts_seen", Context.MODE_PRIVATE).edit { remove(id) }
    }

    var selected: String?
        get() = prefs.getString(SELECTED, null)?.takeIf { s -> list().any { it.id == s } } ?: list().firstOrNull()?.id
        set(value) = prefs.edit { putString(SELECTED, value) }

    private fun save(hosts: List<Host>) {
        val array = JSONArray()
        hosts.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("url", it.baseUrl)) }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private companion object {
        const val KEY = "hosts"
        const val SELECTED = "selected"
    }
}

/**
 * Device tokens, encrypted with a per-host AES-GCM key that never leaves the
 * Android Keystore. Background work needs them, so no user authentication
 * is bound to the key; actions are gated by a biometric prompt instead.
 */
class TokenVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tokens", Context.MODE_PRIVATE)

    fun store(hostId: String, token: String) {
        val clear = token.toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = cipher(hostId, Cipher.ENCRYPT_MODE)
            val encrypted = cipher.doFinal(clear)
            val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
            prefs.edit { putString(hostId, Base64.encodeToString(payload, Base64.NO_WRAP)) }
        } finally { clear.fill(0) }
    }

    fun read(hostId: String): String? {
        val encoded = prefs.getString(hostId, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
            if (ivSize != 12 || payload.size <= ivSize + 1) return null
            val clear = cipher(hostId, Cipher.DECRYPT_MODE, payload.copyOfRange(1, 1 + ivSize))
                .doFinal(payload.copyOfRange(1 + ivSize, payload.size))
            try { String(clear, StandardCharsets.UTF_8) } finally { clear.fill(0) }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(hostId: String) {
        prefs.edit { remove(hostId) }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(hostId)) }
    }

    private fun alias(hostId: String) = "nightwatch.token.$hostId"

    private fun cipher(hostId: String, mode: Int, iv: ByteArray? = null): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = alias(hostId)
        val key = if (store.containsAlias(alias)) store.getKey(alias, null) as SecretKey else {
            check(mode == Cipher.ENCRYPT_MODE) { "Saved token key is missing" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (mode == Cipher.ENCRYPT_MODE) init(mode, key) else init(mode, key, GCMParameterSpec(128, requireNotNull(iv)))
        }
    }
}
