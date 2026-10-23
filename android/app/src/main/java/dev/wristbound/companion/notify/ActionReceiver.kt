package dev.wristbound.companion.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wristbound.companion.core.Wristbound
import kotlinx.coroutines.launch

/** Handles reply and approval notification actions, including ones pressed on the Pebble. */
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
                    ACTION_APPROVE -> intent.getStringExtra(EXTRA_CHOICE)?.let { controller.answerApproval(messageId, it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PLAY = ReplyNotice.ACTION_PLAY
        const val ACTION_PLAY_SPEAKER = ReplyNotice.ACTION_PLAY_SPEAKER
        const val ACTION_OPEN_ON_WATCH = ReplyNotice.ACTION_OPEN_ON_WATCH
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_MESSAGE = "message_id"
        /** For [ACTION_APPROVE]: Hermes' answer name; [EXTRA_MESSAGE] holds the request id. */
        const val EXTRA_CHOICE = "choice"
        const val ACTION_APPROVE = "dev.wristbound.companion.APPROVE"
    }
}
