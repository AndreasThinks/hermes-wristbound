package dev.wristbound.companion.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.Person
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.wristbound.companion.R
import dev.wristbound.companion.ui.MainActivity

/**
 * Phone notifications. The Pebble mirrors these, including their actions, so a reply can be
 * played from the wrist while the phone stays locked and the watchapp is closed.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        // A new channel gives silent defaults without rewriting the old channel's user settings.
        // Carry an existing block forward so an upgrade cannot silently re-enable replies.
        val old = nm.getNotificationChannel("replies")
        val importance = if (old?.importance == NotificationManager.IMPORTANCE_NONE) NotificationManager.IMPORTANCE_NONE
            else NotificationManager.IMPORTANCE_DEFAULT
        nm.createNotificationChannel(
            NotificationChannel(CH_REPLIES, "Hermes replies · silent on phone", importance).apply {
                description = "Native watch replies; no phone sound, vibration or heads-up by default"
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hermes asking before a step, such as running a command"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TOOLS, "Tool activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tools Hermes is using (only when enabled in settings)"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ERRORS, "Problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Failed or uncertain submissions"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SYNC, "Background sync", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Wristbound watches for replies"
                setShowBadge(false)
            },
        )
    }

    /**
     * One reply, keyed by its message id. [silent] refreshes a reply that already alerted (its
     * audio arrived later) without alerting again. Nothing plays until an action is pressed.
     */
    fun reply(sessionId: String, messageId: String, title: String, text: String, private: Boolean, hasAudio: Boolean, silent: Boolean): Boolean {
        val notice = ReplyNotice.create(sessionId, messageId, title, text, private, hasAudio)
        val sender = Person.Builder().setName(notice.sender).setKey("hermes").setBot(true).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(notice.title)
            .setGroupConversation(true)
            .addMessage(notice.body, System.currentTimeMillis(), sender)
        val builder = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, if (hasAudio) R.color.phosphor_voice else R.color.phosphor_signal))
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        for (item in notice.actions) {
            builder.addAction(NotificationCompat.Action.Builder(0, item.label, action(item.action, sessionId, messageId))
                .setShowsUserInterface(item.showsUserInterface).build())
        }
        if (!canPost()) return false
        // Tag + fixed ID avoids hash collisions and keeps updates on the original native item.
        manager.notify(notice.key, REPLY_ID, builder.build())
        return true
    }

    /**
     * Hermes asking before a step, with its answers as actions so it can be answered from the
     * Pebble. Always alerts, whatever the reply settings say: Hermes is stopped until it is answered.
     */
    fun approval(sessionId: String, requestId: String, title: String, text: String, choices: List<String>): Boolean {
        val builder = NotificationCompat.Builder(context, CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.phosphor_signal))
            .setContentTitle("Hermes asks: $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        for (choice in choices) {
            val label = APPROVAL_LABELS[choice] ?: continue
            builder.addAction(NotificationCompat.Action.Builder(0, label, action(ActionReceiver.ACTION_APPROVE, sessionId, requestId, choice))
                .setShowsUserInterface(false).build())
        }
        return post(requestId.hashCode(), builder.build())
    }

    fun clearApproval(requestId: String) = manager.cancel(requestId.hashCode())

    /**
     * Whether a notification on [channel] can be shown: the permission is granted, the app's
     * notifications are on, and the channel is not blocked.
     */
    fun canPost(channel: String = CH_REPLIES): Boolean {
        val permitted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun toolActivity(sessionTitle: String, toolName: String) {
        val n = NotificationCompat.Builder(context, CH_TOOLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sessionTitle)
            .setContentText("Using $toolName")
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(60_000)
            .build()
        post(TOOL_ID, n)
    }

    fun clearToolActivity() = manager.cancel(TOOL_ID)

    fun problem(sessionTitle: String, detail: String) {
        val n = NotificationCompat.Builder(context, CH_ERRORS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sessionTitle)
            .setContentText(detail)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        post(ERROR_ID, n)
    }

    fun syncNotification(text: String): Notification = NotificationCompat.Builder(context, CH_SYNC)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Wristbound")
        .setContentText(text)
        .setSilent(true)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    /** Post [n] if it can be shown; false when notifications are blocked. */
    private fun post(id: Int, n: Notification): Boolean {
        if (!canPost(n.channelId)) return false
        manager.notify(id, n)
        return true
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, sessionId: String, messageId: String, choice: String? = null): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.Builder().scheme("wristbound").authority("notification")
                .appendPath(sessionId).appendPath(messageId).appendPath(action).appendPath(choice.orEmpty()).build())
            .putExtra(ActionReceiver.EXTRA_SESSION, sessionId)
            .putExtra(ActionReceiver.EXTRA_MESSAGE, messageId)
        choice?.let { intent.putExtra(ActionReceiver.EXTRA_CHOICE, it) }
        return PendingIntent.getBroadcast(
            context, (action + messageId + choice.orEmpty()).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CH_REPLIES = "replies_silent_v1"
        const val CH_TOOLS = "tools"
        const val CH_ERRORS = "errors"
        const val CH_SYNC = "sync"
        const val CH_APPROVALS = "approvals"
        const val SYNC_ID = 1
        private const val TOOL_ID = 2
        private const val ERROR_ID = 3
        private const val REPLY_ID = 4
        /** Pebble shows these as the notification's actions. */
        private val APPROVAL_LABELS = mapOf(
            "once" to "Allow once", "session" to "Allow this session", "always" to "Always allow", "deny" to "Deny",
        )
    }
}
