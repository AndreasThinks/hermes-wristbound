package dev.wristbound.companion.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.wristbound.companion.core.KeepAlive
import dev.wristbound.companion.core.Wristbound
import dev.wristbound.companion.notify.Notifier

/**
 * The companion's one foreground service. It keeps the process running while the watchapp is
 * closed for as long as [Wristbound.keepAlive] says there is a reason: background checking, a
 * turn sent from the watch that has not finished, or a reply playing on the phone. The checks
 * themselves are scheduled by [Wristbound.updateMonitoring] ([CheckAlarm] while the watchapp is
 * closed); this service keeps the process alive, holds the CPU awake while a turn runs, and
 * checks again as soon as the network comes back (a Tailscale reconnect, say).
 */
class SyncService : Service() {
    private val controller by lazy { Wristbound.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private var turnLock: PowerManager.WakeLock? = null
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first, always: a service started with startForegroundService that
        // stops before calling it crashes the app on some Android versions.
        if (!apply(controller.keepAlive(), starting = true)) return START_NOT_STICKY
        return START_STICKY
    }

    /** Bring the service in line with [k]. False when it stopped. */
    private fun apply(k: KeepAlive, starting: Boolean = false): Boolean {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                (if (k.playing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } else 0
        try {
            if (k.needed || starting) ServiceCompat.startForeground(this, Notifier.SYNC_ID, Notifier(this).syncNotification(k.label), type)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: a sticky restart from the background.
            // The next check alarm is allowed to start the service again.
            Log.w(TAG, "could not enter the foreground: ${e.message}")
            current = null
            stopSelf()
            return false
        }
        holdForTurns(k.turns > 0)
        if (!k.needed) {
            // A need that turns up from here on starts a fresh service rather than this one.
            current = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return false
        }
        return true
    }

    /**
     * A turn's reply streams over the gateway socket, which needs the CPU to answer its pings and
     * read events; with the screen off and no lock it would stall until the phone woke. Turns are
     * bounded, and the lock has a timeout in case one never ends.
     */
    private fun holdForTurns(hold: Boolean) {
        if (hold && turnLock?.isHeld != true) {
            turnLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristbound:turn")
                ?.apply { acquire(TURN_LOCK_MS) }
        } else if (!hold) {
            turnLock?.let { if (it.isHeld) it.release() }
            turnLock = null
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                // Registering reports the current network straight away; only a return counts.
                if (first) { first = false; return }
                controller.onNetworkBack()
            }
            override fun onLost(network: Network) { first = false }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkWatch = callback }
            .onFailure { Log.w(TAG, "network callback unavailable", it) }
    }

    override fun onDestroy() {
        if (current === this) current = null
        holdForTurns(false)
        networkWatch?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkWatch = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyncService"
        private const val TURN_LOCK_MS = 15 * 60_000L

        @Volatile private var current: SyncService? = null

        /**
         * Start, update or stop the service to match [k]. A running service is updated in place,
         * since starting a foreground service from the background is refused on Android 12+
         * outside a few exemptions (boot, an exact alarm, a notification action, the
         * battery-optimisation exemption).
         */
        fun sync(context: Context, k: KeepAlive) {
            current?.let { s -> s.main.post { if (current === s) s.apply(k) }; return }
            if (!k.needed) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "background start refused: ${e.message}")
            }
        }
    }
}
