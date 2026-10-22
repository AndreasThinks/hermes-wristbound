package dev.wristbound.companion.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Session origin as recorded by Hermes in `sessions.source` (never overwritten once set). */
const val SOURCE_TELEGRAM = "telegram"

fun SessionDto.isTelegram(): Boolean = source.equals(SOURCE_TELEGRAM, ignoreCase = true)

fun SessionDto.displayTitle(): String = when {
    !title.isNullOrBlank() -> title
    !preview.isNullOrBlank() -> preview.lineSequence().first().take(60)
    else -> id
}

/**
 * Session visibility. The Telegram filter controls *visibility only*; whether hidden sessions
 * may still alert is a separate, explicit preference ([AlertPrefs.alertFilteredSessions]).
 */
object SessionFilter {
    fun isEligible(s: SessionDto): Boolean = !s.hidden && !s.archived && !s.isInternalChild

    fun isVisible(s: SessionDto, includeTelegram: Boolean): Boolean =
        isEligible(s) && (includeTelegram || !s.isTelegram())

    fun visible(sessions: List<SessionDto>, includeTelegram: Boolean): List<SessionDto> =
        sessions.filter { isVisible(it, includeTelegram) }
}

enum class MessageKind { USER, ASSISTANT_FINAL, ASSISTANT_TOOL_CALL, TOOL_RESULT, OTHER }

object MessageClassifier {
    fun kind(m: MessageDto): MessageKind = when (m.role) {
        "user" -> MessageKind.USER
        "assistant" -> if (m.hasToolCalls) {
            MessageKind.ASSISTANT_TOOL_CALL
        } else if (m.text.isNotBlank()) {
            MessageKind.ASSISTANT_FINAL
        } else {
            MessageKind.OTHER
        }
        "tool" -> MessageKind.TOOL_RESULT
        else -> MessageKind.OTHER
    }

    private val mediaLine = Regex("""^\s*MEDIA:\S.*$""")

    /** Text suitable for display and speech: strips `MEDIA:` delivery tags and voice markers. */
    fun displayText(m: MessageDto): String = cleanText(m.text)

    fun cleanText(raw: String): String = raw.lineSequence()
        .filterNot { mediaLine.matches(it) || it.trim() == "[[audio_as_voice]]" }
        .joinToString("\n")
        .trim()

    /** Short tool label for activity display, e.g. "terminal" or "web_search". */
    fun toolLabel(m: MessageDto): String? = when (kind(m)) {
        MessageKind.TOOL_RESULT -> m.toolName ?: "tool"
        MessageKind.ASSISTANT_TOOL_CALL -> runCatching {
            val arr = m.toolCalls as? kotlinx.serialization.json.JsonArray
            arr?.mapNotNull { call ->
                val fn = call.jsonObject["function"] as? JsonObject
                (fn?.get("name") as? JsonPrimitive)?.contentOrNull
            }?.joinToString(", ")
        }.getOrNull()?.ifBlank { null } ?: "tool"
        else -> null
    }
}

/** Audio Hermes generated during a turn (TTS tool output), referenced by path on the Hermes host. */
@Serializable
data class MediaRef(val toolMessageId: Long, val path: String, val mimeType: String)

/**
 * Finds Hermes-generated audio belonging to one assistant reply.
 *
 * A *turn* is the run of rows after the previous `user` row up to and including the reply.
 * Only audio produced inside that turn is attributed to the reply; audio is never paired by
 * timestamp proximity or "latest file". Multiple media items are preserved in order.
 */
object TurnMedia {
    private val audioExt = mapOf(
        "ogg" to "audio/ogg", "opus" to "audio/ogg", "mp3" to "audio/mpeg",
        "wav" to "audio/wav", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
    )

    fun mimeFor(path: String): String? = audioExt[path.substringAfterLast('.', "").lowercase()]

    /** Rows of the turn that ends at [replyId], in chronological order; empty if unknown. */
    fun turnOf(messages: List<MessageDto>, replyId: Long): List<MessageDto> {
        val sorted = messages.sortedBy { it.id }
        val idx = sorted.indexOfFirst { it.id == replyId }
        if (idx < 0) return emptyList()
        var start = idx
        // Stop at the user row that opened the turn. If the fetched window starts mid-turn the
        // result is a suffix of the turn: audio may be missed, but never misattributed.
        while (start > 0 && sorted[start - 1].role != "user") start--
        return sorted.subList(start, idx + 1)
    }

    fun audioFor(messages: List<MessageDto>, replyId: Long): List<MediaRef> =
        turnOf(messages, replyId).flatMap { extract(it) }

    fun extract(m: MessageDto): List<MediaRef> {
        if (m.role == "tool" && m.toolName == "text_to_speech") {
            val obj = runCatching { HermesJson.parseToJsonElement(m.text).jsonObject }.getOrNull() ?: return emptyList()
            val ok = (obj["success"] as? JsonPrimitive)?.booleanOrNull ?: false
            val path = (obj["file_path"] as? JsonPrimitive)?.contentOrNull
            val mime = path?.let { mimeFor(it) }
            return if (ok && path != null && mime != null) listOf(MediaRef(m.id, path, mime)) else emptyList()
        }
        if (m.role == "assistant") {
            return m.text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("MEDIA:") }
                .mapNotNull { line ->
                    val path = line.removePrefix("MEDIA:").trim()
                    mimeFor(path)?.let { MediaRef(m.id, path, it) }
                }.toList()
        }
        return emptyList()
    }
}

// ---------------------------------------------------------------------------------------------
// Preferences: visibility vs interruption are independent.

enum class ToolActivityMode { HIDDEN, SHOW_SILENTLY, NOTIFY }
enum class ReplyAlertMode { NOTIFY, SHOW_SILENTLY }
enum class AlertScope { ACTIVE_SESSION, VISIBLE_SESSIONS, ALL_ELIGIBLE }

@Serializable
data class AlertPrefs(
    val includeTelegram: Boolean = true,
    val replies: ReplyAlertMode = ReplyAlertMode.NOTIFY,
    val toolActivity: ToolActivityMode = ToolActivityMode.HIDDEN,
    val scope: AlertScope = AlertScope.VISIBLE_SESSIONS,
    /** Explicit opt-in: sessions hidden by the Telegram filter may still alert (title only). */
    val alertFilteredSessions: Boolean = false,
    /** Show reply text in notifications; when false only "New Hermes reply" is shown. */
    val notificationPreviews: Boolean = true,
)

data class Decision(val display: Boolean, val alert: Boolean) {
    companion object {
        val NONE = Decision(display = false, alert = false)
    }
}

object AlertPolicy {
    fun decide(
        kind: MessageKind,
        session: SessionDto,
        prefs: AlertPrefs,
        activeSessionId: String?,
    ): Decision {
        if (!SessionFilter.isEligible(session)) return Decision.NONE
        val visible = SessionFilter.isVisible(session, prefs.includeTelegram)
        val inScope = when (prefs.scope) {
            AlertScope.ACTIVE_SESSION -> session.id == activeSessionId
            AlertScope.VISIBLE_SESSIONS -> visible || prefs.alertFilteredSessions
            AlertScope.ALL_ELIGIBLE -> true
        } && (visible || prefs.alertFilteredSessions)
        val base = when (kind) {
            MessageKind.ASSISTANT_FINAL -> Decision(display = true, alert = prefs.replies == ReplyAlertMode.NOTIFY)
            MessageKind.TOOL_RESULT, MessageKind.ASSISTANT_TOOL_CALL -> when (prefs.toolActivity) {
                ToolActivityMode.HIDDEN -> Decision.NONE
                ToolActivityMode.SHOW_SILENTLY -> Decision(display = true, alert = false)
                ToolActivityMode.NOTIFY -> Decision(display = true, alert = true)
            }
            MessageKind.USER, MessageKind.OTHER -> Decision.NONE
        }
        return Decision(display = base.display && visible, alert = base.alert && inScope)
    }
}
