package dev.wristbound.companion.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.wristbound.companion.hermes.BoundCredentialStore
import dev.wristbound.companion.hermes.BoundCredentials
import dev.wristbound.companion.hermes.CredentialStore
import dev.wristbound.companion.hermes.Credentials
import dev.wristbound.companion.hermes.EndpointCredentials
import dev.wristbound.companion.hermes.HermesHttp
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * User settings. Hermes credentials (sign-in tokens or a session token) are encrypted with an
 * Android Keystore key and never leave the phone. They are stored with the dashboard address
 * that issued them and only used for that address (see [EndpointCredentials]).
 */
class Settings(context: Context) : BoundCredentialStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * The active dashboard: the one last signed in to successfully. The setup screen only
     * changes it once a sign-in to a new, validated address has worked.
     */
    var baseUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(v) { prefs.edit().putString(KEY_URL, v.trim()).commit() }

    @Synchronized
    override fun loadBound(): BoundCredentials? {
        val stored = prefs.getString(KEY_CREDENTIALS, null) ?: return null
        val credentials = runCatching { HermesHttp.json.decodeFromString(Credentials.serializer(), Secrets.decrypt(stored)) }.getOrNull()
            ?: return null
        // Credentials saved before they were bound to an address belong to the active one.
        val endpoint = prefs.getString(KEY_CREDENTIALS_URL, null) ?: baseUrl
        return BoundCredentials(endpoint, credentials)
    }

    @Synchronized
    override fun saveBound(bound: BoundCredentials?) {
        // commit(): a rotated refresh token must be on disk before the old one is forgotten.
        if (bound == null) prefs.edit().remove(KEY_CREDENTIALS).remove(KEY_CREDENTIALS_URL).commit()
        else prefs.edit()
            .putString(KEY_CREDENTIALS, Secrets.encrypt(HermesHttp.json.encodeToString(Credentials.serializer(), bound.credentials)))
            .putString(KEY_CREDENTIALS_URL, bound.endpoint)
            .commit()
    }

    override var signInExpired: Boolean
        get() = prefs.getBoolean("sign_in_expired", false)
        set(v) { prefs.edit().putBoolean("sign_in_expired", v).commit() }

    /** Credentials for [endpoint], and only for it. */
    fun credentialsFor(endpoint: String): CredentialStore = EndpointCredentials(endpoint, this)

    /** Credentials for the active dashboard. */
    val credentials: Credentials? get() = if (baseUrl.isBlank()) null else credentialsFor(baseUrl).load()

    val isConfigured: Boolean get() = credentials != null

    /**
     * What the watchapp opens on ([dev.wristbound.companion.pebble.Protocol.QuickLaunch]), or
     * null until it is chosen here: the watch's own earlier choice is then adopted on HELLO.
     */
    var quickLaunch: Int?
        get() = prefs.getInt("quick_launch", -1).takeIf { it >= 0 }
        set(v) = prefs.edit().apply { if (v == null) remove("quick_launch") else putInt("quick_launch", v) }.apply()

    /** Show chats from Telegram and other messaging platforms on the watch. */
    var showMessaging: Boolean
        get() = prefs.getBoolean("include_telegram", true)
        set(v) = prefs.edit().putBoolean("include_telegram", v).apply()

    /** Show scheduled and automation sessions (cron, Kanban workers, webhooks…) on the watch. */
    var showAutomation: Boolean
        get() = prefs.getBoolean("show_automation", false)
        set(v) = prefs.edit().putBoolean("show_automation", v).apply()

    /** Allow sending from the watch into Telegram sessions. Off keeps them read and play only. */
    var allowTelegramReplies: Boolean
        get() = prefs.getBoolean("allow_telegram_replies", true)
        set(v) = prefs.edit().putBoolean("allow_telegram_replies", v).apply()

    // Alerts, one switch per kind of reply (see AlertPolicy). Each new switch starts from the
    // setting it replaces, so an upgrade keeps what the user chose.

    /** Master switch for reply alerts. */
    var notifyReplies: Boolean
        get() = prefs.getBoolean("notify_replies", true)
        set(v) = prefs.edit().putBoolean("notify_replies", v).apply()

    /** Replies that carry Hermes audio, from any session. */
    var alertAudio: Boolean
        get() = prefs.getBoolean("alert_audio", prefs.getBoolean("notify_telegram_audio", true))
        set(v) = prefs.edit().putBoolean("alert_audio", v).apply()

    /** Replies to messages sent from the watch. */
    var alertWatchTurns: Boolean
        get() = prefs.getBoolean("alert_watch_turns", true)
        set(v) = prefs.edit().putBoolean("alert_watch_turns", v).apply()

    /** Replies in chats on the desktop, terminal or web UI. Off if alerts were limited to the open session. */
    var alertChats: Boolean
        get() = prefs.getBoolean("alert_chats", prefs.getString("notify_scope", null) != "OPEN_SESSION")
        set(v) = prefs.edit().putBoolean("alert_chats", v).apply()

    /** Text replies in Telegram and other messaging chats. Off by default: those apps notify the phone already. */
    var alertMessagingText: Boolean
        get() = prefs.getBoolean("alert_messaging_text", prefs.getBoolean("notify_telegram", false))
        set(v) = prefs.edit().putBoolean("alert_messaging_text", v).apply()

    /** Replies in scheduled and automation sessions, and to turns Hermes started itself. */
    var alertBackground: Boolean
        get() = prefs.getBoolean("alert_background", false)
        set(v) = prefs.edit().putBoolean("alert_background", v).apply()

    var toolActivity: ToolActivity
        get() = enumValueOrDefault(prefs.getString("tool_activity", null), ToolActivity.HIDDEN)
        set(v) = prefs.edit().putString("tool_activity", v.name).apply()

    /** Hide reply text in phone notifications (and so in the Pebble's mirrored copy). */
    var privatePreviews: Boolean
        get() = prefs.getBoolean("private_previews", false)
        set(v) = prefs.edit().putBoolean("private_previews", v).apply()

    /** On by default: without it, replies from other surfaces go unnoticed once the watchapp closes. */
    var backgroundSync: Boolean
        get() = prefs.getBoolean("background_sync", true)
        set(v) = prefs.edit().putBoolean("background_sync", v).apply()

    var pollSeconds: Int
        get() = prefs.getInt("poll_seconds", 60).coerceIn(15, 900)
        set(v) = prefs.edit().putInt("poll_seconds", v.coerceIn(15, 900)).apply()

    /** When reply checking last reached Hermes, in wall-clock ms; 0 if never. */
    var lastCheckAt: Long
        get() = prefs.getLong("last_check_at", 0L)
        set(v) = prefs.edit().putLong("last_check_at", v).apply()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val KEY_URL = "hermes_url"
        const val KEY_CREDENTIALS = "hermes_credentials"
        const val KEY_CREDENTIALS_URL = "hermes_credentials_url"
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
