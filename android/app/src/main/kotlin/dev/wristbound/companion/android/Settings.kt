package dev.wristbound.companion.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.wristbound.companion.core.AlertPrefs
import dev.wristbound.companion.core.AlertScope
import dev.wristbound.companion.core.HermesJson
import dev.wristbound.companion.core.ReplyAlertMode
import dev.wristbound.companion.core.ToolActivityMode
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Non-secret configuration and preferences (app-private SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("wristbound", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v.trim()).apply()

    /** Optional Wristbound media adapter (adapter/ in this repo) for Hermes-generated audio. */
    var mediaAdapterUrl: String
        get() = prefs.getString("media_adapter_url", "") ?: ""
        set(v) = prefs.edit().putString("media_adapter_url", v.trim()).apply()

    var pollSeconds: Int
        get() = prefs.getInt("poll_seconds", 30)
        set(v) = prefs.edit().putInt("poll_seconds", v.coerceIn(15, 900)).apply()

    /** Hold a partial wake lock while the service runs: lowest latency, highest battery cost. */
    var lowLatency: Boolean
        get() = prefs.getBoolean("low_latency", false)
        set(v) = prefs.edit().putBoolean("low_latency", v).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(v) = prefs.edit().putBoolean("service_enabled", v).apply()

    var apiToken: String?
        get() = secrets.read("api_token")
        set(v) = if (v.isNullOrBlank()) secrets.delete("api_token") else secrets.write("api_token", v.trim())

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && !apiToken.isNullOrBlank()

    var alertPrefs: AlertPrefs
        get() = prefs.getString("alert_prefs", null)?.let {
            runCatching { HermesJson.decodeFromString(AlertPrefs.serializer(), it) }.getOrNull()
        } ?: AlertPrefs()
        set(v) = prefs.edit().putString("alert_prefs", HermesJson.encodeToString(AlertPrefs.serializer(), v)).apply()

    fun updateAlertPrefs(f: (AlertPrefs) -> AlertPrefs) {
        alertPrefs = f(alertPrefs)
    }

    companion object {
        fun toolModeToWire(m: ToolActivityMode) = m.ordinal
        fun toolModeFromWire(v: Int) = ToolActivityMode.entries.getOrElse(v) { ToolActivityMode.HIDDEN }
        fun replyModeToWire(m: ReplyAlertMode) = m.ordinal
        fun replyModeFromWire(v: Int) = ReplyAlertMode.entries.getOrElse(v) { ReplyAlertMode.NOTIFY }
        fun scopeLabel(s: AlertScope) = when (s) {
            AlertScope.ACTIVE_SESSION -> "Active session"
            AlertScope.VISIBLE_SESSIONS -> "Visible sessions"
            AlertScope.ALL_ELIGIBLE -> "All sessions"
        }
    }
}

/**
 * AES-GCM encryption with a non-exportable Android Keystore key. The ciphertext lives in
 * app-private prefs that are excluded from backup; revocation = clearing it here and rotating
 * API_SERVER_KEY on the Hermes host.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("wristbound_secrets", Context.MODE_PRIVATE)

    fun write(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun read(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return runCatching {
            val (iv, ct) = blob.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }

    fun delete(name: String) = prefs.edit().remove(name).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "wristbound_secret_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
