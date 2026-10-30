package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** What a reply answers, which decides the switch that governs its alert. */
enum class ReplyKind {
    /** A turn sent from the watch. */
    WATCH_TURN,
    /** A chat on this machine: the desktop, terminal, web UI or API. */
    CHAT,
    /** A chat on a messaging platform, which notifies the phone itself. */
    MESSAGING,
    /**
     * A scheduled or automation session, or a turn Hermes started itself that no person asked
     * for. Finished work a person asked for (a delegation, a background process) is not
     * background: it alerts like a reply in the conversation that asked.
     */
    BACKGROUND,
}

/**
 * Which replies alert, read from settings once per decision. Every delivery path (a turn sent
 * from the watch, background polling, recovery after a dropped socket) goes through this.
 * Nothing here ever starts playback: an alert only offers Play.
 *
 * A reply that carries Hermes audio is governed by [audio] alone, from any session: listening
 * is what the watch is for. A text reply is governed by its kind's switch.
 */
data class AlertPolicy(
    /** Master switch. */
    val enabled: Boolean,
    val audio: Boolean,
    val watchTurns: Boolean,
    val chats: Boolean,
    /** Text alerts for messaging chats; off by default so Telegram and the like do not double-notify. */
    val messagingText: Boolean,
    val background: Boolean,
) {
    fun textAlerts(kind: ReplyKind) = enabled && when (kind) {
        ReplyKind.WATCH_TURN -> watchTurns
        ReplyKind.CHAT -> chats
        ReplyKind.MESSAGING -> messagingText
        ReplyKind.BACKGROUND -> background
    }

    val audioAlerts: Boolean get() = enabled && audio

    companion object {
        /**
         * The kind of [reply] in [session]; [watchTurn] when it answers a turn sent from the
         * watch, directly or through work that turn started.
         */
        fun kindOf(session: HermesSession, reply: SessionMapper.Reply, watchTurn: Boolean): ReplyKind = when {
            session.isAutomation || reply.background -> ReplyKind.BACKGROUND
            watchTurn -> ReplyKind.WATCH_TURN
            session.isMessaging -> ReplyKind.MESSAGING
            else -> ReplyKind.CHAT
        }
    }
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
    /** Forget [key], so an alert that could not be shown is decided again. */
    fun unmarkAlerted(key: String)
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
 *
 * Deciding and delivering are separate steps. [deliver] says whether the alert reached the
 * user (the watch acknowledged it, or a phone notification was posted). When it did not, the
 * decision is rolled back and the session's checkpoint holds, so the next pass tries again;
 * after [MAX_ATTEMPTS] failures the alert is dropped rather than retried forever.
 */
class ReplyAlerts(
    private val store: AlertStore,
    private val policy: () -> AlertPolicy,
    /** Every first decision, for the setup screen's list of recent alerts. */
    private val log: (AlertRecord) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val deliver: suspend (ReplyAlert) -> Boolean,
) {
    /** Failed deliveries per reply id, in memory: a restart gets a fresh set of attempts. */
    private val failures = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 100
    }

    /**
     * A turn sent from the watch ended in [session]: alert its reply, if the policy allows.
     * Only answers after the last user row count, so older history never alerts here.
     */
    suspend fun turnFinished(session: HermesSession, messages: List<HermesMessage>) {
        // Remember the message the watch sent, so work it started alerts as a watch reply when it finishes.
        messages.lastOrNull { it.role == "user" && !it.opensBackgroundTurn }?.let { store.markAlerted(WATCH_ROW_PREFIX + it.stableId) }
        for (reply in SessionMapper.latestTurnReplies(messages)) consider(session, reply, floor = null, watchTurn = true)
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
                var delivered = true
                for (reply in SessionMapper.replies(messages)) delivered = consider(session, reply, floor, watchTurn = false) && delivered
                // An alert that reached nobody keeps the checkpoint, so the next pass retries it.
                if (delivered) cursors[session.id] = session.activity
            }
        } finally {
            store.pollCursors = PollCursors(saved.since, bounded(cursors))
        }
        return moved
    }

    /**
     * Replies at or before [floor] predate the checkpoint; only their late audio still counts.
     * Returns false when an alert was due but could not be delivered (and will be retried).
     */
    private suspend fun consider(session: HermesSession, reply: SessionMapper.Reply, floor: Double?, watchTurn: Boolean): Boolean {
        val latestAudio = reply.message.audio_attachments.maxOfOrNull { it.created_at } ?: 0.0
        if (floor != null && !store.isAlerted(reply.id) &&
            maxOf(reply.message.timestamp ?: Double.MAX_VALUE, latestAudio) <= floor + EPSILON) return true
        val fromWatch = watchTurn || reply.originRow?.let { store.isAlerted(WATCH_ROW_PREFIX + it) } == true
        val kind = AlertPolicy.kindOf(session, reply, fromWatch)
        val decision = decide(session, reply, policy(), kind) ?: return true
        fun note(outcome: AlertRecord.Outcome) {
            if (!decision.alert.silent) log(record(session, reply, kind, decision.alert.hasAudio, outcome))
        }
        if (deliver(decision.alert)) {
            store.markAlerted(DELIVERED_PREFIX + reply.id)
            synchronized(failures) { failures.remove(reply.id) }
            note(AlertRecord.Outcome.ALERTED)
            return true
        }
        val attempts = synchronized(failures) { (failures[reply.id] ?: 0).plus(1).also { failures[reply.id] = it } }
        if (attempts >= MAX_ATTEMPTS) {
            note(AlertRecord.Outcome.FAILED)
            return true
        }
        decision.marked.forEach(store::unmarkAlerted)
        return false
    }

    /** An alert to deliver, and the keys recorded for it (rolled back if delivery fails). */
    data class Decision(val alert: ReplyAlert, val marked: List<String>)

    /** The alert for [reply], if any, recording it so it is never decided twice. */
    fun decide(session: HermesSession, reply: SessionMapper.Reply, policy: AlertPolicy, kind: ReplyKind): Decision? {
        val audioKey = AUDIO_PREFIX + reply.id
        fun alert(silent: Boolean, marked: List<String>) =
            Decision(ReplyAlert(session.id, session.displayTitle, reply.id, reply.message.text, reply.hasAudio, silent), marked)

        if (store.markAlerted(reply.id)) {
            // First sight of this reply. Either way it is now decided; audio is decided with it
            // when it is already there.
            if (reply.hasAudio) store.markAlerted(audioKey)
            val marked = if (reply.hasAudio) listOf(reply.id, audioKey) else listOf(reply.id)
            val due = if (reply.hasAudio) policy.audioAlerts else policy.textAlerts(kind)
            if (!due) logSilent(session, reply, policy, kind)
            return if (due) alert(silent = false, marked) else null
        }
        // Seen before: only newly arrived audio matters.
        if (!reply.hasAudio || !store.markAlerted(audioKey)) return null
        return when {
            // The text already alerted: add Play to it without alerting again.
            policy.enabled && store.isAlerted(DELIVERED_PREFIX + reply.id) -> alert(silent = true, listOf(audioKey))
            policy.audioAlerts -> alert(silent = false, listOf(audioKey))
            else -> null.also { logSilent(session, reply, policy, kind) }
        }
    }

    private fun logSilent(session: HermesSession, reply: SessionMapper.Reply, policy: AlertPolicy, kind: ReplyKind) =
        log(record(session, reply, kind, reply.hasAudio, AlertRecord.Outcome.SILENT, AlertRecord.silentReason(policy, kind, reply.hasAudio)))

    private fun record(
        session: HermesSession, reply: SessionMapper.Reply, kind: ReplyKind, audio: Boolean, outcome: AlertRecord.Outcome, reason: String = "",
    ) = AlertRecord(now(), session.displayTitle, AlertRecord.preview(reply.message.text), kind, audio, outcome, reason)

    private fun bounded(map: Map<String, Double>): Map<String, Double> =
        if (map.size <= MAX_CURSORS) map
        else map.entries.sortedByDescending { it.value }.take(MAX_CURSORS).associate { it.key to it.value }

    companion object {
        const val AUDIO_PREFIX = "audio:"
        const val DELIVERED_PREFIX = "delivered:"
        /** A user row sent from the watch, by stable id. */
        const val WATCH_ROW_PREFIX = "watch-row:"
        const val MAX_ATTEMPTS = 5
        private const val EPSILON = 0.001
        private const val MAX_CURSORS = 200
    }
}
