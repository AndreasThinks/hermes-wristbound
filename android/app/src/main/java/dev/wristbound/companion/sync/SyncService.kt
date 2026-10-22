package dev.wristbound.companion.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.wristbound.companion.core.Wristbound
import dev.wristbound.companion.notify.Notifier

/**
 * Optional foreground service that keeps the companion alive while the phone is locked and the
 * watchapp is closed, so in-flight turns finish and replies from other Hermes surfaces are
 * still picked up. The polling loop itself belongs to [Wristbound.updateMonitoring], which
 * also runs it while the watchapp is open; this service only keeps the process around.
 * Polling survives sleep and network changes without a socket to keep alive (see
 * docs/HERMES_API.md).
 */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = Wristbound.get(this)
        if (!controller.settings.backgroundSync) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifier.SYNC_ID, Notifier(this).syncNotification(), type)
        controller.updateMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        Wristbound.get(this).updateMonitoring()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, SyncService::class.java))
    }
}
