package dev.wristbound.companion.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps Hermes sync and the watch link alive while the phone is locked.
 *
 * Android does not keep the CPU awake for a foreground service, so polling is driven by:
 *  - an in-process loop while the CPU is awake,
 *  - `setAndAllowWhileIdle` alarms (the OS may defer these to ~10 min in deep Doze),
 *  - a timed wake lock while a watch-submitted run is being followed (SSE wakes the radio),
 *  - optional "low latency" mode: a partial wake lock for as long as the service runs.
 */
class WristboundService : Service() {
    private var lowLatencyLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val ok = runCatching {
            ServiceCompat.startForeground(
                this, Notifier.SERVICE_ID, hub().notifier.serviceNotification("Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { Log.e(TAG, "cannot start foreground service", it) }.isSuccess
        if (!ok) {
            stopSelf(); return
        }
        running = true
        instance = this
        hub().startLoop()
        applyPowerMode()
        scheduleAlarm(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_POLL) hub().requestPoll()
        applyPowerMode()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        hub().stopLoop()
        lowLatencyLock?.takeIf { it.isHeld }?.release()
        cancelAlarm(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hub() = (application as WristboundApp).hub

    private fun applyPowerMode() {
        val want = hub().settings.lowLatency
        val held = lowLatencyLock?.isHeld == true
        if (want && !held) {
            lowLatencyLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristbound:lowlatency").apply {
                    setReferenceCounted(false); acquire()
                }
        } else if (!want && held) {
            lowLatencyLock?.release()
        }
    }

    private fun updateTypes(playing: Boolean) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (playing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        runCatching {
            ServiceCompat.startForeground(
                this, Notifier.SERVICE_ID,
                hub().notifier.serviceNotification(if (playing) "Playing reply" else hub().status.value.text),
                types,
            )
        }.onFailure { Log.w(TAG, "could not update service type (playback continues)", it) }
    }

    /** Alarm tick: run one poll under a short wake lock, then re-arm. */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext as WristboundApp
            val lock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristbound:poll").apply { acquire(60_000) }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    app.hub.pollOnce()
                } finally {
                    if (running) scheduleAlarm(context)
                    if (lock.isHeld) lock.release()
                    pending.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "WristboundService"
        const val ACTION_POLL = "dev.wristbound.companion.POLL"

        @Volatile var running = false
            private set
        @Volatile private var instance: WristboundService? = null

        fun start(context: Context) {
            val intent = Intent(context, WristboundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "start refused (background start restriction?)", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WristboundService::class.java))
        }

        fun setPlaying(context: Context, playing: Boolean) {
            val svc = instance
            if (svc != null) {
                svc.updateTypes(playing)
            } else if (playing) {
                start(context)
            }
        }

        fun scheduleAlarm(context: Context) {
            val app = context.applicationContext as WristboundApp
            val am = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + app.hub.settings.pollSeconds * 1000L
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(context))
        }

        private fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 7, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Holds a partial wake lock for up to [timeoutMs] while [block] runs. */
        suspend fun <T> withWakeLock(context: Context, tag: String, timeoutMs: Long, block: suspend () -> T): T {
            val lock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristbound:$tag").apply { acquire(timeoutMs) }
            try {
                return block()
            } finally {
                if (lock.isHeld) lock.release()
            }
        }
    }
}
