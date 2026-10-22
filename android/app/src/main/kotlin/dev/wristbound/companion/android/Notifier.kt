package dev.wristbound.companion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.wristbound.companion.R
import dev.wristbound.companion.core.SessionDto
import dev.wristbound.companion.core.displayTitle

/**
 * Android notifications are the delivery path while the watch shows its watchface: the Pebble
 * app mirrors them (with their actions) to the watch. Each reply has a stable notification id
 * derived from (session, message), so re-posting can never produce a second alert.
 */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Connection status", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Persistent notification while Wristbound keeps Hermes and the watch in sync"
                },
                NotificationChannel(CH_REPLIES, "Hermes replies", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Final replies from Hermes sessions"
                },
                NotificationChannel(CH_TOOLS, "Tool activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Only used when tool activity is set to Notify"
                },
            ),
        )
    }

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_wristbound)
            .setContentTitle("Hermes Wristbound")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setLocalOnly(true) // keep the status notification off the watch
            .setContentIntent(openApp())
            .build()

    fun updateServiceStatus(text: String) {
        if (!WristboundService.running) return
        notify(SERVICE_ID, serviceNotification(text))
    }

    fun showReply(session: SessionDto, msgId: Long, text: String, collapsed: Int, hasHermesAudio: Boolean, previews: Boolean) {
        val title = session.displayTitle()
        val body = if (previews) text else "New reply"
        val sub = buildString {
            if (collapsed > 0) append("+$collapsed earlier · ")
            append(if (hasHermesAudio) "Hermes audio" else "Speakable")
        }
        val n = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_wristbound)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(sub)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CH_REPLIES).setSmallIcon(R.drawable.ic_stat_wristbound)
                    .setContentTitle("Hermes").setContentText("New reply").build(),
            )
            .setGroup("session:${session.id}")
            .setContentIntent(openApp())
            .setDeleteIntent(action(NotificationActionReceiver.ACTION_DISMISS, session.id, msgId))
            .addAction(0, "Play", action(NotificationActionReceiver.ACTION_PLAY, session.id, msgId))
            .addAction(0, "Speaker", action(NotificationActionReceiver.ACTION_PLAY_SPEAKER, session.id, msgId))
            .addAction(0, "Open on watch", action(NotificationActionReceiver.ACTION_OPEN_WATCH, session.id, msgId))
            .build()
        notify(replyId(session.id, msgId), n)
    }

    fun showToolActivity(session: SessionDto, msgId: Long?, label: String) {
        val n = NotificationCompat.Builder(context, CH_TOOLS)
            .setSmallIcon(R.drawable.ic_stat_wristbound)
            .setContentTitle(session.displayTitle())
            .setContentText("Tool: $label")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        // One tool notification per session, updated in place: never an inbox item per event.
        notify(("tool:" + session.id).hashCode(), n)
    }

    fun cancelReply(sessionId: String, msgId: Long) = nm.cancel(replyId(sessionId, msgId))

    private fun notify(id: Int, n: Notification) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { nm.notify(id, n) }
        }
    }

    private fun action(action: String, sessionId: String, msgId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_SESSION, sessionId)
            .putExtra(NotificationActionReceiver.EXTRA_MSG, msgId)
        val req = (action + sessionId + msgId).hashCode()
        return PendingIntent.getBroadcast(context, req, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CH_SERVICE = "service"
        const val CH_REPLIES = "replies"
        const val CH_TOOLS = "tools"
        const val SERVICE_ID = 1
        fun replyId(sessionId: String, msgId: Long) = "$sessionId#$msgId".hashCode()
    }
}
