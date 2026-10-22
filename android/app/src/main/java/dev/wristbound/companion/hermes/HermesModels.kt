package dev.wristbound.companion.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Client-side views of the Hermes dashboard's session surface (`/api/sessions*`), the same rows
 * the desktop app reads. Only fields Wristbound uses are modelled; unknown fields are ignored.
 * See docs/HERMES_API.md.
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
    val sessions: List<HermesSession> = emptyList(),
    val total: Int? = null,
)

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

    /** The integer transcript row id, when Hermes gives one. Later rows have larger ids. */
    val rowId: Long?
        get() = (id as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

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

    /**
     * Server paths named by `MEDIA:<path>` audio tags in this row. The text-to-speech tool
     * returns one in its tool result, and the agent usually repeats it in the reply. Whether a
     * path is Hermes' answer or the user's own recording is decided per turn by
     * `SessionMapper.replies`, not here.
     *
     * Automatic voice replies (Telegram voice mode, auto-TTS after a voice note) never appear:
     * Hermes sends them straight to the platform and deletes the file, without a transcript
     * row. See docs/HERMES_API.md.
     */
    val audioRefs: List<String>
        get() = AUDIO_TAG.findAll(text.replace("\\/", "/"))
            .map { it.groupValues[1] }.distinct().toList()
}

private val AUDIO_TAG = Regex(
    """MEDIA:([^\s"'<>\\]+?\.(?:mp3|ogg|opus|wav|m4a|aac|flac))(?![\w.])""",
    RegexOption.IGNORE_CASE,
)

@Serializable
data class MessageList(
    val session_id: String? = null,
    val messages: List<HermesMessage> = emptyList(),
)

/** `GET /api/status`: whether the dashboard requires sign-in, and which flows it offers. */
@Serializable
data class DashboardStatus(
    val auth_required: Boolean = false,
    val auth_providers: List<String> = emptyList(),
    val auth_flows: List<String> = emptyList(),
    val version: String? = null,
)

/** One interactive sign-in provider from `GET /api/auth/providers`. */
@Serializable
data class AuthProvider(
    val name: String,
    val display_name: String? = null,
    val supports_password: Boolean = false,
)

@Serializable
data class AuthProviderList(val providers: List<AuthProvider> = emptyList())

/** Bearer tokens from `/auth/native/token` and `/auth/native/refresh`. [expires_at] is epoch seconds. */
@Serializable
data class NativeTokens(
    val access_token: String,
    val refresh_token: String = "",
    val expires_at: Double = 0.0,
    val provider: String = "",
    val user_id: String = "",
)
