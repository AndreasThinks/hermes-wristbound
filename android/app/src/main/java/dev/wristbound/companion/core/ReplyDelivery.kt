package dev.wristbound.companion.core

/** One transport per reply. An ACK means receipt, never playback. */
class ReplyDelivery(
    private val watchOpen: () -> Boolean,
    private val sendWatch: suspend (ReplyAlert) -> Boolean,
    private val closeWatch: () -> Unit,
    private val postNative: (ReplyAlert) -> Boolean,
) {
    suspend fun deliver(alert: ReplyAlert): Boolean {
        if (alert.silent) return watchOpen() || postNative(alert)
        if (watchOpen()) {
            if (sendWatch(alert)) return true
            closeWatch()
        }
        return postNative(alert)
    }
}
