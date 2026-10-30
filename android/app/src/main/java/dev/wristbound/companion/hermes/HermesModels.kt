package dev.wristbound.companion.hermes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
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
    @Serializable(with = SessionFlag::class) val archived: Boolean? = null,
    @Serializable(with = SessionFlag::class) val hidden: Boolean? = null,
    @Serializable(with = SessionFlag::class) val is_internal_child: Boolean? = null,
    @Serializable(with = SessionFlag::class) val pinned: Boolean? = null,
) {
    val isTelegram: Boolean get() = source.equals("telegram", ignoreCase = true)

    private val sourceKey: String? get() = source?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Started by a schedule, a webhook, a worker or another agent rather than by someone chatting. */
    val isAutomation: Boolean get() = sourceKey in AUTOMATION_SOURCES

    /** A chat on a messaging platform (Telegram, Discord, Slack, Signal, WhatsApp, email, SMS…). */
    val isMessaging: Boolean get() = sourceKey.let { it != null && it !in LOCAL_SOURCES && it !in AUTOMATION_SOURCES }

    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: preview?.takeIf { it.isNotBlank() }?.lineSequence()?.first()?.take(40)
            ?: "Untitled"

    /** Most recent activity, for unread tracking. */
    val activity: Double get() = last_active ?: ended_at ?: started_at ?: 0.0

    companion object {
        /** Hermes session sources that are not someone chatting, as `exclude_sources` takes them. */
        val AUTOMATION_SOURCES = listOf("cron", "kanban", "webhook", "msgraph_webhook", "oneshot", "tool", "acp", "bot_room", "subagent")
        /** Chats on this machine: the desktop, terminal, web UI and API. Any other source is a messaging platform. */
        val LOCAL_SOURCES = setOf("desktop", "tui", "cli", "webui", "api_server", "local")
    }
}

/**
 * A session flag. Hermes stores these in SQLite and returns them as `0`/`1` on some routes and
 * as JSON booleans on others, so both are accepted. Anything else is a protocol error rather
 * than a silent `false`.
 */
object SessionFlag : KSerializer<Boolean?> {
    override val descriptor = PrimitiveSerialDescriptor("SessionFlag", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw SerializationException("Session flag is not a value: $element")
        return when (primitive.content.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw SerializationException("Unsupported session flag: ${primitive.content}")
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
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
    /** How Hermes shows a row; set on rows Hermes wrote itself, such as a finished background task's notice. */
    val display_kind: String? = null,
    /** Hermes' display data for that row; a delegation's completion names its `delegation_id` here. */
    val display_metadata: JsonElement? = null,
    val audio_attachments: List<AudioAttachment> = emptyList(),
) {
    /**
     * A user row Hermes wrote to start a turn of its own (a background process finished, a
     * delegated task reported back), rather than something a person sent.
     */
    val opensBackgroundTurn: Boolean
        get() = role == "user" && display_kind in BACKGROUND_TURN_KINDS

    /**
     * For a row that [opensBackgroundTurn], the ids of the work it reports finished: the
     * delegation (`deleg_…`, from `display_metadata` or the notice's header) or background process
     * (`proc_…`). The tool result that started that work names the same id, which is how
     * `SessionMapper` traces the completion back to the turn that asked for it. Empty for any
     * other row, and for notices that report no work (a heartbeat tick, a gateway notice).
     */
    val completedWork: Set<String>
        get() {
            if (!opensBackgroundTurn) return emptySet()
            val fromMetadata = ((display_metadata as? JsonObject)?.get("delegation_id") as? JsonPrimitive)?.contentOrNull
            return (listOfNotNull(fromMetadata?.takeIf { it.isNotBlank() }) +
                WORK_NOTICES.flatMap { re -> re.findAll(text).map { it.groupValues[1] } }).toSet()
        }

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
     * Automatic voice uses typed attachments on the exact assistant row, separately from tags.
     */
    val audioRefs: List<String>
        get() = AUDIO_TAG.findAll(text.replace("\\/", "/"))
            .map { it.groupValues[1] }.distinct().toList()
}

@Serializable
data class AudioAttachment(
    val id: String,
    val mime_type: String,
    val created_at: Double,
    val expires_at: Double? = null,
    val part: Int = 0,
)

private val AUDIO_TAG = Regex(
    """MEDIA:([^\s"'<>\\]+?\.(?:mp3|ogg|opus|wav|m4a|aac|flac))(?![\w.])""",
    RegexOption.IGNORE_CASE,
)

@Serializable
data class MessageList(
    val session_id: String? = null,
    val messages: List<HermesMessage> = emptyList(),
    val audio_messages: List<HermesMessage> = emptyList(),
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

/**
 * Headers Hermes writes into a completion notice's content (`tools/process_registry_notifications.py`
 * `format_process_notification`): a delegation finished or one of its tasks failed, a subagent's
 * process names its delegation, and a background process exited or matched its watch pattern.
 * A heartbeat (`[Background process … heartbeat`) is progress, not a result, and is left out.
 */
private val WORK_NOTICES = listOf(
    Regex("""\[ASYNC DELEGATION [A-Z ]+ — ([\w-]+)"""),
    Regex("""Started by subagent \S+ of delegation ([\w-]+)"""),
    Regex("""\[IMPORTANT: Background process (proc_[\w-]+) """),
)

/** `display_kind`s of the user rows Hermes writes to start a turn of its own. */
private val BACKGROUND_TURN_KINDS = setOf("internal_notification", "process_complete", "async_delegation_complete")
