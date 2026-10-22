package dev.wristbound.companion.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client-side views of the Hermes API server's session surface (`/api/sessions*`).
 * Only fields Wristbound uses are modelled; unknown fields are ignored. See docs/HERMES_API.md.
 */
@Serializable
data class HermesSession(
    val id: String,
    val source: String? = null,
    val title: String? = null,
    val preview: String? = null,
    val started_at: Double? = null,
    val ended_at: Double? = null,
    val last_active: Double? = null,
    val message_count: Int? = null,
    val archived: Boolean? = null,
    val hidden: Boolean? = null,
    val is_internal_child: Boolean? = null,
) {
    val isTelegram: Boolean get() = source.equals("telegram", ignoreCase = true)

    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: preview?.takeIf { it.isNotBlank() }?.lineSequence()?.first()?.take(40)
            ?: "Untitled"

    /** Most recent activity, for unread tracking. */
    val activity: Double get() = last_active ?: ended_at ?: started_at ?: 0.0
}

@Serializable
data class SessionList(
    val data: List<HermesSession> = emptyList(),
    val has_more: Boolean = false,
)

@Serializable
data class SessionEnvelope(val session: HermesSession)

@Serializable
data class HermesMessage(
    val id: JsonElement? = null,
    val session_id: String? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    val tool_name: String? = null,
    val tool_calls: JsonElement? = null,
    val timestamp: Double? = null,
    val finish_reason: String? = null,
) {
    /** Hermes message ids are integers today; keep them opaque strings on our side. */
    val stableId: String
        get() = (id as? JsonPrimitive)?.contentOrNull
            ?: "${session_id.orEmpty()}:${timestamp ?: 0.0}:${role.orEmpty()}"

    /** Plain text of the content, whether it is a string or a list of multimodal parts. */
    val text: String
        get() = when (val c = content) {
            null -> ""
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull (part as? JsonPrimitive)?.contentOrNull
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text", "input_text", "output_text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                    "image_url", "input_image" -> "[image]"
                    else -> null
                }
            }.joinToString("\n")
            else -> ""
        }

    val hasToolCalls: Boolean
        get() = when (val t = tool_calls) {
            null -> false
            is JsonArray -> t.isNotEmpty()
            is JsonPrimitive -> !t.contentOrNull.isNullOrBlank() && t.contentOrNull != "null"
            else -> true
        }

    /** A finished assistant answer, as opposed to a tool-calling step. */
    val isFinalAssistantText: Boolean
        get() = role == "assistant" && !hasToolCalls && text.isNotBlank()

    /** References to server-side audio that Hermes emitted as MEDIA tags. */
    val audioRefs: List<String>
        get() = AUDIO_TAG.findAll(text).map { it.groupValues[1] }.toList()
}

private val AUDIO_TAG = Regex("""MEDIA:(\S+\.(?:mp3|ogg|opus|wav|m4a|aac|flac))""", RegexOption.IGNORE_CASE)

@Serializable
data class MessageList(
    val session_id: String? = null,
    val data: List<HermesMessage> = emptyList(),
)

@Serializable
data class RunStatus(
    val run_id: String? = null,
    val status: String? = null,
    val session_id: String? = null,
    val output: String? = null,
    val error: String? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(val message: String? = null, val code: String? = null, val type: String? = null)
