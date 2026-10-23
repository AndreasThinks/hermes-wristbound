package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.pebble.Protocol
import dev.wristbound.companion.pebble.Text

/** Pure mapping from Hermes data to what the watch shows. */
object SessionMapper {

    /**
     * Sessions visible on the watch: pinned first, then most recent. Messaging chats show with
     * [includeMessaging], and scheduled and automation sessions only with [includeAutomation].
     * These control visibility only; alerts are decided separately by [AlertPolicy]. With
     * [telegramReplies] off, Telegram sessions are marked read-only: still readable and
     * playable, but not sendable.
     */
    fun visibleSessions(
        sessions: List<HermesSession>,
        includeMessaging: Boolean,
        lastSeen: Map<String, Double>,
        readOnly: Set<String>,
        active: Set<String>,
        telegramReplies: Boolean = true,
        includeAutomation: Boolean = false,
    ): List<WatchSession> = sessions
        .asSequence()
        .filter { it.archived != true && it.hidden != true && it.is_internal_child != true }
        .filter { includeMessaging || !it.isMessaging }
        .filter { includeAutomation || !it.isAutomation }
        .sortedWith(compareByDescending<HermesSession> { it.pinned == true }.thenByDescending { it.activity })
        .take(Protocol.MAX_SESSIONS)
        .map { s ->
            var flags = 0
            val seen = lastSeen[s.id]
            if (seen != null && s.activity > seen + 0.001) flags = flags or Protocol.SessionFlag.UNREAD
            if (s.isTelegram) flags = flags or Protocol.SessionFlag.TELEGRAM
            if (s.id in readOnly || (s.isTelegram && !telegramReplies)) flags = flags or Protocol.SessionFlag.READ_ONLY
            if (s.id in active) flags = flags or Protocol.SessionFlag.ACTIVE
            WatchSession(s.id, Text.forWatch(s.displayTitle), flags)
        }
        .toList()

    /**
     * Why a reply into [session] is refused on the phone, or null if it may be sent. With
     * [telegramReplies] off an unknown session is refused too: it might be a Telegram one.
     */
    fun replyRefusal(session: HermesSession?, telegramReplies: Boolean): String? = when {
        telegramReplies -> null
        session == null -> "Refresh sessions first"
        session.isTelegram -> "Reply in Telegram"
        else -> null
    }

    /**
     * Messages for the watch: user turns and final assistant answers. Tool steps appear only when
     * the user asked to see tool activity, and then as a one-line summary.
     */
    fun watchMessages(messages: List<HermesMessage>, toolActivity: ToolActivity): List<WatchMessage> {
        val replies = replies(messages).associateBy { it.index }
        return messages
            .mapIndexedNotNull { i, m ->
                val reply = replies[i]
                when {
                    // Scaffolding Hermes never shows.
                    m.display_kind == "hidden" -> null
                    // Hermes' own notice that starts a background turn: shown small, not as something you sent.
                    m.opensBackgroundTurn && m.text.isNotBlank() ->
                        WatchMessage(m.stableId, Protocol.Role.SYSTEM, 0, Text.forWatch(m.text))
                    m.role == "user" && m.text.isNotBlank() ->
                        WatchMessage(m.stableId, Protocol.Role.USER, 0, Text.forWatch(m.text))
                    reply != null -> WatchMessage(
                        m.stableId, Protocol.Role.ASSISTANT,
                        if (reply.hasAudio) Protocol.MessageFlag.HAS_AUDIO else 0,
                        Text.forWatch(m.text),
                    )
                    // A media-only follow-up is shown as part of the answer it belongs to.
                    m.isFinalAssistantText -> null
                    toolActivity != ToolActivity.HIDDEN && (m.role == "tool" || m.hasToolCalls) ->
                        WatchMessage(m.stableId, Protocol.Role.TOOL, 0, "Used ${m.tool_name ?: "a tool"}")
                    else -> null
                }
            }
            .takeLast(Protocol.MAX_MESSAGES)
    }

    /**
     * One final answer at [index] in a message list, with the audio Hermes made for it.
     * [background] marks an answer to a turn Hermes started itself (see [HermesMessage.opensBackgroundTurn]).
     */
    data class Reply(val index: Int, val message: HermesMessage, val audio: List<String>, val background: Boolean = false) {
        val id: String get() = message.stableId
        val hasAudio: Boolean get() = audio.isNotEmpty()
    }

    /**
     * Final answers in [messages], each with the audio Hermes generated for it, classified
     * explicitly rather than by position or timestamp:
     *
     * - A turn runs from a user row to the next one. Audio is taken only from assistant rows and
     *   `text_to_speech` tool results of that turn, never from user rows or other tools' output.
     * - The user's own recordings (a Telegram voice note, a Ringbearer capture) sit in the same
     *   `cache/audio` folder as generated speech, so any file a user row names is excluded,
     *   however the agent later refers to it. So is any file named the way Hermes names inbound
     *   media (`audio_<12 hex>`), for when that user row is outside the fetched window.
     * - A media-only assistant row is not a separate answer: its audio joins the answer before
     *   it in the same turn, which is how audio that arrives after the text is matched.
     */
    fun replies(messages: List<HermesMessage>): List<Reply> {
        val userFiles = messages.filter { it.role == "user" }
            .flatMap { AUDIO_PATH.findAll(it.text.replace("\\/", "/")).map { m -> m.value.substringAfterLast('/') } }
            .toSet()
        val out = mutableListOf<Reply>()
        var start = 0
        while (start < messages.size) {
            var end = start
            while (end < messages.size && messages[end].role != "user") end++
            if (end > start) out += turnReplies(messages, start, end, userFiles, messages.getOrNull(start - 1)?.opensBackgroundTurn == true)
            start = end + 1
        }
        return out
    }

    /** [replies] that belong to the latest turn: after the last user row. */
    fun latestTurnReplies(messages: List<HermesMessage>): List<Reply> {
        val lastUser = messages.indexOfLast { it.role == "user" }
        return replies(messages).filter { it.index > lastUser }
    }

    private fun turnReplies(messages: List<HermesMessage>, start: Int, end: Int, userFiles: Set<String>, background: Boolean): List<Reply> {
        val finals = (start until end).filter { messages[it].isFinalAssistantText }
        val answers = finals.filter { Text.forSpeech(Text.forWatch(messages[it].text)).isNotBlank() }
            .ifEmpty { listOfNotNull(finals.lastOrNull()) }
        return answers.mapIndexed { k, index ->
            val from = if (k == 0) start else answers[k - 1] + 1
            val to = if (k == answers.lastIndex) end else index + 1
            val audio = (from until to)
                .map { messages[it] }
                .filter { it.role == "assistant" || it.isSpeechResult }
                .flatMap { it.audioRefs }
                .filter { it.substringAfterLast('/').let { name -> name !in userFiles && !INBOUND_AUDIO.matches(name) } }
                .distinct()
            Reply(index, messages[index], audio, background)
        }
    }

    private val HermesMessage.isSpeechResult: Boolean
        get() = role == "tool" && (tool_name == "text_to_speech" || (tool_name == null && text.contains("\"media_tag\"")))

    /** Hermes' name for cached inbound media (`gateway/platforms/base.py` `_write_cache_file`). */
    private val INBOUND_AUDIO = Regex("""audio_[0-9a-f]{12}\.\w+""", RegexOption.IGNORE_CASE)

    /** Any audio file path, tagged or not: how user rows name a voice note. */
    private val AUDIO_PATH = Regex("""[^\s"'<>\[\](){}:]+\.(?:mp3|ogg|opus|wav|m4a|aac|flac|oga)(?![\w.])""", RegexOption.IGNORE_CASE)
}
