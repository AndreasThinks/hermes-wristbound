package dev.wristbound.companion.pebble

import android.util.Log
import dev.wristbound.companion.core.Wristbound
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Bound by the Pebble app while the Wristbound watchapp is open. Commands are acknowledged
 * immediately and handled on the controller's scope, so a slow Hermes call never makes the
 * watch's AppMessage time out.
 */
class WristboundListenerService : BasePebbleListenerService() {
    private val controller by lazy { Wristbound.get(this) }

    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != Protocol.WATCHAPP_UUID) return ReceiveResult.Nack
        val cmd = WatchCodec.decode(data) ?: return ReceiveResult.Nack.also { Log.w(TAG, "undecodable: $data") }
        controller.scope.launch { controller.onWatchCommand(cmd) }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == Protocol.WATCHAPP_UUID) controller.onWatchAppOpened()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == Protocol.WATCHAPP_UUID) controller.onWatchAppClosed()
    }

    private companion object {
        const val TAG = "WristboundListener"
    }
}
