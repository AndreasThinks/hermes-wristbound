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

/** Audio Hermes made, ready to play from a local file. */
data class AudioClip(val file: File, val mimeType: String)

/**
 * Client for the Hermes dashboard: the same surface the desktop app and Hermes-Relay use.
 * Sessions and history come from its REST routes, turns run over the gateway socket
 * (`/api/ws`), and audio comes from `/api/files/stream`. Telegram,
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
            addQueryParameter("include_audio", "true")
        }
        // The latest page comes back in display order; sort defensively by row id and time.
        val page = HermesHttp.decode(MessageList.serializer(), get(url), "a history")
        (page.messages + page.audio_messages).distinctBy { it.stableId }
            .sortedWith(compareBy({ it.timestamp ?: 0.0 }, { it.stableId.toLongOrNull() ?: 0L }))
    }

    override suspend fun openGateway(): Gateway {
        val (name, value) = auth.webSocketCredential(http)
        val url = url("api/ws") { addQueryParameter(name, value) }
        return GatewaySocket.connect(wsHttp, Request.Builder().url(url).build())
    }

    /** Download audio Hermes produced (a `MEDIA:` path) into [dir]. Cached by path. */
    suspend fun fetchAudio(serverPath: String, dir: File): AudioClip = withContext(Dispatchers.IO) {
        val ext = serverPath.substringAfterLast('.', "mp3").lowercase()
        val target = File(dir, "hermes-${sha1(serverPath)}.$ext")
        if (!target.exists()) {
            val url = if (serverPath.startsWith("attachment:")) {
                url("api/reply-audio") { addPathSegment(serverPath.removePrefix("attachment:")) }
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
