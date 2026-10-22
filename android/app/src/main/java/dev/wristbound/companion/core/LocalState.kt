package dev.wristbound.companion.core

import android.content.Context
import dev.wristbound.companion.hermes.HermesClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Small, bounded on-device state: unread baselines, notified message ids (for deduplication),
 * sessions known to be read-only through the API, and a cache of what the watch last showed.
 * Everything lives in app-private storage, excluded from backups.
 */
class LocalState(context: Context) {
    private val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val cacheDir = File(context.noBackupFilesDir, "cache").apply { mkdirs() }
    private val json = HermesClient.json

    @Synchronized
    fun lastSeen(): Map<String, Double> = readMap("last_seen")

    /** Mark a session as read up to [activity]. Unknown sessions get a baseline so they start read. */
    @Synchronized
    fun markSeen(sessionId: String, activity: Double) {
        val map = lastSeen().toMutableMap()
        map[sessionId] = maxOf(activity, map[sessionId] ?: 0.0)
        writeMap("last_seen", map.entries.sortedByDescending { it.value }.take(MAX_SEEN).associate { it.key to it.value })
    }

    @Synchronized
    fun baseline(sessions: Map<String, Double>) {
        val map = lastSeen().toMutableMap()
        var changed = false
        for ((id, activity) in sessions) if (id !in map) { map[id] = activity; changed = true }
        if (changed) writeMap("last_seen", map.entries.sortedByDescending { it.value }.take(MAX_SEEN).associate { it.key to it.value })
    }

    @Synchronized
    fun notified(): Set<String> = readList("notified").toSet()

    /** Record a notified message id. Returns false if it was already recorded (a duplicate). */
    @Synchronized
    fun markNotified(messageId: String): Boolean {
        val list = readList("notified")
        if (messageId in list) return false
        writeList("notified", (list + messageId).takeLast(MAX_NOTIFIED))
        return true
    }

    @Synchronized
    fun readOnly(): Set<String> = readList("read_only").toSet()

    @Synchronized
    fun markReadOnly(sessionId: String) {
        val list = readList("read_only")
        if (sessionId !in list) writeList("read_only", (list + sessionId).takeLast(MAX_SEEN))
    }

    var openSessionId: String?
        get() = prefs.getString("open_session", null)
        set(v) = prefs.edit().putString("open_session", v).apply()

    // -- watch cache --------------------------------------------------------------------------

    @Serializable
    data class CachedSessions(val fetchedAt: Long, val sessions: List<WatchSession>)

    @Serializable
    data class CachedMessages(val fetchedAt: Long, val title: String, val messages: List<WatchMessage>)

    fun saveSessions(sessions: List<WatchSession>) =
        write("sessions.json", json.encodeToString(CachedSessions.serializer(), CachedSessions(now(), sessions)))

    fun loadSessions(): CachedSessions? =
        read("sessions.json")?.let { runCatching { json.decodeFromString(CachedSessions.serializer(), it) }.getOrNull() }

    fun saveMessages(sessionId: String, title: String, messages: List<WatchMessage>) {
        write(messagesFile(sessionId), json.encodeToString(CachedMessages.serializer(), CachedMessages(now(), title, messages)))
        // Keep only the most recently used conversations on disk.
        cacheDir.listFiles { f -> f.name.startsWith("m_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_SESSIONS)
            ?.forEach { it.delete() }
    }

    fun loadMessages(sessionId: String): CachedMessages? =
        read(messagesFile(sessionId))?.let { runCatching { json.decodeFromString(CachedMessages.serializer(), it) }.getOrNull() }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
    }

    private fun messagesFile(sessionId: String) = "m_" + sessionId.hashCode().toUInt().toString(16) + ".json"

    private fun write(name: String, content: String) = File(cacheDir, name).writeText(content)

    private fun read(name: String): String? = File(cacheDir, name).takeIf { it.exists() }?.readText()

    private fun readMap(key: String): Map<String, Double> = prefs.getString(key, null)?.let {
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), Double.serializer()), it) }.getOrNull()
    } ?: emptyMap()

    private fun writeMap(key: String, map: Map<String, Double>) = prefs.edit()
        .putString(key, json.encodeToString(MapSerializer(String.serializer(), Double.serializer()), map)).apply()

    private fun readList(key: String): List<String> = prefs.getString(key, null)?.let {
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
    } ?: emptyList()

    private fun writeList(key: String, list: List<String>) = prefs.edit()
        .putString(key, json.encodeToString(ListSerializer(String.serializer()), list)).apply()

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val MAX_SEEN = 200
        const val MAX_NOTIFIED = 500
        const val MAX_CACHED_SESSIONS = 10
    }
}
