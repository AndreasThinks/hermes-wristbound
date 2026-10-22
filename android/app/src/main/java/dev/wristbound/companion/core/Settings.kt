package dev.wristbound.companion.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User settings. The Hermes API key is encrypted with an Android Keystore key and never leaves the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_URL, v.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_TOKEN, null)?.let { runCatching { Secrets.decrypt(it) }.getOrNull() }.orEmpty()
        set(v) {
            if (v.isBlank()) prefs.edit().remove(KEY_TOKEN).apply()
            else prefs.edit().putString(KEY_TOKEN, Secrets.encrypt(v.trim())).apply()
        }

    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()

    var includeTelegram: Boolean
        get() = prefs.getBoolean("include_telegram", true)
        set(v) = prefs.edit().putBoolean("include_telegram", v).apply()

    var notifyReplies: Boolean
        get() = prefs.getBoolean("notify_replies", true)
        set(v) = prefs.edit().putBoolean("notify_replies", v).apply()

    var notifyTelegram: Boolean
        get() = prefs.getBoolean("notify_telegram", false)
        set(v) = prefs.edit().putBoolean("notify_telegram", v).apply()

    var notifyScope: NotifyScope
        get() = enumValueOrDefault(prefs.getString("notify_scope", null), NotifyScope.ALL_ELIGIBLE)
        set(v) = prefs.edit().putString("notify_scope", v.name).apply()

    var toolActivity: ToolActivity
        get() = enumValueOrDefault(prefs.getString("tool_activity", null), ToolActivity.HIDDEN)
        set(v) = prefs.edit().putString("tool_activity", v.name).apply()

    /** Hide reply text in phone notifications (and so in the Pebble's mirrored copy). */
    var privatePreviews: Boolean
        get() = prefs.getBoolean("private_previews", false)
        set(v) = prefs.edit().putBoolean("private_previews", v).apply()

    var backgroundSync: Boolean
        get() = prefs.getBoolean("background_sync", false)
        set(v) = prefs.edit().putBoolean("background_sync", v).apply()

    var pollSeconds: Int
        get() = prefs.getInt("poll_seconds", 60).coerceIn(15, 900)
        set(v) = prefs.edit().putInt("poll_seconds", v.coerceIn(15, 900)).apply()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_URL = "hermes_url"
        const val KEY_TOKEN = "hermes_token"
    }
}

private object Secrets {
    private const val ALIAS = "wristbound-hermes-token"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
