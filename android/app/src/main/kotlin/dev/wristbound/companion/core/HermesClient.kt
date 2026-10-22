package dev.wristbound.companion.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Failure categories that matter to the caller's retry/uncertainty decisions. */
enum class HermesErrorKind { UNAUTHORIZED, NOT_FOUND, CONFLICT, RATE_LIMITED, BAD_REQUEST, SERVER, NETWORK, PROTOCOL }

class HermesException(
    val kind: HermesErrorKind,
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Result of `POST /v1/runs`. Only [Accepted] proves Hermes admitted the turn. */
sealed class SubmitOutcome {
    data class Accepted(val runId: String, val replayed: Boolean) : SubmitOutcome()

    /** Definitely not executed (auth, validation, idempotency conflict, rate limit). */
    data class Rejected(val kind: HermesErrorKind, val message: String) : SubmitOutcome()

    /** Request may or may not have reached Hermes (timeout, connection reset, 5xx). */
    data class Uncertain(val message: String) : SubmitOutcome()
}

/**
 * Minimal client for the Hermes API server.
 *
 * Endpoints (hermes-agent v0.22.2, see docs/API-CONTRACT.md):
 *  - GET  /health, /v1/capabilities
 *  - GET  /api/sessions, POST /api/sessions, GET /api/sessions/{id}/messages
 *  - POST /v1/runs (Idempotency-Key), GET /v1/runs/{id}, GET /v1/runs/{id}/events (SSE)
 *
 * Authentication is `Authorization: Bearer <API_SERVER_KEY>`. Tailscale reachability is not
 * treated as authentication; every request carries the bearer token.
 */
class HermesClient(
    baseUrl: String,
    private val token: () -> String?,
    httpClient: OkHttpClient = defaultHttpClient(),
) : HermesApi {
    private val base: HttpUrl = normalizeBaseUrl(baseUrl)
    private val http = httpClient
    private val streamHttp = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    suspend fun health(): Boolean = io {
        http.newCall(Request.Builder().url(url("health")).get().build()).execute().use { it.isSuccessful }
    }

    /** Returns the `features` object of /v1/capabilities. */
    override suspend fun capabilities(): JsonObject = io {
        val body = execute(authed(url("v1", "capabilities")).get().build())
        HermesJson.parseToJsonElement(body).jsonObject["features"]?.jsonObject ?: JsonObject(emptyMap())
    }

    override suspend fun listSessions(limit: Int, offset: Int): SessionListResponse = io {
        val u = url("api", "sessions").newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        HermesJson.decodeFromString(SessionListResponse.serializer(), execute(authed(u).get().build()))
    }

    /** Latest [limit] messages of a session in chronological order (`order=latest`). */
    override suspend fun latestMessages(sessionId: String, limit: Int): MessageListResponse = io {
        val u = url("api", "sessions", sessionId, "messages").newBuilder()
            .addQueryParameter("order", "latest")
            .addQueryParameter("limit", limit.toString())
            .build()
        HermesJson.decodeFromString(MessageListResponse.serializer(), execute(authed(u).get().build()))
    }

    /**
     * Creates an empty session with a client-chosen id, so a retried create cannot mint a
     * second session. Returns true when created now, false when it already existed (409).
     */
    override suspend fun createSession(sessionId: String, title: String?): Boolean = io {
        val payload = buildJsonObject {
            put("id", sessionId)
            if (!title.isNullOrBlank()) put("title", title)
        }
        try {
            execute(authed(url("api", "sessions")).post(payload.toString().toRequestBody(JSON)).build())
            true
        } catch (e: HermesException) {
            if (e.kind == HermesErrorKind.CONFLICT) false else throw e
        }
    }

    /**
     * Submits one user turn into an existing session via `POST /v1/runs` with `session_id`.
     * The same [idempotencyKey] must be reused for retries of the same logical submission;
     * Hermes stores it durably (`runs_idempotency.db`) and replays the original run.
     */
    override suspend fun submitRun(sessionId: String, text: String, idempotencyKey: String): SubmitOutcome = io {
        val payload = buildJsonObject {
            put("input", text)
            put("session_id", sessionId)
        }
        val request = authed(url("v1", "runs"))
            .header("Idempotency-Key", idempotencyKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.code == 202 || response.isSuccessful -> {
                        val accepted = HermesJson.decodeFromString(RunAccepted.serializer(), body)
                        SubmitOutcome.Accepted(
                            accepted.runId,
                            accepted.replayed || response.header("Idempotency-Replayed") == "true",
                        )
                    }
                    response.code >= 500 -> SubmitOutcome.Uncertain("HTTP ${response.code}: ${errorMessage(body)}")
                    else -> SubmitOutcome.Rejected(kindFor(response.code), "HTTP ${response.code}: ${errorMessage(body)}")
                }
            }
        } catch (e: IOException) {
            SubmitOutcome.Uncertain(e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun getRun(runId: String): RunStatus = io {
        HermesJson.decodeFromString(RunStatus.serializer(), execute(authed(url("v1", "runs", runId)).get().build()))
    }

    /**
     * Streams `/v1/runs/{id}/events`, resuming after [lastSeq] via `Last-Event-ID`.
     * Blocks until the server closes the stream. Callers persist the last seen seq to resume.
     */
    override suspend fun streamRunEvents(runId: String, lastSeq: Long?, onEvent: suspend (RunEvent) -> Unit) {
        val builder = authed(url("v1", "runs", runId, "events")).header("Accept", "text/event-stream").get()
        if (lastSeq != null) builder.header("Last-Event-ID", lastSeq.toString())
        val response = io { streamHttp.newCall(builder.build()).execute() }
        response.use {
            if (!it.isSuccessful) throw httpError(it.code, io { it.body.string() })
            val source = it.body.source()
            val parser = SseParser()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = io { source.readUtf8Line() } ?: break
                parser.feed(line)?.let { frame -> frame.toRunEvent()?.let { ev -> onEvent(ev) } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun url(vararg segments: String): HttpUrl {
        val b = base.newBuilder()
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun authed(u: HttpUrl): Request.Builder {
        val b = Request.Builder().url(u).header("Accept", "application/json")
        val t = token()
        if (!t.isNullOrBlank()) b.header("Authorization", "Bearer $t")
        return b
    }

    private fun execute(request: Request): String {
        val response: Response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw HermesException(HermesErrorKind.NETWORK, e.message ?: "network error", cause = e)
        }
        response.use {
            val body = it.body.string()
            if (!it.isSuccessful) throw httpError(it.code, body)
            return body
        }
    }

    private fun httpError(code: Int, body: String) =
        HermesException(kindFor(code), "HTTP $code: ${errorMessage(body)}", code)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun normalizeBaseUrl(raw: String): HttpUrl {
            val trimmed = raw.trim().trimEnd('/')
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            return withScheme.toHttpUrl()
        }

        internal fun kindFor(code: Int): HermesErrorKind = when (code) {
            401, 403 -> HermesErrorKind.UNAUTHORIZED
            404, 410 -> HermesErrorKind.NOT_FOUND
            409 -> HermesErrorKind.CONFLICT
            429 -> HermesErrorKind.RATE_LIMITED
            in 400..499 -> HermesErrorKind.BAD_REQUEST
            else -> HermesErrorKind.SERVER
        }

        /** Extracts `error.message` from Hermes/OpenAI-style error bodies, redacted to a short length. */
        internal fun errorMessage(body: String): String {
            val msg = runCatching {
                val obj = HermesJson.parseToJsonElement(body).jsonObject
                val err = obj["error"]
                when (err) {
                    is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                    is JsonPrimitive -> err.contentOrNull
                    else -> null
                }
            }.getOrNull() ?: body
            return msg.take(200)
        }
    }
}

/** Incremental `text/event-stream` parser (WHATWG SSE subset: id, event, data). */
class SseParser {
    data class Frame(val id: String?, val event: String?, val data: String)

    private var id: String? = null
    private var event: String? = null
    private val data = StringBuilder()

    /** Feed one line (without terminator). Returns a frame when a blank line completes one. */
    fun feed(line: String): Frame? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event == null) {
                id = null
                return null
            }
            val frame = Frame(id, event, data.toString().removeSuffix("\n"))
            id = null; event = null; data.setLength(0)
            return frame
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> id = value
            "event" -> event = value
            "data" -> data.append(value).append('\n')
        }
        return null
    }
}

internal fun SseParser.Frame.toRunEvent(): RunEvent? {
    val obj = runCatching { HermesJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    val name = event ?: (obj["event"] as? JsonPrimitive)?.contentOrNull ?: return null
    val seq = id?.toLongOrNull() ?: (obj["seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    return RunEvent(seq, name, obj)
}
