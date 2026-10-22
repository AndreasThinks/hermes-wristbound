package dev.wristbound.companion.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wristbound.companion.core.Wristbound
import kotlinx.coroutines.launch

/** Handles reply-notification actions, including ones pressed on the Pebble. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val messageId = intent.getStringExtra(EXTRA_MESSAGE) ?: return
        val controller = Wristbound.get(context)
        val pending = goAsync()
        controller.scope.launch {
            try {
                when (intent.action) {
                    ACTION_PLAY -> controller.play(sessionId, messageId, speaker = false)
                    ACTION_PLAY_SPEAKER -> controller.play(sessionId, messageId, speaker = true)
                    ACTION_OPEN_ON_WATCH -> controller.openReplyOnWatch(sessionId, messageId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = "dev.wristbound.companion.PLAY"
        const val ACTION_PLAY_SPEAKER = "dev.wristbound.companion.PLAY_SPEAKER"
        const val ACTION_OPEN_ON_WATCH = "dev.wristbound.companion.OPEN_ON_WATCH"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_MESSAGE = "message_id"
    }
}
