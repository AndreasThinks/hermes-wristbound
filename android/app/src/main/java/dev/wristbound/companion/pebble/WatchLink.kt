package dev.wristbound.companion.pebble

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sends AppMessages to the Wristbound watchapp, one at a time, in order. */
class WatchLink(context: Context) {
    private val sender = DefaultPebbleSender(context.applicationContext)
    private val mutex = Mutex()

    /** Returns true when at least one watch acknowledged the message. */
    suspend fun send(data: PebbleDictionary): Boolean = mutex.withLock {
        val results = runCatching { sender.sendDataToPebble(Protocol.WATCHAPP_UUID, data) }
            .onFailure { Log.w(TAG, "send failed", it) }
            .getOrNull()
        val ok = results?.values?.any { it == TransmissionResult.Success } == true
        if (!ok) Log.d(TAG, "watch did not ack: $results")
        ok
    }

    /** Send pages in order, stopping at the first one the watch does not acknowledge. */
    suspend fun sendAll(pages: List<PebbleDictionary>): Boolean {
        for (page in pages) if (!send(page)) return false
        return true
    }

    suspend fun startApp(): Boolean = runCatching {
        sender.startAppOnTheWatch(Protocol.WATCHAPP_UUID)?.values?.any { it == TransmissionResult.Success } == true
    }.getOrDefault(false)

    fun close() = sender.close()

    private companion object {
        const val TAG = "WatchLink"
    }
}
