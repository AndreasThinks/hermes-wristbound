package dev.wristbound.companion.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.wristbound.companion.core.Wristbound
import kotlinx.coroutines.launch

/**
 * Wakes the phone for each background check while the watchapp is closed. Alarms keep time
 * through deep sleep, unlike a coroutine `delay()`, and survive the process being killed. Exact
 * alarms keep the chosen interval while the phone is awake; in deep Doze Android spaces them to
 * about one every 9 minutes (sooner with the battery-optimisation exemption).
 */
object CheckAlarm {
    private const val TAG = "CheckAlarm"

    fun schedule(context: Context, seconds: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + seconds * 1000L
        val pi = intent(context)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // Exact alarms were revoked between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
        Log.d(TAG, "next check in $seconds s (exact=$exact)")
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intent(context))
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, CheckReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** One background check, with the CPU held awake until it is done. */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = Wristbound.get(context)
        val lock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristbound:check")
            ?.apply { acquire(WAKE_TIMEOUT_MS) }
        val pending = goAsync()
        controller.scope.launch {
            try {
                controller.backgroundCheck()
            } finally {
                if (lock?.isHeld == true) lock.release()
                pending.finish()
            }
        }
    }

    private companion object {
        // A safety cap: Wristbound.backgroundCheck gives up well before this.
        const val WAKE_TIMEOUT_MS = 45_000L
    }
}
