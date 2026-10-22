package dev.wristbound.companion.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A request Hermes answered with an HTTP error. [code] is Hermes's machine-readable error code. */
class HermesHttpException(val status: Int, val code: String?, message: String) : IOException(message) {
    val isAuth: Boolean get() = status == 401 || status == 403
    /** The session belongs to another surface (CLI, desktop, Telegram...) and the API may not drive it. */
    val isForeignSession: Boolean get() = status == 409 && code == "permission_denied"
}

/** Events from one streamed session turn (`POST /api/sessions/{id}/chat/stream`). */
sealed class TurnEvent {
    data class Started(val runId: String?) : TurnEvent()
    data class Delta(val text: String) : TurnEvent()
    data class Tool(val name: String, val phase: String) : TurnEvent()
    data class AssistantCompleted(val content: String) : TurnEvent()
    /** Terminal: status is "completed", "failed" or "cancelled". */
    data class Finished(val status: String, val reason: String?) : TurnEvent()
    data class Error(val message: String) : TurnEvent()
}

/** The Hermes operations Wristbound needs; [HermesClient] is the HTTP implementation. */
interface HermesApi {
    suspend fun listSessions(limit: Int = 50, source: String? = null): List<HermesSession>
    suspend fun recentMessages(sessionId: String, limit: Int): List<HermesMessage>
    suspend fun createSession(title: String?): HermesSession
    suspend fun runStatus(runId: String): RunStatus
    suspend fun streamTurn(sessionId: String, message: String, idempotencyKey: String, onEvent: suspend (TurnEvent) -> Unit)
}

/**
 * Thin client for the Hermes API server (`hermes gateway` with `API_SERVER_ENABLED=true`).
 * Only documented routes are used; see docs/HERMES_API.md.
 */
class HermesClient(
    baseUrl: String,
    private val apiKey: String,
    http: OkHttpClient = defaultHttp,
) : HermesApi {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid Hermes URL: $baseUrl")

    private val http = http
    // Streams stay open for the whole agent turn; Hermes sends keepalives every 10s.
    private val streamHttp = http.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        http.newCall(request("health").build()).execute().use { it.isSuccessful }
    }

    override suspend fun listSessions(limit: Int, source: String?): List<HermesSession> =
        withContext(Dispatchers.IO) {
            val url = url("api/sessions") {
                addQueryParameter("limit", limit.toString())
                source?.let { addQueryParameter("source", it) }
            }
            val body = execute(Request.Builder().url(url).auth().build())
            json.decodeFromString(SessionList.serializer(), body).data
        }

    suspend fun getSession(sessionId: String): HermesSession = withContext(Dispatchers.IO) {
        val body = execute(request("api/sessions/${seg(sessionId)}").build())
        json.decodeFromString(SessionEnvelope.serializer(), body).session
    }

    /** Most recent [limit] messages, oldest first. */
    override suspend fun recentMessages(sessionId: String, limit: Int): List<HermesMessage> = withContext(Dispatchers.IO) {
        val url = url("api/sessions/${seg(sessionId)}/messages") {
            addQueryParameter("limit", limit.toString())
            addQueryParameter("order", "latest")
            addQueryParameter("inline_images", "false")
        }
        val body = execute(Request.Builder().url(url).auth().build())
        val messages = json.decodeFromString(MessageList.serializer(), body).data
        // `latest` pages may come newest-first or oldest-first depending on version; normalise.
        messages.sortedWith(compareBy({ it.timestamp ?: 0.0 }, { it.stableId.toLongOrNull() ?: 0L }))
    }

    override suspend fun createSession(title: String?): HermesSession = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("source", "api_server")
            title?.let { put("title", it) }
        }
        val body = execute(request("api/sessions").post(payload.toString().toRequestBody(JSON)).build())
        json.decodeFromString(SessionEnvelope.serializer(), body).session
    }

    override suspend fun runStatus(runId: String): RunStatus = withContext(Dispatchers.IO) {
        json.decodeFromString(RunStatus.serializer(), execute(request("v1/runs/${seg(runId)}").build()))
    }

    /**
     * Run one agent turn in [sessionId] and stream its events to [onEvent]. Returns when the
     * stream ends. Throws [HermesHttpException] if Hermes refuses the turn before it starts, or
     * [IOException] if the connection fails; in the latter case the caller must treat the outcome
     * as uncertain once [TurnEvent.Started] was seen, and must not resend.
     */
    override suspend fun streamTurn(
        sessionId: String,
        message: String,
        idempotencyKey: String,
        onEvent: suspend (TurnEvent) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("message", message) }
        val req = request("api/sessions/${seg(sessionId)}/chat/stream")
            .header("Accept", "text/event-stream")
            .header("Idempotency-Key", idempotencyKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        streamHttp.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            val source = response.body?.source() ?: throw IOException("Empty stream")
            val parser = SseParser()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val event = parser.feed(line) ?: continue
                mapEvent(event)?.let { onEvent(it) }
            }
            val tail = parser.flush()
            if (tail != null) mapEvent(tail)?.let { onEvent(it) }
        }
    }

    private fun request(path: String) = Request.Builder().url(url(path) {}).auth()

    private fun Request.Builder.auth() = header("Authorization", "Bearer $apiKey")

    private fun url(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        base.newBuilder().addEncodedPathSegments(path).apply(block).build()

    private fun seg(value: String): String = HttpUrl.Builder().scheme("http").host("x")
        .addPathSegment(value).build().encodedPathSegments.first()

    private fun execute(request: Request): String = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw httpError(response)
        response.body?.string().orEmpty()
    }

    private fun httpError(response: Response): HermesHttpException {
        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val err = runCatching { json.decodeFromString(ErrorEnvelope.serializer(), raw).error }.getOrNull()
        return HermesHttpException(response.code, err?.code, err?.message ?: "HTTP ${response.code}")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun mapEvent(event: SseEvent): TurnEvent? {
            val obj = runCatching { json.parseToJsonElement(event.data) as? JsonObject }.getOrNull()
            fun field(name: String) = obj?.get(name)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            return when (event.name) {
                "run.started" -> TurnEvent.Started(field("run_id"))
                "assistant.delta" -> TurnEvent.Delta(field("delta").orEmpty())
                "tool.started", "tool.completed", "tool.failed" ->
                    TurnEvent.Tool(field("tool_name") ?: "tool", event.name.removePrefix("tool."))
                "assistant.completed" -> TurnEvent.AssistantCompleted(field("content").orEmpty())
                "run.completed", "run.failed", "run.cancelled" ->
                    TurnEvent.Finished(event.name.removePrefix("run."), field("turn_exit_reason") ?: field("error"))
                "error" -> TurnEvent.Error(field("message") ?: "Hermes error")
                else -> null
            }
        }
    }
}
