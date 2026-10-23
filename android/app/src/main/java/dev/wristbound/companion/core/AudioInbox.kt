package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.pebble.Text
import kotlinx.serialization.Serializable

/** One assistant reply that carries audio Hermes generated, for the watch's Recent audio list. */
@Serializable
data class AudioItem(
    val sessionId: String,
    val messageId: String,
    val sessionTitle: String,
    val preview: String,
    /** Seconds since the epoch, from the reply's transcript row. */
    val timestamp: Double,
    val telegram: Boolean,
)

/**
 * The Recent audio inbox: assistant replies with Hermes audio across sessions, newest first.
 * Hermes is the source of truth; this only keeps a bounded copy of what was found, so the list
 * shows at once and survives a restart or an offline phone.
 *
 * Window: the [SESSIONS] most recently active visible sessions, the last [MESSAGES] transcript
 * rows of each, and the newest [MAX_ITEMS] replies overall. Retained automatic voice and MEDIA tool audio are included; the user's recordings are excluded by
 * [SessionMapper.replies].
 */
object AudioInbox {
    const val SESSIONS = 12
    const val MESSAGES = 60
    const val MAX_ITEMS = 30
    private const val PREVIEW = 60

    /** The audio replies in one session's transcript. */
    fun collect(session: HermesSession, messages: List<HermesMessage>): List<AudioItem> =
        SessionMapper.replies(messages).filter { it.hasAudio }.map { r ->
            AudioItem(
                sessionId = session.id,
                messageId = r.id,
                sessionTitle = Text.forWatch(session.displayTitle),
                preview = Text.fit(Text.forWatch(r.message.text).replace('\n', ' '), PREVIEW).first,
                timestamp = r.message.timestamp ?: session.activity,
                telegram = session.isMessaging,
            )
        }

    /**
     * [incoming] merged into [existing]: one entry per reply (the newer copy wins, so a title or
     * preview change is picked up), newest first, at most [max].
     */
    fun merge(existing: List<AudioItem>, incoming: List<AudioItem>, max: Int = MAX_ITEMS): List<AudioItem> {
        val byId = LinkedHashMap<String, AudioItem>()
        for (item in existing) byId[item.messageId] = item
        for (item in incoming) byId[item.messageId] = item
        return byId.values.sortedByDescending { it.timestamp }.take(max)
    }

    /** What the watch lists: Telegram items only when Telegram sessions are shown. */
    fun visible(items: List<AudioItem>, includeMessaging: Boolean, max: Int): List<AudioItem> =
        items.filter { includeMessaging || !it.telegram }.take(max)
}
