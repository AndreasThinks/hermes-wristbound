package dev.wristbound.companion.core

/**
 * Wire contract shared with pebble/src/c/protocol.h (checked by WatchProtocolHeaderTest).
 * Values are plain Ints so the header test can compare them by name.
 */
@Suppress("unused")
object WP {
    const val WB_PROTO_VERSION = 1

    const val KEY_TYPE = 1
    const val KEY_SESSION_ID = 2
    const val KEY_MSG_ID = 3
    const val KEY_TEXT = 4
    const val KEY_TITLE = 5
    const val KEY_INDEX = 6
    const val KEY_COUNT = 7
    const val KEY_FLAGS = 8
    const val KEY_ROLE = 9
    const val KEY_OFFSET = 10
    const val KEY_STATE = 11
    const val KEY_SUBMIT_ID = 12
    const val KEY_ROUTE = 13
    const val KEY_ACTION = 14
    const val KEY_PREF_ID = 15
    const val KEY_PREF_VALUE = 16
    const val KEY_GEN = 17
    const val KEY_PROTO = 18
    const val KEY_SOURCE = 19

    const val T_HELLO = 1
    const val T_LIST_SESSIONS = 2
    const val T_OPEN_SESSION = 3
    const val T_GET_BODY = 4
    const val T_SUBMIT = 5
    const val T_PLAY = 6
    const val T_PLAYBACK = 7
    const val T_SET_PREF = 8
    const val T_MARK_READ = 9

    const val T_STATUS = 101
    const val T_SESSION_ITEM = 102
    const val T_MESSAGE_ITEM = 103
    const val T_BODY_CHUNK = 104
    const val T_SUBMIT_STATE = 105
    const val T_PLAYBACK_STATE = 106
    const val T_PREFS = 107
    const val T_ALERT = 108
    const val T_TOOL_STATUS = 109

    const val ST_OK = 0
    const val ST_OFFLINE = 1
    const val ST_AUTH = 2
    const val ST_UNCONFIGURED = 3
    const val ST_SYNCING = 4

    const val SF_UNREAD = 0x01
    const val SF_TELEGRAM = 0x02
    const val SF_ACTIVE = 0x04
    const val SF_SELECTED = 0x08

    const val ROLE_USER = 1
    const val ROLE_ASSISTANT = 2
    const val ROLE_TOOL = 3

    const val MF_HERMES_AUDIO = 0x01
    const val MF_PLAYABLE = 0x02
    const val MF_FINAL_CHUNK = 0x04
    const val MF_TRUNCATED = 0x08

    const val SS_PENDING = 1
    const val SS_ACCEPTED = 2
    const val SS_RUNNING = 3
    const val SS_COMPLETED = 4
    const val SS_FAILED = 5
    const val SS_UNCERTAIN = 6

    const val ROUTE_HEADPHONES = 0
    const val ROUTE_SPEAKER = 1

    const val PA_PAUSE = 1
    const val PA_RESUME = 2
    const val PA_REPLAY = 3
    const val PA_STOP = 4

    const val PS_IDLE = 0
    const val PS_PREPARING = 1
    const val PS_PLAYING = 2
    const val PS_PAUSED = 3
    const val PS_ENDED = 4
    const val PS_ERROR = 5
    const val PS_NEED_HEADPHONES = 6

    const val SRC_HERMES_AUDIO = 1
    const val SRC_PHONE_TTS = 2

    const val PREF_INCLUDE_TELEGRAM = 1
    const val PREF_TOOL_MODE = 2
    const val PREF_REPLY_MODE = 3

    // Size limits (bytes, UTF-8) chosen to fit the watch inbox (1024 bytes) with headroom.
    const val MAX_TITLE = 32
    const val MAX_PREVIEW = 80
    const val MAX_STATUS = 64
    const val BODY_CHUNK = 360
    const val MAX_BODY = 3000
    const val MAX_LIST = 15
}

/** Platform-neutral AppMessage value; mapped to PebbleKit dictionary items on Android. */
sealed class WValue {
    data class Text(val value: String) : WValue()
    data class U32(val value: Long) : WValue()
}

typealias WatchDict = Map<Int, WValue>

/** Builds phone->watch messages. Pure, so it is unit-tested on the JVM. */
object WatchEncoder {
    fun status(code: Int, text: String): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_STATUS), WP.KEY_STATE to u(code), WP.KEY_TEXT to t(text, WP.MAX_STATUS),
    )

    fun sessionItem(gen: Int, index: Int, count: Int, id: String, title: String, flags: Int): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_SESSION_ITEM), WP.KEY_GEN to u(gen), WP.KEY_INDEX to u(index), WP.KEY_COUNT to u(count),
        WP.KEY_SESSION_ID to WValue.Text(id), WP.KEY_TITLE to t(title, WP.MAX_TITLE), WP.KEY_FLAGS to u(flags),
    )

    fun messageItem(
        gen: Int, index: Int, count: Int, sessionId: String, msgId: Long, role: Int, flags: Int, preview: String,
    ): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_MESSAGE_ITEM), WP.KEY_GEN to u(gen), WP.KEY_INDEX to u(index), WP.KEY_COUNT to u(count),
        WP.KEY_SESSION_ID to WValue.Text(sessionId), WP.KEY_MSG_ID to WValue.U32(msgId), WP.KEY_ROLE to u(role),
        WP.KEY_FLAGS to u(flags), WP.KEY_TEXT to t(preview, WP.MAX_PREVIEW),
    )

    /** Splits [body] into UTF-8-safe chunks; the last carries MF_FINAL_CHUNK. */
    fun bodyChunks(msgId: Long, body: String): List<WatchDict> {
        val cleaned = WatchText.sanitize(body)
        val truncated = utf8Length(cleaned) > WP.MAX_BODY
        val text = if (truncated) WatchText.truncateUtf8(cleaned, WP.MAX_BODY - 24) + "\n…(more on phone)" else cleaned
        val parts = WatchText.chunkUtf8(text, WP.BODY_CHUNK).ifEmpty { listOf("") }
        var offset = 0
        return parts.mapIndexed { i, part ->
            val last = i == parts.lastIndex
            val flags = (if (last) WP.MF_FINAL_CHUNK else 0) or (if (last && truncated) WP.MF_TRUNCATED else 0)
            val dict = mapOf(
                WP.KEY_TYPE to u(WP.T_BODY_CHUNK), WP.KEY_MSG_ID to WValue.U32(msgId), WP.KEY_OFFSET to u(offset),
                WP.KEY_TEXT to WValue.Text(part), WP.KEY_FLAGS to u(flags),
            )
            offset += utf8Length(part)
            dict
        }
    }

    fun submitState(submitId: Long, state: Int, text: String, sessionId: String): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_SUBMIT_STATE), WP.KEY_SUBMIT_ID to WValue.U32(submitId), WP.KEY_STATE to u(state),
        WP.KEY_TEXT to t(text, WP.MAX_STATUS), WP.KEY_SESSION_ID to WValue.Text(sessionId),
    )

    fun playbackState(msgId: Long, state: Int, source: Int, text: String): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_PLAYBACK_STATE), WP.KEY_MSG_ID to WValue.U32(msgId), WP.KEY_STATE to u(state),
        WP.KEY_SOURCE to u(source), WP.KEY_TEXT to t(text, WP.MAX_STATUS),
    )

    fun pref(id: Int, value: Int): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_PREFS), WP.KEY_PREF_ID to u(id), WP.KEY_PREF_VALUE to u(value),
    )

    fun alert(sessionId: String, msgId: Long, title: String, text: String, flags: Int): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_ALERT), WP.KEY_SESSION_ID to WValue.Text(sessionId), WP.KEY_MSG_ID to WValue.U32(msgId),
        WP.KEY_TITLE to t(title, WP.MAX_TITLE), WP.KEY_TEXT to t(text, WP.MAX_PREVIEW), WP.KEY_FLAGS to u(flags),
    )

    fun toolStatus(sessionId: String, text: String): WatchDict = mapOf(
        WP.KEY_TYPE to u(WP.T_TOOL_STATUS), WP.KEY_SESSION_ID to WValue.Text(sessionId), WP.KEY_TEXT to t(text, WP.MAX_STATUS),
    )

    private fun u(v: Int) = WValue.U32(v.toLong())
    private fun t(v: String, maxBytes: Int) = WValue.Text(WatchText.truncateUtf8(WatchText.sanitize(v), maxBytes))
    internal fun utf8Length(s: String) = s.toByteArray(Charsets.UTF_8).size
}

/** Decoded watch->phone command. */
sealed class WatchCommand {
    data class Hello(val proto: Int) : WatchCommand()
    data object ListSessions : WatchCommand()
    data class OpenSession(val sessionId: String) : WatchCommand()
    data class GetBody(val sessionId: String, val msgId: Long) : WatchCommand()
    data class Submit(val sessionId: String?, val text: String, val submitId: Long) : WatchCommand()
    data class Play(val sessionId: String, val msgId: Long, val speaker: Boolean) : WatchCommand()
    data class Playback(val action: Int) : WatchCommand()
    data class SetPref(val id: Int, val value: Int) : WatchCommand()
    data class MarkRead(val sessionId: String, val msgId: Long) : WatchCommand()
    data class Unknown(val type: Int?) : WatchCommand()

    companion object {
        fun decode(d: WatchDict): WatchCommand {
            fun num(k: Int): Long? = (d[k] as? WValue.U32)?.value
            fun str(k: Int): String? = (d[k] as? WValue.Text)?.value
            val type = num(WP.KEY_TYPE)?.toInt()
            return when (type) {
                WP.T_HELLO -> Hello(num(WP.KEY_PROTO)?.toInt() ?: 0)
                WP.T_LIST_SESSIONS -> ListSessions
                WP.T_OPEN_SESSION -> str(WP.KEY_SESSION_ID)?.let { OpenSession(it) } ?: Unknown(type)
                WP.T_GET_BODY -> {
                    val s = str(WP.KEY_SESSION_ID); val m = num(WP.KEY_MSG_ID)
                    if (s != null && m != null) GetBody(s, m) else Unknown(type)
                }
                WP.T_SUBMIT -> {
                    val text = str(WP.KEY_TEXT)?.trim(); val id = num(WP.KEY_SUBMIT_ID)
                    if (!text.isNullOrEmpty() && id != null) Submit(str(WP.KEY_SESSION_ID)?.ifBlank { null }, text, id) else Unknown(type)
                }
                WP.T_PLAY -> {
                    val s = str(WP.KEY_SESSION_ID); val m = num(WP.KEY_MSG_ID)
                    if (s != null && m != null) Play(s, m, num(WP.KEY_ROUTE)?.toInt() == WP.ROUTE_SPEAKER) else Unknown(type)
                }
                WP.T_PLAYBACK -> num(WP.KEY_ACTION)?.let { Playback(it.toInt()) } ?: Unknown(type)
                WP.T_SET_PREF -> {
                    val id = num(WP.KEY_PREF_ID); val v = num(WP.KEY_PREF_VALUE)
                    if (id != null && v != null) SetPref(id.toInt(), v.toInt()) else Unknown(type)
                }
                WP.T_MARK_READ -> {
                    val s = str(WP.KEY_SESSION_ID); val m = num(WP.KEY_MSG_ID)
                    if (s != null && m != null) MarkRead(s, m) else Unknown(type)
                }
                else -> Unknown(type)
            }
        }
    }
}

object WatchText {
    private val supplementary = Regex("[\\x{10000}-\\x{10FFFF}]")
    private val dingbats = Regex("[\\u2600-\\u27BF\\uFE0F\\u200D]")
    private val mdEmphasis = Regex("""(\*\*|__|`)""")

    /** Removes glyphs Pebble system fonts cannot render and markdown emphasis noise. */
    fun sanitize(s: String): String = s
        .replace(supplementary, "")
        .replace(dingbats, "")
        .replace(mdEmphasis, "")
        .replace("\r\n", "\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val ellipsis = "…"
        val budget = maxBytes - ellipsis.toByteArray(Charsets.UTF_8).size
        return takeUtf8(s, budget) + ellipsis
    }

    fun chunkUtf8(s: String, maxBytes: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.isNotEmpty()) {
            val part = takeUtf8(rest, maxBytes)
            if (part.isEmpty()) break
            out += part
            rest = rest.substring(part.length)
        }
        return out
    }

    /** Longest prefix of [s] whose UTF-8 encoding fits [maxBytes], never splitting a code point. */
    private fun takeUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + len > maxBytes) break
            bytes += len
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }
}
