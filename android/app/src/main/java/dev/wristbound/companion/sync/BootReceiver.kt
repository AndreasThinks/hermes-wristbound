package dev.wristbound.companion.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wristbound.companion.core.Wristbound

/** Restores reply checking and the foreground service after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Wristbound.get(context).updateMonitoring()
    }
}
