package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Which replies alert, read from settings once per decision. Every delivery path (a turn sent
 * from the watch, background polling, recovery after a dropped socket) goes through this.
 * Nothing here ever starts playback: an alert only offers Play.
 */
data class AlertPolicy(
    val replies: Boolean,
    val scope: NotifyScope,
    /** Text alerts for Telegram sessions; off by default so Telegram does not double-notify. */
    val telegramText: Boolean,
    /** Alerts for Telegram replies that carry Hermes audio, even with [telegramText] off. */
    val telegramAudio: Boolean,
    val openSessionId: String?,
) {
    private fun inScope(session: HermesSession) = when (scope) {
        NotifyScope.ALL_ELIGIBLE -> true
        NotifyScope.OPEN_SESSION -> session.id == openSessionId
    }

    fun textAlerts(session: HermesSession) = replies && inScope(session) && (!session.isTelegram || telegramText)

    fun audioAlerts(session: HermesSession) = replies && inScope(session) && (!session.isTelegram || telegramAudio)
}

/** One reply to show, on the open watchapp or as a phone notification. */
data class ReplyAlert(
    val sessionId: String,
    val title: String,
    val messageId: String,
    val text: String,
    val hasAudio: Boolean,
    /** Audio arrived for a reply that already alerted: refresh it without alerting again. */
    val silent: Boolean,
)

/** Persistent alert bookkeeping (on the phone, [LocalState]). */
interface AlertStore {
    /** Record [key]; false if it was already recorded. */
    fun markAlerted(key: String): Boolean
    fun isAlerted(key: String): Boolean
    /** Null until the first poll has taken a baseline. */
    var pollCursors: PollCursors?
}

/**
 * Per-session polling checkpoints. [sessions] holds the activity each session was last
 * processed up to; a session missing from it is compared with [since], the baseline time.
 */
@Serializable
data class PollCursors(val since: Double, val sessions: Map<String, Double>)

/**
 * Decides and delivers reply alerts, exactly once each.
 *
 * Text and audio are tracked separately (`<id>` and `audio:<id>`), so a Telegram reply whose
 * text is not alerting can still alert once when its audio is there, including audio that
 * arrives after the text, and a reply that already alerted is only refreshed when its audio
 * turns up.
 */
class ReplyAlerts(
    private val store: AlertStore,
    private val policy: () -> AlertPolicy,
    private val deliver: suspend (ReplyAlert) -> Unit,
) {
    /**
     * A turn sent from the watch ended in [session]: alert its reply, if the policy allows.
     * Only answers after the last user row count, so older history never alerts here.
     */
    suspend fun turnFinished(session: HermesSession, messages: List<HermesMessage>) {
        for (reply in SessionMapper.latestTurnReplies(messages)) consider(session, reply, floor = null)
    }

    /**
     * One background pass over [sessions]. The first pass ever only records a baseline, so
     * old history is not replayed. A session's checkpoint advances only after its history was
     * fetched and processed: a failed fetch, or a turn still running ([busy]), leaves it for the
     * next pass. Checkpoints persist, so a restart catches up on what changed meanwhile.
     *
     * Returns the sessions whose activity moved.
     */
    suspend fun poll(
        sessions: List<HermesSession>,
        busy: (String) -> Boolean,
        fetch: suspend (String) -> List<HermesMessage>,
    ): Set<String> {
        val saved = store.pollCursors
        if (saved == null) {
            store.pollCursors = PollCursors(sessions.maxOfOrNull { it.activity } ?: 0.0, bounded(sessions.associate { it.id to it.activity }))
            return emptySet()
        }
        val cursors = saved.sessions.toMutableMap()
        val moved = mutableSetOf<String>()
        try {
            for (session in sessions) {
                val floor = cursors[session.id] ?: saved.since
                if (session.activity <= floor + EPSILON) continue
                moved += session.id
                if (busy(session.id)) continue
                val messages = try {
                    fetch(session.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                for (reply in SessionMapper.replies(messages)) consider(session, reply, floor)
                cursors[session.id] = session.activity
            }
        } finally {
            store.pollCursors = PollCursors(saved.since, bounded(cursors))
        }
        return moved
    }

    /** Replies at or before [floor] predate the checkpoint; only their late audio still counts. */
    private suspend fun consider(session: HermesSession, reply: SessionMapper.Reply, floor: Double?) {
        if (floor != null && !store.isAlerted(reply.id) && (reply.message.timestamp ?: Double.MAX_VALUE) <= floor + EPSILON) return
        decide(session, reply, policy())?.let { deliver(it) }
    }

    /** The alert for [reply], if any, recording it so it is never decided twice. */
    fun decide(session: HermesSession, reply: SessionMapper.Reply, policy: AlertPolicy): ReplyAlert? {
        val audioKey = AUDIO_PREFIX + reply.id
        fun alert(silent: Boolean) =
            ReplyAlert(session.id, session.displayTitle, reply.id, reply.message.text, reply.hasAudio, silent)

        if (store.markAlerted(reply.id)) {
            // First sight of this reply. Either way it is now decided; audio is decided with it
            // when it is already there.
            if (reply.hasAudio) store.markAlerted(audioKey)
            return when {
                policy.textAlerts(session) -> alert(silent = false)
                reply.hasAudio && policy.audioAlerts(session) -> alert(silent = false)
                else -> null
            }
        }
        // Seen before: only newly arrived audio matters.
        if (!reply.hasAudio || !store.markAlerted(audioKey)) return null
        return when {
            policy.textAlerts(session) -> alert(silent = true)
            policy.audioAlerts(session) -> alert(silent = false)
            else -> null
        }
    }

    private fun bounded(map: Map<String, Double>): Map<String, Double> =
        if (map.size <= MAX_CURSORS) map
        else map.entries.sortedByDescending { it.value }.take(MAX_CURSORS).associate { it.key to it.value }

    companion object {
        const val AUDIO_PREFIX = "audio:"
        private const val EPSILON = 0.001
        private const val MAX_CURSORS = 200
    }
}
