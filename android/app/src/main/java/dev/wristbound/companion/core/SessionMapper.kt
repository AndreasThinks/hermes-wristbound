package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.pebble.Protocol
import dev.wristbound.companion.pebble.Text

/** Pure mapping from Hermes data to what the watch shows. */
object SessionMapper {

    /**
     * Sessions visible on the watch. The Telegram filter controls visibility only; notification
     * eligibility is decided separately by [shouldNotify].
     */
    fun visibleSessions(
        sessions: List<HermesSession>,
        includeTelegram: Boolean,
        lastSeen: Map<String, Double>,
        readOnly: Set<String>,
        active: Set<String>,
    ): List<WatchSession> = sessions
        .asSequence()
        .filter { it.archived != true && it.hidden != true && it.is_internal_child != true }
        .filter { includeTelegram || !it.isTelegram }
        .sortedByDescending { it.activity }
        .take(Protocol.MAX_SESSIONS)
        .map { s ->
            var flags = 0
            val seen = lastSeen[s.id]
            if (seen != null && s.activity > seen + 0.001) flags = flags or Protocol.SessionFlag.UNREAD
            if (s.isTelegram) flags = flags or Protocol.SessionFlag.TELEGRAM
            if (s.id in readOnly) flags = flags or Protocol.SessionFlag.READ_ONLY
            if (s.id in active) flags = flags or Protocol.SessionFlag.ACTIVE
            WatchSession(s.id, Text.forWatch(s.displayTitle), flags)
        }
        .toList()

    /**
     * Messages for the watch: user turns and final assistant answers. Tool steps appear only when
     * the user asked to see tool activity, and then as a one-line summary.
     */
    fun watchMessages(messages: List<HermesMessage>, toolActivity: ToolActivity): List<WatchMessage> = messages
        .mapNotNull { m ->
            when {
                m.role == "user" && m.text.isNotBlank() ->
                    WatchMessage(m.stableId, Protocol.Role.USER, 0, Text.forWatch(m.text))
                m.isFinalAssistantText -> WatchMessage(
                    m.stableId, Protocol.Role.ASSISTANT,
                    if (m.audioRefs.isNotEmpty()) Protocol.MessageFlag.HAS_AUDIO else 0,
                    Text.forWatch(m.text),
                )
                toolActivity != ToolActivity.HIDDEN && (m.role == "tool" || m.hasToolCalls) ->
                    WatchMessage(m.stableId, Protocol.Role.TOOL, 0, "Used ${m.tool_name ?: "a tool"}")
                else -> null
            }
        }
        .takeLast(Protocol.MAX_MESSAGES)

    /** New final assistant answers in [messages] that have not been notified yet. */
    fun newReplies(messages: List<HermesMessage>, alreadyNotified: Set<String>): List<HermesMessage> =
        messages.filter { it.isFinalAssistantText && it.stableId !in alreadyNotified }

    /**
     * Whether a reply in [session] should alert. Telegram sessions alert only when opted in, so
     * enabling both clients does not double-notify.
     */
    fun shouldNotify(
        session: HermesSession,
        scope: NotifyScope,
        openSessionId: String?,
        notifyTelegram: Boolean,
    ): Boolean {
        if (session.isTelegram && !notifyTelegram) return false
        return when (scope) {
            NotifyScope.ALL_ELIGIBLE -> true
            NotifyScope.OPEN_SESSION -> session.id == openSessionId
        }
    }
}
