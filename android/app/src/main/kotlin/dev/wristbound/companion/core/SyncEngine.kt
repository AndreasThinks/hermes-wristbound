package dev.wristbound.companion.core

/** A new Hermes row discovered by polling, with the visibility/alert decision applied. */
data class SyncItem(
    val session: SessionDto,
    val message: MessageDto,
    val kind: MessageKind,
    val decision: Decision,
    /** Number of older alertable rows in the same session collapsed into this alert. */
    val collapsed: Int = 0,
)

data class PollResult(
    val state: SyncState,
    val sessions: List<SessionDto>,
    val items: List<SyncItem>,
    /** Session messages fetched this poll, keyed by session id (chronological). */
    val fetched: Map<String, List<MessageDto>>,
)

/**
 * Discovers new replies by polling. Hermes exposes no event feed for turns executed by other
 * surfaces (e.g. the Telegram gateway handling a Ringbearer capture), so the session list's
 * `last_active` / `message_count` act as change hints and message row ids act as cursors.
 *
 * Rules:
 *  - First poll after install records cursors without alerting (no notification flood).
 *  - Sessions first seen later alert for rows newer than the bootstrap time.
 *  - Each (session, message) alerts at most once ([SyncState.alerted]).
 *  - Per session per poll, only the newest alertable row alerts; older ones are collapsed.
 */
class SyncEngine(
    private val sessionLimit: Int = 30,
    private val messageWindow: Int = 30,
) {
    suspend fun poll(
        api: HermesApi,
        state: SyncState,
        prefs: AlertPrefs,
        nowEpochSeconds: Double,
        forceSessions: Set<String> = emptySet(),
    ): PollResult {
        val sessions = api.listSessions(sessionLimit, 0).data.filter(SessionFilter::isEligible)
        val bootstrapping = state.bootstrappedAt == null
        val bootstrapAt = state.bootstrappedAt ?: nowEpochSeconds
        var next = state.copy(bootstrappedAt = bootstrapAt)
        val items = mutableListOf<SyncItem>()
        val fetched = mutableMapOf<String, List<MessageDto>>()

        for (s in sessions) {
            val cursor = next.cursors[s.id]
            val changed = cursor == null || cursor.lastActive != s.lastActive ||
                cursor.messageCount != s.messageCount || s.id in forceSessions
            if (!changed) continue
            val visible = SessionFilter.isVisible(s, prefs.includeTelegram)
            val couldAlert = visible || prefs.alertFilteredSessions
            // Skip fetching content of sessions the user hid and did not opt into alerts for,
            // unless they already have a cursor (keeps unread state honest when un-hidden).
            if (!couldAlert && cursor != null && s.id !in forceSessions) {
                next = next.copy(cursors = next.cursors + (s.id to cursor.copy(lastActive = s.lastActive, messageCount = s.messageCount)))
                continue
            }
            val messages = api.latestMessages(s.id, messageWindow).data.sortedBy { it.id }
            fetched[s.id] = messages
            val maxId = messages.maxOfOrNull { it.id } ?: cursor?.lastMessageId ?: 0
            val latestReply = messages.lastOrNull { MessageClassifier.kind(it) == MessageKind.ASSISTANT_FINAL }?.id
                ?: cursor?.latestReplyId ?: 0

            val fresh = when {
                bootstrapping -> emptyList()
                cursor == null -> messages.filter { (it.timestamp ?: 0.0) >= bootstrapAt }
                else -> messages.filter { it.id > cursor.lastMessageId }
            }
            val decided = fresh.map { m ->
                val kind = MessageClassifier.kind(m)
                SyncItem(s, m, kind, AlertPolicy.decide(kind, s, prefs, next.activeSessionId))
            }.filter { it.decision.display || it.decision.alert }

            val alertable = decided.filter { it.decision.alert && !next.alreadyAlerted(s.id, it.message.id) }
            val newestAlert = alertable.lastOrNull()
            for (item in decided) {
                val alert = item === newestAlert
                val collapsed = if (alert) alertable.size - 1 else 0
                items += item.copy(
                    decision = item.decision.copy(alert = alert),
                    collapsed = collapsed,
                )
            }
            alertable.forEach { next = next.withAlerted(s.id, it.message.id) }

            next = next.copy(
                cursors = next.cursors + (
                    s.id to SessionCursor(
                        lastMessageId = maxOf(maxId, cursor?.lastMessageId ?: 0),
                        lastActive = s.lastActive,
                        messageCount = s.messageCount,
                        latestReplyId = maxOf(latestReply, cursor?.latestReplyId ?: 0),
                    )
                ),
            )
            if (bootstrapping) {
                // Existing history counts as read at install time.
                next = next.copy(lastRead = next.lastRead + (s.id to latestReply))
            }
        }
        // Bound the cursor map to sessions still in the recent window plus the active one.
        val keep = sessions.map { it.id }.toSet() + listOfNotNull(next.activeSessionId)
        if (next.cursors.size > sessionLimit * 3) {
            next = next.copy(
                cursors = next.cursors.filterKeys { it in keep },
                lastRead = next.lastRead.filterKeys { it in keep },
            )
        }
        return PollResult(next.copy(lastPollOkAt = (nowEpochSeconds * 1000).toLong(), lastError = null), sessions, items, fetched)
    }
}
