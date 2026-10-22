package dev.wristbound.companion.core

import kotlinx.serialization.json.JsonObject

/** The subset of the Hermes API server the companion uses. Implemented by [HermesClient]. */
interface HermesApi {
    suspend fun capabilities(): JsonObject
    suspend fun listSessions(limit: Int = 50, offset: Int = 0): SessionListResponse
    suspend fun latestMessages(sessionId: String, limit: Int = 30): MessageListResponse
    suspend fun createSession(sessionId: String, title: String?): Boolean
    suspend fun submitRun(sessionId: String, text: String, idempotencyKey: String): SubmitOutcome
    suspend fun getRun(runId: String): RunStatus
    suspend fun streamRunEvents(runId: String, lastSeq: Long?, onEvent: suspend (RunEvent) -> Unit)
}
