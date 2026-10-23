package dev.wristbound.companion.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One frame Hermes pushed on the gateway socket: an event (`message.delta`, `message.complete`,
 * `tool.start`, ...) or, when [requestId] is set, a request Hermes is asking a client to answer
 * (`approval`, `clarify`, `sudo`, ...), which [Gateway.answer] or [Gateway.decline] settles.
 * A client gets those requests only after `client.capabilities {server_requests: true}`.
 */
data class GatewayEvent(
    val type: String,
    val sessionId: String?,
    val payload: JsonObject,
    val requestId: String? = null,
)

/** Hermes answered a JSON-RPC call with an error. Nothing was started by that call. */
class GatewayRpcException(val code: Int, message: String) : Exception(message)

/** The gateway socket closed or failed. Calls in flight may or may not have reached Hermes. */
class GatewayClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The JSON-RPC surface of Hermes' `tui_gateway`, reached through the dashboard's `/api/ws`. */
interface Gateway : AutoCloseable {
    /** Events in arrival order. Closed, with the cause, when the socket ends. */
    val events: ReceiveChannel<GatewayEvent>

    /**
     * Send one request and wait for its answer. Throws [GatewayRpcException] for an error
     * answer and [GatewayClosedException] if the socket ends first. [onSent] runs once the
     * request has been handed to the socket.
     */
    suspend fun call(method: String, params: JsonObject, timeoutMs: Long = 30_000, onSent: () -> Unit = {}): JsonObject

    /** Answer a request Hermes sent ([GatewayEvent.requestId]). False if the socket is gone. */
    fun answer(requestId: String, result: JsonObject): Boolean

    /**
     * Turn down a request this app cannot answer. With [NOT_SHOWN] another app showing the
     * session (the desktop) can still answer it; Hermes gives up only once every app declined.
     */
    fun decline(requestId: String, code: Int, message: String): Boolean

    override fun close()

    companion object {
        /** `server_requests.NOT_SHOWN_CODE`: "not mine to answer". */
        const val NOT_SHOWN = 4404
    }
}

/**
 * [Gateway] over an OkHttp WebSocket. The wire format is newline-delimited JSON-RPC 2.0; the
 * server sends `gateway.ready` first and expects a `gateway.ping` every 15 seconds.
 */
class GatewaySocket private constructor() : Gateway, WebSocketListener() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val ids = AtomicInteger(0)
    private val ready = CompletableDeferred<Unit>()
    private val channel = Channel<GatewayEvent>(Channel.UNLIMITED)
    private lateinit var socket: WebSocket
    @Volatile private var closedCause: Throwable? = null

    override val events: ReceiveChannel<GatewayEvent> get() = channel

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long, onSent: () -> Unit): JsonObject {
        closedCause?.let { throw GatewayClosedException("Gateway closed", it) }
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            if (!socket.send(frame.toString())) throw GatewayClosedException("Gateway closed")
            onSent()
            return withTimeoutOrNull(timeoutMs) { answer.await() }
                ?: throw GatewayClosedException("No answer from Hermes to $method")
        } finally {
            pending.remove(id)
        }
    }

    override fun answer(requestId: String, result: JsonObject): Boolean = closedCause == null &&
        socket.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", requestId); put("result", result) }.toString())

    override fun decline(requestId: String, code: Int, message: String): Boolean = closedCause == null &&
        socket.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("error", buildJsonObject { put("code", code); put("message", message) })
            }.toString(),
        )

    override fun close() {
        socket.close(1000, null)
        shutdown(GatewayClosedException("Closed by Wristbound"))
    }

    // -- WebSocketListener ----------------------------------------------------------------------

    override fun onMessage(webSocket: WebSocket, text: String) {
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val obj = runCatching { HermesHttp.json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            dispatch(obj)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        shutdown(closeCause(code, reason))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = shutdown(closeCause(code, reason))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val cause = when (response?.code) {
            401, 403 -> HermesHttpException(response.code, "unauthorized", "Hermes rejected the sign-in")
            404 -> HermesHttpException(404, "not_found", "This Hermes has no gateway socket; update Hermes")
            else -> GatewayClosedException(t.message ?: "Gateway connection failed", t)
        }
        shutdown(cause)
    }

    private fun dispatch(obj: JsonObject) {
        val method = obj.string("method")
        val id = obj["id"]
        when {
            method == "event" -> {
                val params = obj["params"] as? JsonObject ?: return
                val type = params.string("type") ?: return
                if (type == "gateway.ready") {
                    ready.complete(Unit)
                    return
                }
                channel.trySend(GatewayEvent(type, params.string("session_id"), params["payload"] as? JsonObject ?: JsonObject(emptyMap())))
            }
            method != null -> {
                // A request from Hermes to its clients: an approval, clarification or password prompt.
                val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
                channel.trySend(GatewayEvent(method, params.string("session_id"), params, (id as? JsonPrimitive)?.contentOrNull))
            }
            else -> {
                val key = (id as? JsonPrimitive)?.intOrNull ?: return
                val answer = pending[key] ?: return
                val error = obj["error"] as? JsonObject
                if (error != null) {
                    val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: -1
                    answer.completeExceptionally(GatewayRpcException(code, error.string("message") ?: "Hermes error $code"))
                } else {
                    answer.complete(obj["result"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            }
        }
    }

    private fun shutdown(cause: Throwable) {
        if (closedCause != null) return
        closedCause = cause
        ready.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(GatewayClosedException(cause.message ?: "Gateway closed", cause)) }
        channel.close(cause)
        scope.cancel()
    }

    private fun closeCause(code: Int, reason: String): Throwable = when (code) {
        4401 -> HermesHttpException(401, "unauthorized", "Hermes rejected the sign-in")
        4403 -> HermesHttpException(403, "forbidden", "Hermes refused this connection")
        else -> GatewayClosedException("Gateway closed ($code${if (reason.isNotBlank()) " $reason" else ""})")
    }

    private fun startHeartbeat() = scope.launch {
        while (isActive) {
            delay(HEARTBEAT_MS)
            val ok = withTimeoutOrNull(HEARTBEAT_MS) {
                runCatching { call("gateway.ping", JsonObject(emptyMap()), HEARTBEAT_MS) }.isSuccess
            } ?: false
            if (!ok) {
                socket.cancel()
                shutdown(GatewayClosedException("Gateway stopped answering"))
            }
        }
    }

    companion object {
        private const val HEARTBEAT_MS = 15_000L

        /** Open [request] (an `http(s)://.../api/ws?...` URL) and wait for `gateway.ready`. */
        suspend fun connect(http: OkHttpClient, request: Request, readyTimeoutMs: Long = 20_000): GatewaySocket {
            val gateway = GatewaySocket()
            gateway.socket = http.newWebSocket(request, gateway)
            try {
                withTimeout(readyTimeoutMs) { gateway.ready.await() }
            } catch (e: Throwable) {
                gateway.socket.cancel()
                gateway.shutdown(e)
                throw if (e is IOException) e else GatewayClosedException("Gateway did not become ready", e)
            }
            gateway.startHeartbeat()
            return gateway
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/** Convenience for building RPC params. */
internal fun params(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> Unit
        is String -> put(k, v)
        is Boolean -> put(k, v)
        is Number -> put(k, v)
        is JsonElement -> put(k, v)
        else -> put(k, v.toString())
    }
}
