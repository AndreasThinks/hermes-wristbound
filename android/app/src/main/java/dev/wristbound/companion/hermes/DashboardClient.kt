package dev.wristbound.companion.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The Hermes operations a turn needs. [DashboardClient] is the real implementation. */
interface HermesApi {
    suspend fun listSessions(limit: Int = 50): List<HermesSession>

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
 * (`/api/ws`), and audio comes from `/api/files/stream` and `/api/audio/speak`. Telegram,
 * CLI and desktop sessions all live in the one session store behind it, so any of them can
 * be read and continued. See docs/HERMES_API.md.
 */
class DashboardClient(
    val auth: DashboardAuth,
    http: OkHttpClient = HermesHttp.base,
) : HermesApi {
    private val http = http.newBuilder().addInterceptor(auth.interceptor).build()
    // Speech synthesis and audio downloads can take a while for long replies.
    private val slowHttp = this.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    // OkHttp's own pings keep NAT and the tailnet path warm; the gateway adds its JSON heartbeat.
    private val wsHttp = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val json = HermesHttp.json

    override suspend fun listSessions(limit: Int): List<HermesSession> = withContext(Dispatchers.IO) {
        val url = url("api/sessions") {
            addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            addQueryParameter("order", "recent")
        }
        json.decodeFromString(SessionList.serializer(), get(url)).sessions
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
        json.decodeFromString(MessageList.serializer(), get(url)).messages
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
            val url = url("api/files/stream") { addQueryParameter("path", serverPath) }
            slowHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw HermesHttp.error(response)
                val body = response.body ?: throw IOException("Empty audio")
                if (body.contentLength() > MAX_AUDIO_BYTES) throw IOException("Audio too large")
                val tmp = File(dir, target.name + ".part")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                tmp.renameTo(target)
            }
        }
        AudioClip(target, mimeFor(ext))
    }

    /**
     * Speak [text] in the voice configured under `tts:` in Hermes, as the desktop's read-aloud
     * does (`POST /api/audio/speak`). Cached under [key], usually the message id.
     */
    suspend fun speak(text: String, key: String, dir: File): AudioClip = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("voice-${sha1(key)}.") }?.firstOrNull()?.let {
            return@withContext AudioClip(it, mimeFor(it.extension))
        }
        val payload = buildJsonObject { put("text", text) }
        val request = Request.Builder().url(url("api/audio/speak") {})
            .post(payload.toString().toRequestBody(HermesHttp.JSON_MEDIA)).build()
        val body = slowHttp.newCall(request).execute().use(HermesHttp::body)
        val obj = json.parseToJsonElement(body) as? JsonObject
        val dataUrl = (obj?.get("data_url") as? JsonPrimitive)?.contentOrNull ?: throw IOException("Hermes returned no audio")
        val (mime, bytes) = decodeDataUrl(dataUrl)
        val target = File(dir, "voice-${sha1(key)}.${extensionFor(mime)}")
        target.writeBytes(bytes)
        AudioClip(target, mime)
    }

    private fun get(url: HttpUrl): String = http.newCall(Request.Builder().url(url).build()).execute().use(HermesHttp::body)

    private fun url(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        auth.base.newBuilder().addEncodedPathSegments(path).apply(block).build()

    companion object {
        private const val MAX_AUDIO_BYTES = 50L * 1024 * 1024

        fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray> {
            val comma = dataUrl.indexOf(',')
            require(dataUrl.startsWith("data:") && comma > 0) { "Not a data URL" }
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifBlank { "audio/mpeg" }
            return mime to java.util.Base64.getMimeDecoder().decode(dataUrl.substring(comma + 1))
        }

        fun mimeFor(ext: String): String = when (ext.lowercase()) {
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }

        fun extensionFor(mime: String): String = when (mime.lowercase()) {
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mp4", "audio/aac", "audio/x-m4a" -> "m4a"
            "audio/flac" -> "flac"
            else -> "mp3"
        }

        private fun sha1(value: String): String = java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }
}
