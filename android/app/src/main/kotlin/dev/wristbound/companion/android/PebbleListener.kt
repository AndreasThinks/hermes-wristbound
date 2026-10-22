package dev.wristbound.companion.android

import android.util.Log
import dev.wristbound.companion.core.WatchCommand
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/** Bound by the Pebble app while the Wristbound watchapp is open (PebbleKit Android 2). */
class PebbleListener : BasePebbleListenerService() {
    private val hub get() = (application as WristboundApp).hub

    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != WatchBridge.WATCHAPP_UUID) return ReceiveResult.Nack
        val cmd = WatchCommand.decode(WatchBridge.fromPebble(data))
        Log.d(TAG, "watch -> ${cmd.javaClass.simpleName}")
        hub.handleWatch(cmd)
        // Ack means "received by the companion", never "done by Hermes"; outcomes are reported
        // back with T_SUBMIT_STATE / T_PLAYBACK_STATE.
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != WatchBridge.WATCHAPP_UUID) return
        if (hub.settings.serviceEnabled && !WristboundService.running) WristboundService.start(this)
        hub.onWatchAppOpened()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == WatchBridge.WATCHAPP_UUID) hub.onWatchAppClosed()
    }

    private companion object {
        const val TAG = "PebbleListener"
    }
}
