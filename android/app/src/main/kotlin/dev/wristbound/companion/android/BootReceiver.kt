package dev.wristbound.companion.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts sync after reboot/update (connectedDevice FGS may start from BOOT_COMPLETED). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hub = (context.applicationContext as WristboundApp).hub
        if (hub.settings.serviceEnabled && hub.settings.isConfigured) WristboundService.start(context)
    }
}
