package dev.wristbound.companion.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Streams one clip of PCM to the watch speaker. The watch drives the pace: it announces room
 * for more with AUDIO_PULL and the phone never sends past what it asked for, so the watch's
 * ring buffer cannot overflow however fast the link is. Chunks go one at a time, each waiting
 * for the watch's acknowledgement; a chunk the watch does not take ends the stream.
 */
class WatchStream(
    private val scope: CoroutineScope,
    private val send: suspend (PebbleDictionary) -> Boolean,
    private val onEnded: (messageId: String, reason: String) -> Unit = { _, _ -> },
) {
    private class Clip(val messageId: String, val pcm: ByteArray, val chunk: Int) {
        var sentUntil = 0
        var allowedUntil = 0
        /** A sender is running for this clip; set and cleared under the lock. */
        var pumping = false
    }

    private val lock = Mutex()
    private var clip: Clip? = null
    private var sender: Job? = null

    val activeMessageId: String? get() = clip?.messageId

    /** Start a new clip, replacing any other. The watch pulls the data once it has the header. */
    suspend fun start(messageId: String, pcm: ByteArray, chunk: Int, label: String): Boolean {
        lock.withLock {
            sender?.cancel()
            clip = Clip(messageId, pcm, chunk.coerceAtLeast(1))
        }
        val ok = send(WatchCodec.audioStart(messageId, pcm.size, Protocol.PcmFormat.PCM_8KHZ_8BIT, label))
        if (!ok) end(messageId, "Watch did not answer")
        return ok
    }

    /**
     * The watch has room up to byte [offset] + [length] (offset is what it has received). The
     * window only grows: chunks already in flight are not sent again. Replay starts a new clip.
     */
    suspend fun pull(messageId: String, offset: Int, length: Int) {
        lock.withLock {
            val c = clip?.takeIf { it.messageId == messageId } ?: return
            val until = minOf(offset.toLong() + length, c.pcm.size.toLong()).toInt()
            c.allowedUntil = maxOf(c.allowedUntil, until)
            if (!c.pumping) {
                c.pumping = true
                sender = scope.launch { pump(c) }
            }
        }
    }

    suspend fun stop(messageId: String? = null) {
        val id = lock.withLock {
            val c = clip ?: return
            if (messageId != null && c.messageId != messageId) return
            sender?.cancel()
            clip = null
            c.messageId
        }
        onEnded(id, "Stopped")
    }

    private suspend fun pump(c: Clip) {
        while (true) {
            val (offset, bytes) = lock.withLock {
                if (clip !== c || c.sentUntil >= c.allowedUntil) {
                    c.pumping = false
                    return
                }
                val from = c.sentUntil
                val to = minOf(from + c.chunk, c.allowedUntil)
                c.sentUntil = to
                from to c.pcm.copyOfRange(from, to)
            }
            if (!send(WatchCodec.audioData(c.messageId, offset, bytes))) {
                lock.withLock { if (clip === c) clip = null }
                end(c.messageId, "Watch stopped receiving")
                return
            }
        }
    }

    private fun end(messageId: String, reason: String) = onEnded(messageId, reason)
}
