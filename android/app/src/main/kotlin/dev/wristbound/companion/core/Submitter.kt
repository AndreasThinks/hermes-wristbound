package dev.wristbound.companion.core

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Submits user turns exactly once from the user's point of view.
 *
 *  - A watch retransmission (same `watchSubmitId`) never creates a second submission.
 *  - Every submission carries a stable `Idempotency-Key`; retries reuse it, so Hermes replays
 *    the original run instead of executing the task again.
 *  - Retries happen only when Hermes advertises durable run idempotency. Otherwise an
 *    uncertain outcome is reported as [SubmissionState.UNCERTAIN] and never resent.
 */
class Submitter(
    private val maxAttempts: Int = 4,
    private val backoffMs: (Int) -> Long = { attempt -> 1000L * (1 shl (attempt - 1)).coerceAtMost(8) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val state: SyncState, val submission: Submission, val duplicate: Boolean)

    fun idempotencyKey(installId: String, watchSubmitId: Long?, nonce: String): String =
        if (watchSubmitId != null) "wristbound-$installId-w$watchSubmitId" else "wristbound-$installId-$nonce"

    /**
     * @param onUpdate called after each state transition so the UI/watch can reflect progress.
     */
    suspend fun submit(
        api: HermesApi,
        state: SyncState,
        sessionId: String,
        text: String,
        watchSubmitId: Long?,
        nonce: String,
        durableIdempotency: Boolean,
        onUpdate: suspend (SyncState, Submission) -> Unit = { _, _ -> },
    ): Result {
        if (watchSubmitId != null) {
            state.submissionByWatchId(watchSubmitId)?.let { existing ->
                return Result(state, existing, duplicate = true)
            }
        }
        val now = clock()
        var sub = Submission(
            key = idempotencyKey(state.installId, watchSubmitId, nonce),
            watchSubmitId = watchSubmitId,
            sessionId = sessionId,
            textPreview = text.take(40),
            state = SubmissionState.PENDING,
            createdAt = now,
            updatedAt = now,
        )
        var st = state.upsertSubmission(sub)
        onUpdate(st, sub)

        var attempt = 0
        while (true) {
            attempt++
            val outcome = api.submitRun(sessionId, text, sub.key)
            sub = when (outcome) {
                is SubmitOutcome.Accepted -> sub.copy(
                    state = SubmissionState.ACCEPTED, runId = outcome.runId, attempts = attempt,
                    detail = if (outcome.replayed) "replayed" else null, updatedAt = clock(),
                )
                is SubmitOutcome.Rejected -> if (outcome.kind == HermesErrorKind.RATE_LIMITED && attempt < maxAttempts) {
                    sub.copy(attempts = attempt, detail = "rate limited, retrying", updatedAt = clock())
                } else {
                    sub.copy(state = SubmissionState.FAILED, attempts = attempt, detail = outcome.message, updatedAt = clock())
                }
                is SubmitOutcome.Uncertain -> if (durableIdempotency && attempt < maxAttempts) {
                    sub.copy(attempts = attempt, detail = "retrying: ${outcome.message}", updatedAt = clock())
                } else {
                    sub.copy(
                        state = SubmissionState.UNCERTAIN, attempts = attempt,
                        detail = "Outcome unknown (${outcome.message}). Check the session before resending.",
                        updatedAt = clock(),
                    )
                }
            }
            st = st.upsertSubmission(sub)
            if (sub.state == SubmissionState.ACCEPTED && sub.runId != null) {
                st = st.copy(activeRuns = (st.activeRuns.filterNot { it.runId == sub.runId } + ActiveRun(sub.runId!!, sessionId, sub.key)).takeLast(10))
            }
            onUpdate(st, sub)
            if (sub.state != SubmissionState.PENDING) return Result(st, sub, duplicate = false)
            delay(backoffMs(attempt))
        }
    }

    companion object {
        /** `features.runs_idempotency.durable` from /v1/capabilities. */
        fun durableIdempotency(features: JsonObject): Boolean {
            val ri = features["runs_idempotency"] as? JsonObject ?: return false
            val supported = (ri["supported"] as? JsonPrimitive)?.booleanOrNull ?: false
            val durable = (ri["durable"] as? JsonPrimitive)?.booleanOrNull ?: false
            return supported && durable
        }
    }
}
