package dev.wristbound.companion.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire models for the Hermes API server (gateway platform `api_server`).
 *
 * Shapes are taken from hermes-agent v0.22.2 `gateway/platforms/api_server.py`
 * (`_session_response`, `_message_response`) and `api_server_runs.py`. Unknown fields are
 * ignored so newer servers stay compatible. See docs/API-CONTRACT.md for evidence.
 */
val HermesJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class SessionDto(
    val id: String,
    val source: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val hidden: Boolean = false,
    @SerialName("is_internal_child") val isInternalChild: Boolean = false,
)

@Serializable
data class SessionListResponse(
    val data: List<SessionDto> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class MessageDto(
    val id: Long,
    @SerialName("session_id") val sessionId: String? = null,
    val role: String,
    /** String, list of content parts, or null (assistant tool-call rows). */
    val content: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val timestamp: Double? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    @SerialName("display_kind") val displayKind: String? = null,
) {
    /** Flattened textual content. Content-part arrays keep only `text` parts. */
    val text: String
        get() = contentText(content)

    val hasToolCalls: Boolean
        get() = when (val tc = toolCalls) {
            null, JsonNull -> false
            is JsonArray -> tc.isNotEmpty()
            is JsonPrimitive -> tc.contentOrNull.let { !it.isNullOrBlank() && it != "[]" }
            is JsonObject -> true
        }
}

@Serializable
data class MessageListResponse(
    @SerialName("session_id") val sessionId: String? = null,
    val data: List<MessageDto> = emptyList(),
)

@Serializable
data class RunAccepted(
    @SerialName("run_id") val runId: String,
    val status: String? = null,
    val replayed: Boolean = false,
)

@Serializable
data class RunStatus(
    @SerialName("run_id") val runId: String,
    val status: String,
    @SerialName("session_id") val sessionId: String? = null,
    val output: String? = null,
    val error: String? = null,
    @SerialName("last_event") val lastEvent: String? = null,
) {
    val isTerminal: Boolean get() = status in TERMINAL_RUN_STATUSES
}

/** `TERMINAL_STATUSES` in api_server_run_idempotency.py. */
val TERMINAL_RUN_STATUSES = setOf("completed", "failed", "cancelled", "interrupted")

/** One `/v1/runs/{id}/events` SSE frame: `id: <seq>` + `data: {"event": ..., ...}`. */
data class RunEvent(val seq: Long?, val name: String, val payload: JsonObject) {
    fun string(key: String): String? = (payload[key] as? JsonPrimitive)?.contentOrNull
}

internal fun contentText(content: JsonElement?): String = when (content) {
    null, JsonNull -> ""
    is JsonPrimitive -> content.contentOrNull ?: ""
    is JsonArray -> content.mapNotNull { part ->
        val obj = part as? JsonObject ?: return@mapNotNull null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        if (type == null || type == "text" || type == "input_text" || type == "output_text") {
            (obj["text"] as? JsonPrimitive)?.contentOrNull
        } else {
            null
        }
    }.joinToString("\n")
    is JsonObject -> (content["text"] as? JsonPrimitive)?.contentOrNull ?: ""
}
