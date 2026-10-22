package dev.wristbound.companion.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Notification actions (also shown on the watch by the Pebble app). Every action carries the
 * exact session and message id, so "Play" always targets that reply, never "the latest audio".
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val msgId = intent.getLongExtra(EXTRA_MSG, -1).takeIf { it >= 0 } ?: return
        val hub = (context.applicationContext as WristboundApp).hub
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_PLAY -> hub.player.play(sessionId, msgId, speaker = false)
                    ACTION_PLAY_SPEAKER -> hub.player.play(sessionId, msgId, speaker = true)
                    ACTION_OPEN_WATCH -> hub.openOnWatch(sessionId, msgId)
                    ACTION_DISMISS -> hub.markRead(sessionId, msgId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "dev.wristbound.companion.PLAY"
        const val ACTION_PLAY_SPEAKER = "dev.wristbound.companion.PLAY_SPEAKER"
        const val ACTION_OPEN_WATCH = "dev.wristbound.companion.OPEN_WATCH"
        const val ACTION_DISMISS = "dev.wristbound.companion.DISMISS"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_MSG = "message_id"
    }
}
