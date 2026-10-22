package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.Gateway
import dev.wristbound.companion.hermes.GatewayEvent
import dev.wristbound.companion.hermes.GatewayRpcException
import dev.wristbound.companion.hermes.HermesApi
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.params
import dev.wristbound.companion.pebble.Protocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

sealed class TurnOutcome {
    data class Completed(val content: String) : TurnOutcome()
    data class Failed(val detail: String) : TurnOutcome()
    data object Cancelled : TurnOutcome()
    /** Hermes refused to continue this session (it guards some, such as Kanban worker transcripts). */
    data class ReadOnly(val detail: String) : TurnOutcome()
    /** The connection broke after Hermes may have accepted the turn. It was not resent. */
    data class Uncertain(val detail: String) : TurnOutcome()
    data object AuthFailed : TurnOutcome()
}

/**
 * Runs one user turn through the Hermes gateway socket, the way the desktop app does:
 * `session.resume` attaches to the stored session (whichever surface started it, Telegram
 * included), `prompt.submit` sends the text, and events stream until `message.complete`.
 *
 * The one rule that matters: a turn is submitted at most once. Once `prompt.submit` has left
 * the phone, a broken socket is only ever followed by observing (re-attaching to the live
 * session, or reading the transcript); the prompt is never sent again.
 */
class TurnRunner(
    private val api: HermesApi,
    private val recoverIntervalMs: Long = 5_000,
    private val recoverTimeoutMs: Long = 15 * 60_000,
    private val turnTimeoutMs: Long = 30 * 60_000,
) {
    /**
     * Send [text] into [sessionId], or into a new session when it is null; [onCreated] then
     * receives the new session's id before the prompt is sent.
     */
    suspend fun run(
        sessionId: String?,
        text: String,
        onProgress: suspend (Protocol.Submit, String) -> Unit,
        onTool: suspend (String) -> Unit = {},
        onCreated: suspend (String) -> Unit = {},
    ): TurnOutcome {
        onProgress(Protocol.Submit.QUEUED, "Sending to Hermes")
        val gateway = try {
            api.openGateway()
        } catch (e: HermesHttpException) {
            return if (e.isAuth) TurnOutcome.AuthFailed else TurnOutcome.Failed(e.message.orEmpty().take(60))
        } catch (e: IOException) {
            return TurnOutcome.Failed("Hermes unreachable")
        }

        var stored = sessionId
        var submitted = false
        try {
            val runtime = if (sessionId != null) {
                val resumed = gateway.call(
                    "session.resume",
                    params("session_id" to sessionId, "omit_messages" to true, "inline_images" to false),
                    RESUME_TIMEOUT_MS,
                )
                resumed.string("session_id") ?: return TurnOutcome.Failed("Hermes did not open the session")
            } else {
                val created = gateway.call("session.create", params("source" to NEW_SESSION_SOURCE))
                stored = created.string("stored_session_id")
                stored?.let { onCreated(it) }
                created.string("session_id") ?: return TurnOutcome.Failed("Hermes did not create a session")
            }
            // queued=true: if a turn is already running here, wait behind it instead of
            // redirecting it, which is Hermes' default for a busy session.
            val ack = gateway.call(
                "prompt.submit",
                params("session_id" to runtime, "text" to text, "queued" to true),
                SUBMIT_TIMEOUT_MS,
                onSent = { submitted = true },
            )
            val queued = ack.string("status") == "queued"
            onProgress(Protocol.Submit.WORKING, if (queued) "Queued behind a running turn" else "Hermes is working")
            return listen(gateway, runtime, awaitStart = queued, onProgress, onTool)
                ?: TurnOutcome.Uncertain("Still running; check later")
        } catch (e: GatewayRpcException) {
            // An error answer means Hermes did not start anything for that call.
            return rpcFailure(e)
        } catch (e: HermesHttpException) {
            if (!submitted) return if (e.isAuth) TurnOutcome.AuthFailed else TurnOutcome.Failed(e.message.orEmpty().take(60))
        } catch (e: IOException) {
            if (!submitted) return TurnOutcome.Failed("Connection lost; not sent")
        } finally {
            gateway.close()
        }
        val target = stored ?: return TurnOutcome.Uncertain("Connection lost; not resent")
        return recover(target, text, onProgress, onTool)
    }

    /**
     * Follow [runtime]'s events until the turn ends. Returns null on timeout and throws
     * [IOException] if the socket ends first. With [awaitStart], a completion seen before
     * this turn's `message.start` belongs to the turn it queued behind.
     */
    private suspend fun listen(
        gateway: Gateway,
        runtime: String,
        awaitStart: Boolean,
        onProgress: suspend (Protocol.Submit, String) -> Unit,
        onTool: suspend (String) -> Unit,
    ): TurnOutcome? = withTimeoutOrNull(turnTimeoutMs) {
        var started = !awaitStart
        try {
            for (event in gateway.events) {
                if (event.sessionId != null && event.sessionId != runtime) continue
                when (event.type) {
                    "message.start" -> {
                        if (!started) onProgress(Protocol.Submit.WORKING, "Hermes is working")
                        started = true
                    }
                    "tool.start" -> if (started) onTool(event.payload.string("name") ?: "a tool")
                    "message.complete" -> if (started) return@withTimeoutOrNull completion(event)
                    "error" -> return@withTimeoutOrNull TurnOutcome.Failed(event.payload.string("message")?.take(60) ?: "Hermes error")
                    in WAITS_FOR_ANSWER -> onProgress(Protocol.Submit.WORKING, "Needs an answer on desktop")
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw IOException(e.message, e)
        }
        throw IOException("Gateway closed")
    }

    private fun completion(event: GatewayEvent): TurnOutcome {
        val p = event.payload
        return when (p.string("status")) {
            "interrupted" -> TurnOutcome.Cancelled
            "error" -> TurnOutcome.Failed((p.string("error") ?: p.string("failure_reason") ?: "Hermes could not finish").take(60))
            else -> TurnOutcome.Completed(p.string("text").orEmpty())
        }
    }

    /**
     * The socket broke after the prompt left the phone. Look, never resend: first the
     * transcript, then re-attach to the live session (`session.resume` joins a running turn
     * and streams the rest of it).
     */
    private suspend fun recover(
        sessionId: String,
        text: String,
        onProgress: suspend (Protocol.Submit, String) -> Unit,
        onTool: suspend (String) -> Unit,
    ): TurnOutcome {
        onProgress(Protocol.Submit.WORKING, "Reconnecting to Hermes")
        var waited = 0L
        while (waited <= recoverTimeoutMs) {
            when (val seen = answerInTranscript(sessionId, text)) {
                is Seen.Answered -> return TurnOutcome.Completed(seen.text)
                else -> Unit
            }
            try {
                api.openGateway().use { gateway ->
                    val resumed = gateway.call(
                        "session.resume",
                        params("session_id" to sessionId, "omit_messages" to true, "inline_images" to false),
                        RESUME_TIMEOUT_MS,
                    )
                    val runtime = resumed.string("session_id")
                    val running = (resumed["running"] as? JsonPrimitive)?.booleanOrNull == true
                    if (runtime != null && running) {
                        return listen(gateway, runtime, awaitStart = false, onProgress, onTool)
                            ?: TurnOutcome.Uncertain("Still running; check later")
                    }
                    // Idle: the turn already ended. The transcript is the authority.
                    return when (val seen = answerInTranscript(sessionId, text)) {
                        is Seen.Answered -> TurnOutcome.Completed(seen.text)
                        Seen.Asked -> TurnOutcome.Uncertain("Hermes got it but did not answer")
                        Seen.Unknown -> TurnOutcome.Uncertain("Not sure Hermes got it; not resent")
                    }
                }
            } catch (e: GatewayRpcException) {
                return TurnOutcome.Uncertain("Could not check the session; not resent")
            } catch (e: HermesHttpException) {
                if (e.isAuth) return TurnOutcome.Uncertain("Signed out mid-turn; not resent")
            } catch (e: IOException) {
                // Still offline; try again shortly.
            }
            delay(recoverIntervalMs)
            waited += recoverIntervalMs
        }
        return TurnOutcome.Uncertain("Lost Hermes mid-turn; not resent")
    }

    private sealed class Seen {
        data class Answered(val text: String) : Seen()
        data object Asked : Seen()
        data object Unknown : Seen()
    }

    private suspend fun answerInTranscript(sessionId: String, text: String): Seen {
        val messages = try {
            api.recentMessages(sessionId, 12)
        } catch (e: IOException) {
            return Seen.Unknown
        }
        val asked = messages.indexOfLast { it.role == "user" && it.text.trim() == text.trim() }
        if (asked < 0) return Seen.Unknown
        val answer = messages.drop(asked + 1).lastOrNull { it.isFinalAssistantText } ?: return Seen.Asked
        return Seen.Answered(answer.text)
    }

    private fun rpcFailure(e: GatewayRpcException): TurnOutcome = when (e.code) {
        4132 -> TurnOutcome.ReadOnly(e.message.orEmpty().ifBlank { "Hermes keeps this session read-only" })
        4007 -> TurnOutcome.Failed("Session not found")
        4009 -> TurnOutcome.Failed("Hermes is busy here")
        4130 -> TurnOutcome.Failed("Session too long to resume")
        else -> TurnOutcome.Failed(e.message.orEmpty().ifBlank { "Hermes error ${e.code}" }.take(60))
    }

    private companion object {
        const val RESUME_TIMEOUT_MS = 60_000L
        /** Hermes acks `prompt.submit` once the turn thread starts; allow for a slow agent build. */
        const val SUBMIT_TIMEOUT_MS = 120_000L
        /** Existing rich-chat source label, as Hermes-Relay uses; Hermes has no Wristbound-specific one. */
        const val NEW_SESSION_SOURCE = "webui"
        val WAITS_FOR_ANSWER = setOf("approval.request", "clarify.request", "sudo.request", "secret.request")

        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
