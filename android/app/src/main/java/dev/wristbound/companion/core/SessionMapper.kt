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
     * [messagingReplies] off, messaging chats are marked read-only: still readable and
     * playable, but not sendable.
     */
    fun visibleSessions(
        sessions: List<HermesSession>,
        includeMessaging: Boolean,
        lastSeen: Map<String, Double>,
        readOnly: Set<String>,
        active: Set<String>,
        messagingReplies: Boolean = true,
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
            if (s.id in readOnly || (s.isMessaging && !messagingReplies)) flags = flags or Protocol.SessionFlag.READ_ONLY
            if (s.id in active) flags = flags or Protocol.SessionFlag.ACTIVE
            WatchSession(s.id, Text.forWatch(s.displayTitle), flags)
        }
        .toList()

    /**
     * Why a reply into [session] is refused on the phone, or null if it may be sent. With
     * [messagingReplies] off an unknown session is refused too: it might be a messaging chat.
     */
    fun replyRefusal(session: HermesSession?, messagingReplies: Boolean): String? = when {
        messagingReplies -> null
        session == null -> "Refresh sessions first"
        session.isTelegram -> "Reply in Telegram"
        session.isMessaging -> "Reply in the chat app"
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
     *
     * [background] marks an answer to a turn Hermes opened with a notice that reports no finished
     * work (a heartbeat tick, a gateway check-in). An answer about finished work (a delegation or
     * background process reporting back) is part of the conversation, like the desktop app's
     * "Response ready". [originRow] is the stable id of the person's message the answer responds
     * to: the user row of its own turn or, for finished work, of the turn that started that work
     * when it is in view.
     */
    data class Reply(
        val index: Int,
        val message: HermesMessage,
        val audio: List<String>,
        val background: Boolean = false,
        val originRow: String? = null,
    ) {
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
     * - A turn Hermes opened to report finished work is a reply in the conversation; only a turn
     *   opened by a notice that reports no work is [Reply.background]. Finished work is traced
     *   back to the person who asked for it where possible ([Origins]), for [Reply.originRow].
     */
    fun replies(messages: List<HermesMessage>): List<Reply> {
        val userFiles = messages.filter { it.role == "user" }
            .flatMap { AUDIO_PATH.findAll(it.text.replace("\\/", "/")).map { m -> m.value.substringAfterLast('/') } }
            .toSet()
        val out = mutableListOf<Reply>()
        val origins = Origins(messages)
        var start = 0
        while (start < messages.size) {
            var end = start
            while (end < messages.size && messages[end].role != "user") end++
            if (end > start) {
                // A window that starts mid-turn has no opener in view: as before, not background.
                val origin = if (start == 0) Origin.Unseen else origins.of(start - 1)
                out += turnReplies(messages, start, end, userFiles, origin)
            }
            start = end + 1
        }
        return out
    }

    /** Who a turn answers, found from its opening user row. */
    private sealed interface Origin {
        /** A person's message, by stable id. */
        data class Person(val row: String) : Origin
        /** Hermes reporting finished work, started by [asker]'s message when that is in view. */
        data class Work(val asker: String?) : Origin
        /** A notice from Hermes that reports no finished work. */
        data object Hermes : Origin
        /** The fetched rows start inside the turn. */
        data object Unseen : Origin
    }

    /**
     * Classifies turn openers and traces finished work back to the person who asked for it,
     * memoised per row.
     *
     * A completion notice names its delegation or process id ([HermesMessage.completedWork]);
     * the earliest tool result in view that names the same id is where that work was started
     * (`delegate_task` returns `delegation_id`, a background `terminal` call returns
     * `session_id`). The work belongs to whoever opened that turn, which may itself be another
     * completion, so the chain is followed back. Each step moves to an earlier row, so it always
     * ends. Tracing only names the asker: a completion it cannot trace is still finished work.
     */
    private class Origins(private val messages: List<HermesMessage>) {
        private val memo = HashMap<Int, Origin>()

        fun of(opener: Int): Origin = memo.getOrPut(opener) {
            val row = messages[opener]
            if (!row.opensBackgroundTurn) return@getOrPut Origin.Person(row.stableId)
            val ids = row.completedWork.map { Regex("""(?<![\w-])${Regex.escape(it)}(?![\w-])""") }
            if (ids.isEmpty()) return@getOrPut Origin.Hermes
            val dispatch = (0 until opener).firstOrNull { i ->
                messages[i].role == "tool" && messages[i].text.let { text -> ids.any { it.containsMatchIn(text) } }
            }
            val asker = dispatch?.let { d -> (d - 1 downTo 0).firstOrNull { messages[it].role == "user" } }
            Origin.Work(asker?.let { askerOf(of(it)) })
        }

        private fun askerOf(origin: Origin): String? = when (origin) {
            is Origin.Person -> origin.row
            is Origin.Work -> origin.asker
            else -> null
        }
    }

    /** [replies] that belong to the latest turn: after the last user row. */
    fun latestTurnReplies(messages: List<HermesMessage>): List<Reply> {
        val lastUser = messages.indexOfLast { it.role == "user" }
        return replies(messages).filter { it.index > lastUser }
    }

    private fun turnReplies(messages: List<HermesMessage>, start: Int, end: Int, userFiles: Set<String>, origin: Origin): List<Reply> {
        val background = origin == Origin.Hermes
        val originRow = when (origin) {
            is Origin.Person -> origin.row
            is Origin.Work -> origin.asker
            else -> null
        }
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
            // Typed attachments are already associated by Hermes; never borrow a sibling's.
            val attached = messages[index].audio_attachments.sortedBy { it.part }.map { "attachment:${it.id}" }
            Reply(index, messages[index], (audio + attached).distinct(), background, originRow)
        }
    }

    private val HermesMessage.isSpeechResult: Boolean
        get() = role == "tool" && (tool_name == "text_to_speech" || (tool_name == null && text.contains("\"media_tag\"")))

    /** Hermes' name for cached inbound media (`gateway/platforms/base.py` `_write_cache_file`). */
    private val INBOUND_AUDIO = Regex("""audio_[0-9a-f]{12}\.\w+""", RegexOption.IGNORE_CASE)

    /** Any audio file path, tagged or not: how user rows name a voice note. */
    private val AUDIO_PATH = Regex("""[^\s"'<>\[\](){}:]+\.(?:mp3|ogg|opus|wav|m4a|aac|flac|oga)(?![\w.])""", RegexOption.IGNORE_CASE)
}
