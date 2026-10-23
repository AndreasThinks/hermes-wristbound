package dev.wristbound.companion.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reply so far while Hermes writes it, passed to [send] at most once every [intervalMs]:
 * AppMessage is slow, and the watch only needs to keep up with reading speed. The first chunk
 * goes at once. A null chunk ends one segment of the reply (Hermes writes some text, uses a
 * tool, then writes more), and the next segment replaces it.
 */
class ReplyStream(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 700,
    private val send: suspend (String) -> Unit,
) {
    private val lock = Any()
    private val text = StringBuilder()
    private var segmentEnded = false
    private var dirty = false
    private var flusher: Job? = null

    fun delta(chunk: String?) {
        synchronized(lock) { add(chunk) }
    }

    private fun add(chunk: String?) {
        if (chunk == null) {
            segmentEnded = text.isNotEmpty()
            return
        }
        if (chunk.isEmpty()) return
        if (segmentEnded) {
            text.clear()
            segmentEnded = false
        }
        text.append(chunk)
        dirty = true
        if (flusher == null) flusher = scope.launch { flush() }
    }

    /** Stop sending: the turn is over and the transcript takes over. */
    suspend fun finish() {
        val job = synchronized(lock) { flusher.also { flusher = null; dirty = false } }
        job?.cancelAndJoin()
    }

    private suspend fun flush() {
        while (true) {
            val snapshot = synchronized(lock) {
                if (!dirty) {
                    flusher = null
                    return
                }
                dirty = false
                text.toString()
            }
            send(snapshot)
            delay(intervalMs)
        }
    }
}
