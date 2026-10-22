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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional foreground service that keeps the companion alive while the phone is locked, so
 * in-flight turns finish and replies from other Hermes surfaces are picked up by polling.
 * Hermes' API server has no push channel for session activity, so polling is the only
 * documented option (see docs/HERMES_API.md).
 */
class SyncService : Service() {
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = Wristbound.get(this)
        if (!controller.settings.backgroundSync) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, Notifier.SYNC_ID, Notifier(this).syncNotification(), type)
        if (loop?.isActive != true) {
            loop = controller.scope.launch {
                while (isActive) {
                    controller.pollOnce()
                    delay(controller.settings.pollSeconds * 1000L)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, SyncService::class.java))
    }
}
