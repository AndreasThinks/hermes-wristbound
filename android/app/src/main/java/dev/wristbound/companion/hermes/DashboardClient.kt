package dev.wristbound.companion.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The Hermes operations a turn needs. [DashboardClient] is the real implementation. */
interface HermesApi {
    /** Most recently active sessions, leaving out any whose source is in [excludeSources]. */
    suspend fun listSessions(limit: Int = 50, excludeSources: List<String> = emptyList()): List<HermesSession>

    /** Most recent [limit] messages of a session, oldest first. */
    suspend fun recentMessages(sessionId: String, limit: Int): List<HermesMessage>

    /** Open the gateway socket the desktop app uses to run turns in any session. */
    suspend fun openGateway(): Gateway
}

/** Joins the wristbound-voice plugin's records onto the transcript rows they belong to. */
object VoiceReplies {
    /**
     * [messages] with each ready voice reply's clips on its assistant row, and [HermesMessage.audio_pending]
     * on a row whose voice is still being made. A pending turn the plugin has not tied to a row
     * yet (it does so as the turn finishes) applies to the newest final answer written after it began.
     */
    fun merge(messages: List<HermesMessage>, replies: List<VoiceReply>): List<HermesMessage> {
        if (replies.isEmpty()) return messages
        val byMessage = replies.filter { it.message_id != null }.associateBy { it.message_id!! }
        val unbound = replies.filter { it.message_id == null && it.pending }.maxByOrNull { it.created_at }
        val unboundRow = unbound?.let { u ->
            messages.lastOrNull { it.isFinalAssistantText && (it.timestamp ?: 0.0) >= u.created_at && it.stableId !in byMessage }?.stableId
        }
        return messages.map { m ->
            val reply = byMessage[m.stableId]
            when {
                reply?.status == "ready" && reply.clips.isNotEmpty() -> m.copy(audio_attachments = reply.clips, audio_pending = false)
                reply?.pending == true || m.stableId == unboundRow -> m.copy(audio_pending = true)
                else -> m
            }
        }
    }
}

/** Audio Hermes made, ready to play from a local file. */
data class AudioClip(val file: File, val mimeType: String)

/**
 * Client for the Hermes dashboard: the same surface the desktop app and Hermes-Relay use.
 * Sessions and history come from its REST routes, turns run over the gateway socket
 * (`/api/ws`), and audio comes from `/api/files/stream` (or the wristbound-voice plugin). Telegram,
 * CLI and desktop sessions all live in the one session store behind it, so any of them can
 * be read and continued. See docs/HERMES_API.md.
 */
class DashboardClient(
    val auth: DashboardAuth,
    http: OkHttpClient = HermesHttp.base,
) : HermesApi {
    private val http = http.newBuilder().addInterceptor(auth.interceptor).build()
    // Audio downloads can take a while for long replies.
    private val slowHttp = this.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    // OkHttp's own pings keep NAT and the tailnet path warm; the gateway adds its JSON heartbeat.
    private val wsHttp = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    // The idle listener pings rarely: each ping wakes the phone and the radio. Over Tailscale the
    // tunnel keeps the path open, so this only has to notice a socket that died without a close.
    private val listenHttp = http.newBuilder().pingInterval(LISTENER_PING_MINUTES, TimeUnit.MINUTES).readTimeout(0, TimeUnit.SECONDS).build()

    override suspend fun listSessions(limit: Int, excludeSources: List<String>): List<HermesSession> = withContext(Dispatchers.IO) {
        val url = url("api/sessions") {
            addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            addQueryParameter("order", "recent")
            if (excludeSources.isNotEmpty()) addQueryParameter("exclude_sources", excludeSources.joinToString(","))
        }
        HermesHttp.decode(SessionList.serializer(), get(url), "a session list").sessions
    }

    override suspend fun recentMessages(sessionId: String, limit: Int): List<HermesMessage> = withContext(Dispatchers.IO) {
        val url = url("api/sessions") {
            addPathSegment(sessionId)
            addPathSegment("messages")
            addQueryParameter("limit", limit.toString())
            addQueryParameter("order", "latest")
            addQueryParameter("inline_images", "false")
        }
        // The latest page comes back in display order; sort defensively by row id and time.
        val page = HermesHttp.decode(MessageList.serializer(), get(url), "a history")
        val messages = page.messages.distinctBy { it.stableId }
            .sortedWith(compareBy({ it.timestamp ?: 0.0 }, { it.stableId.toLongOrNull() ?: 0L }))
        VoiceReplies.merge(messages, voiceReplies(sessionId))
    }

    /** When the wristbound-voice plugin last answered 404; it is not asked again for a while. */
    @Volatile private var voicePluginMissingAt = 0L

    /**
     * The voice replies the wristbound-voice Hermes plugin made in [sessionId]. Empty when the
     * plugin is not installed or cannot be reached: history still loads, as text.
     */
    private fun voiceReplies(sessionId: String): List<VoiceReply> {
        if (System.currentTimeMillis() - voicePluginMissingAt < VOICE_PLUGIN_RETRY_MS) return emptyList()
        val url = url("api/plugins/$VOICE_PLUGIN/sessions") {
            addPathSegment(sessionId)
            addPathSegment("replies")
        }
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 404) {
                    voicePluginMissingAt = System.currentTimeMillis()
                    return emptyList()
                }
                HermesHttp.decode(VoiceReplyList.serializer(), HermesHttp.body(response), "voice replies").replies
            }
        } catch (e: IOException) {
            emptyList() // includes a reply Wristbound cannot read: the plugin is optional
        }
    }

    override suspend fun openGateway(): Gateway {
        val (name, value) = auth.webSocketCredential(http)
        val url = url("api/ws") { addQueryParameter(name, value) }
        return GatewaySocket.connect(wsHttp, Request.Builder().url(url).build())
    }

    /**
     * Open a gateway socket that only listens for broadcasts such as `sessions.changed`: no
     * JSON heartbeat, rare pings, and no `client.capabilities`, so Hermes never routes an approval
     * to it.
     */
    suspend fun openListener(): Gateway {
        val (name, value) = auth.webSocketCredential(http)
        val url = url("api/ws") { addQueryParameter(name, value) }
        return GatewaySocket.connect(listenHttp, Request.Builder().url(url).build(), heartbeat = false)
    }

    /**
     * Download audio Hermes produced into [dir]: a `MEDIA:` path, or `attachment:<id>` for a
     * voice reply clip from the wristbound-voice plugin. Cached by name.
     */
    suspend fun fetchAudio(serverPath: String, dir: File): AudioClip = withContext(Dispatchers.IO) {
        val ext = serverPath.substringAfterLast('.', "mp3").lowercase()
        val target = File(dir, "hermes-${sha1(serverPath)}.$ext")
        if (!target.exists()) {
            val url = if (serverPath.startsWith("attachment:")) {
                url("api/plugins/$VOICE_PLUGIN/clips") { addPathSegment(serverPath.removePrefix("attachment:")) }
            } else url("api/files/stream") { addQueryParameter("path", serverPath) }
            slowHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw HermesHttp.error(response)
                val body = response.body ?: throw IOException("Empty audio")
                if (body.contentLength() > MAX_AUDIO_BYTES) throw IOException("Audio too large")
                val tmp = File(dir, target.name + ".part")
                try {
                    tmp.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_AUDIO_BYTES) throw IOException("Audio too large")
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) throw IOException("Could not cache audio")
                } finally {
                    tmp.delete()
                }
            }
        }
        AudioClip(target, mimeFor(ext))
    }

    private fun get(url: HttpUrl): String = http.newCall(Request.Builder().url(url).build()).execute().use(HermesHttp::body)

    private fun url(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        auth.base.newBuilder().addEncodedPathSegments(path).apply(block).build()

    companion object {
        private const val MAX_AUDIO_BYTES = 50L * 1024 * 1024
        private const val LISTENER_PING_MINUTES = 5L
        /** The Hermes plugin that makes voice replies (hermes-plugin/wristbound-voice). */
        const val VOICE_PLUGIN = "wristbound-voice"
        private const val VOICE_PLUGIN_RETRY_MS = 10 * 60 * 1000L

        fun mimeFor(ext: String): String = when (ext.lowercase()) {
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }

        private fun sha1(value: String): String = java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
