package dev.wristbound.companion.core

import android.content.Context
import dev.wristbound.companion.hermes.HermesHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Small, bounded on-device state: unread baselines, alerted reply ids and polling checkpoints
 * (for deduplication and catch-up), recent alert decisions, sessions Hermes refused to continue, and a cache of what the
 * watch last showed. Everything lives in app-private storage, excluded from backups.
 */
class LocalState(context: Context) : AlertStore {
    private val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val cacheDir = File(context.noBackupFilesDir, "cache").apply { mkdirs() }
    private val json = HermesHttp.json

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
    override fun isAlerted(key: String): Boolean = key in readList("notified")

    @Synchronized
    override fun markAlerted(key: String): Boolean {
        val list = readList("notified")
        if (key in list) return false
        // commit(): an alert must be on disk before it can be shown, or a restart repeats it.
        prefs.edit().putString("notified", json.encodeToString(ListSerializer(String.serializer()), (list + key).takeLast(MAX_NOTIFIED))).commit()
        return true
    }

    @Synchronized
    override fun unmarkAlerted(key: String) {
        val list = readList("notified")
        if (key !in list) return
        prefs.edit().putString("notified", json.encodeToString(ListSerializer(String.serializer()), list - key)).commit()
    }

    override var pollCursors: PollCursors?
        @Synchronized get() = prefs.getString("poll_cursors", null)?.let {
            runCatching { json.decodeFromString(PollCursors.serializer(), it) }.getOrNull()
        }
        @Synchronized set(v) {
            val edit = prefs.edit()
            if (v == null) edit.remove("poll_cursors") else edit.putString("poll_cursors", json.encodeToString(PollCursors.serializer(), v))
            edit.commit()
        }

    /** Recent alert decisions, newest first. */
    @Synchronized
    fun recentAlerts(): List<AlertRecord> = prefs.getString("alert_log", null)?.let {
        runCatching { json.decodeFromString(ListSerializer(AlertRecord.serializer()), it) }.getOrNull()
    }.orEmpty()

    @Synchronized
    fun recordAlert(record: AlertRecord) {
        val list = (listOf(record) + recentAlerts()).take(AlertRecord.MAX)
        prefs.edit().putString("alert_log", json.encodeToString(ListSerializer(AlertRecord.serializer()), list)).apply()
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

    @Serializable
    data class CachedAudio(val fetchedAt: Long, val items: List<AudioItem>)

    fun saveAudio(items: List<AudioItem>) =
        write("audio.json", json.encodeToString(CachedAudio.serializer(), CachedAudio(now(), items)))

    fun loadAudio(): List<AudioItem> =
        read("audio.json")?.let { runCatching { json.decodeFromString(CachedAudio.serializer(), it).items }.getOrNull() }.orEmpty()

    /** Audio replies the user has opened on the watch, so the inbox stops marking them new. */
    @Synchronized
    fun isHeard(messageId: String): Boolean = messageId in readList("heard")

    @Synchronized
    fun markHeard(messageId: String) {
        val list = readList("heard")
        if (messageId !in list) writeList("heard", (list + messageId).takeLast(MAX_SEEN))
    }

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
        const val MAX_NOTIFIED = 1000
        const val MAX_CACHED_SESSIONS = 10
    }
}
