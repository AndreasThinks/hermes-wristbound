package dev.wristbound.companion.android

import android.content.Context
import android.util.Log
import dev.wristbound.companion.core.WValue
import dev.wristbound.companion.core.WatchDict
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Serializes AppMessages to the watchapp. PebbleKit's send is acknowledged per message, so
 * one-at-a-time delivery respects the watch inbox. Messages are dropped (not queued forever)
 * when the watchapp is closed: the Android notification is the delivery path in that case, and
 * the watch re-requests state with T_HELLO when it opens.
 */
class WatchBridge(private val context: Context, scope: CoroutineScope) {
    @Volatile var appOpen: Boolean = false
    private val queue = Channel<WatchDict>(capacity = 256)
    private val sender by lazy { DefaultPebbleSender(context) }

    init {
        scope.launch {
            for (msg in queue) deliver(msg)
        }
    }

    fun send(msg: WatchDict) {
        if (!appOpen) return
        if (queue.trySend(msg).isFailure) Log.w(TAG, "watch queue full; dropping message")
    }

    suspend fun startApp() {
        runCatching { sender.startAppOnTheWatch(WATCHAPP_UUID) }
            .onFailure { Log.w(TAG, "startAppOnTheWatch failed", it) }
    }

    private suspend fun deliver(msg: WatchDict) {
        val dict = toPebble(msg)
        repeat(3) { attempt ->
            if (!appOpen) return
            val results = runCatching { sender.sendDataToPebble(WATCHAPP_UUID, dict) }
                .onFailure { Log.w(TAG, "send failed", it) }
                .getOrNull()
            if (results == null) {
                Log.w(TAG, "Pebble app not reachable")
                return
            }
            if (results.isEmpty()) return // no connected watch
            when {
                results.values.any { it is TransmissionResult.Success } -> return
                results.values.all { it is TransmissionResult.FailedDifferentAppOpen } -> {
                    appOpen = false; return
                }
                results.values.any { it is TransmissionResult.FailedNoPermissions } -> {
                    Log.e(TAG, "watchapp package.json does not list this companion package"); return
                }
                else -> delay(300L * (attempt + 1))
            }
        }
    }

    companion object {
        private const val TAG = "WatchBridge"
        /** Must match pebble/package.json `uuid`. */
        val WATCHAPP_UUID: UUID = UUID.fromString("5f171372-ab64-44b9-8ab3-f7db56306794")

        fun toPebble(msg: WatchDict): PebbleDictionary = msg.entries.associate { (k, v) ->
            k.toUInt() to when (v) {
                is WValue.Text -> PebbleDictionaryItem.Text(v.value)
                is WValue.U32 -> PebbleDictionaryItem.UInt32(v.value.toUInt())
            }
        }

        fun fromPebble(d: PebbleDictionary): WatchDict = d.entries.mapNotNull { (k, v) ->
            val value = when (v) {
                is PebbleDictionaryItem.Text -> WValue.Text(v.value)
                is PebbleDictionaryItem.UInt32 -> WValue.U32(v.value.toLong())
                is PebbleDictionaryItem.Int32 -> WValue.U32(v.value.toLong() and 0xFFFFFFFFL)
                is PebbleDictionaryItem.UInt16 -> WValue.U32(v.value.toLong())
                is PebbleDictionaryItem.Int16 -> WValue.U32(v.value.toLong() and 0xFFFFL)
                is PebbleDictionaryItem.UInt8 -> WValue.U32(v.value.toLong())
                is PebbleDictionaryItem.Int8 -> WValue.U32(v.value.toLong() and 0xFFL)
                is PebbleDictionaryItem.Bytes -> null
            }
            value?.let { k.toInt() to it }
        }.toMap()
    }
}
