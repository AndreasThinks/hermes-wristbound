package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesApi
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.TurnEvent
import dev.wristbound.companion.pebble.Protocol
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

sealed class TurnOutcome {
    data class Completed(val content: String) : TurnOutcome()
    data class Failed(val detail: String) : TurnOutcome()
    data object Cancelled : TurnOutcome()
    /** Hermes refused because another surface (Telegram, CLI, desktop) owns the session. */
    data object ReadOnly : TurnOutcome()
    /** The connection broke after Hermes may have accepted the turn. It was not resent. */
    data class Uncertain(val detail: String) : TurnOutcome()
    data object AuthFailed : TurnOutcome()
}

/**
 * Runs one user turn against Hermes and classifies the result. The one rule that matters:
 * a turn is submitted at most once. After a broken stream the runner can only observe the run
 * (`GET /v1/runs/{id}`); it never retries the submission.
 */
class TurnRunner(
    private val api: HermesApi,
    private val pollIntervalMs: Long = 5_000,
    private val pollTimeoutMs: Long = 15 * 60_000,
) {
    suspend fun run(
        sessionId: String,
        text: String,
        idempotencyKey: String,
        onProgress: suspend (Protocol.Submit, String) -> Unit,
        onTool: suspend (String) -> Unit = {},
    ): TurnOutcome {
        var started = false
        var runId: String? = null
        var answer: String? = null
        var terminal: TurnEvent.Finished? = null
        var streamError: String? = null

        onProgress(Protocol.Submit.QUEUED, "Sending to Hermes")
        try {
            api.streamTurn(sessionId, text, idempotencyKey) { event ->
                when (event) {
                    is TurnEvent.Started -> {
                        started = true
                        runId = event.runId
                        onProgress(Protocol.Submit.WORKING, "Hermes is working")
                    }
                    is TurnEvent.Tool -> if (event.phase == "started") onTool(event.name)
                    is TurnEvent.AssistantCompleted -> answer = event.content
                    is TurnEvent.Finished -> terminal = event
                    is TurnEvent.Error -> streamError = event.message
                    is TurnEvent.Delta -> Unit
                }
            }
        } catch (e: HermesHttpException) {
            return when {
                e.isForeignSession -> TurnOutcome.ReadOnly
                e.isAuth -> TurnOutcome.AuthFailed
                e.status == 429 -> TurnOutcome.Failed("Hermes busy, try later")
                e.status == 404 -> TurnOutcome.Failed("Session not found")
                e.status == 503 -> TurnOutcome.Failed("Hermes unavailable")
                else -> TurnOutcome.Failed(e.message ?: "Hermes error ${e.status}")
            }
        } catch (e: IOException) {
            if (!started && neverReachedServer(e)) return TurnOutcome.Failed("Hermes unreachable")
            val id = runId ?: return TurnOutcome.Uncertain("Connection lost; not resent")
            onProgress(Protocol.Submit.WORKING, "Reconnecting to run")
            return observe(id)
        }

        terminal?.let { return finish(it, answer) }
        streamError?.let { return TurnOutcome.Failed(it.take(60)) }
        // The stream closed without a terminal event.
        val id = runId ?: return TurnOutcome.Uncertain("Stream ended early; not resent")
        return observe(id)
    }

    private fun finish(event: TurnEvent.Finished, answer: String?): TurnOutcome = when (event.status) {
        "completed" -> TurnOutcome.Completed(answer.orEmpty())
        "cancelled" -> TurnOutcome.Cancelled
        else -> TurnOutcome.Failed(event.reason?.take(60) ?: "Hermes could not finish")
    }

    /** Poll the run until it settles. Polling never resubmits work. */
    private suspend fun observe(runId: String): TurnOutcome {
        var waited = 0L
        while (waited <= pollTimeoutMs) {
            val status = try {
                api.runStatus(runId)
            } catch (e: HermesHttpException) {
                return if (e.status == 404) TurnOutcome.Uncertain("Run no longer tracked") else TurnOutcome.Uncertain("Run status unavailable")
            } catch (e: IOException) {
                null
            }
            when (status?.status) {
                "completed" -> return TurnOutcome.Completed(status.output.orEmpty())
                "failed", "interrupted" -> return TurnOutcome.Failed(status.error?.take(60) ?: "Hermes could not finish")
                "cancelled" -> return TurnOutcome.Cancelled
                "unknown" -> return TurnOutcome.Uncertain("Hermes restarted mid-turn")
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
        }
        return TurnOutcome.Uncertain("Still running; check later")
    }

    private fun neverReachedServer(e: IOException): Boolean =
        e is ConnectException || e is UnknownHostException || e is NoRouteToHostException || e is SSLHandshakeException
}
