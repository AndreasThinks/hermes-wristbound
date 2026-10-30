package dev.wristbound.companion.core

/**
 * The few choices the setup screen offers, each standing for several stored settings. They are
 * pure so the mapping both ways (choice to settings, settings to choice) can be tested, and so an
 * upgrade shows what the user had chosen with the old switches.
 */

/** What buzzes the wrist: named sets of the five alert switches in [AlertPolicy]. */
enum class AlertPreset(val label: String, val summary: String) {
    OFF("Off", "Nothing buzzes. Replies still show when you open Wristbound."),
    QUIET("Quiet", "Voice replies, and answers to what you asked from the watch."),
    NORMAL("Normal", "Also replies in desktop, terminal and web chats."),
    ALL("All", "Also messaging text, scheduled jobs and Hermes's own follow-ups."),
    CUSTOM("Custom", "Your own mix.");

    /** The switches this preset sets, or null for [CUSTOM], which keeps them as they are. */
    fun policy(current: AlertPolicy): AlertPolicy? = when (this) {
        OFF -> current.copy(enabled = false)
        QUIET -> AlertPolicy(enabled = true, audio = true, watchTurns = true, chats = false, messagingText = false, background = false)
        NORMAL -> AlertPolicy(enabled = true, audio = true, watchTurns = true, chats = true, messagingText = false, background = false)
        ALL -> AlertPolicy(enabled = true, audio = true, watchTurns = true, chats = true, messagingText = true, background = true)
        CUSTOM -> null
    }

    companion object {
        fun of(policy: AlertPolicy): AlertPreset = when {
            !policy.enabled -> OFF
            else -> listOf(QUIET, NORMAL, ALL).firstOrNull { it.policy(policy) == policy } ?: CUSTOM
        }
    }
}

/** One alert switch, in the words the setup screen uses. */
enum class AlertKind(val label: String, val note: String) {
    AUDIO("Voice replies", "From any session."),
    WATCH_TURNS("Replies to the watch", "Answers to what you asked from the watch, once you have left the watchapp."),
    CHATS("Desktop, terminal and web chats", "Replies in chats you started on the computer."),
    MESSAGING_TEXT("Messaging text", "Telegram and the like already notify your phone."),
    BACKGROUND("Background and scheduled", "Cron jobs, automations and Hermes following up on its own work.");

    fun isOn(p: AlertPolicy): Boolean = when (this) {
        AUDIO -> p.audio
        WATCH_TURNS -> p.watchTurns
        CHATS -> p.chats
        MESSAGING_TEXT -> p.messagingText
        BACKGROUND -> p.background
    }

    fun set(p: AlertPolicy, on: Boolean): AlertPolicy = when (this) {
        AUDIO -> p.copy(audio = on)
        WATCH_TURNS -> p.copy(watchTurns = on)
        CHATS -> p.copy(chats = on)
        MESSAGING_TEXT -> p.copy(messagingText = on)
        BACKGROUND -> p.copy(background = on)
    }
}

/**
 * How late a reply from another surface (desktop, Telegram, Hermes's own follow-ups) can reach
 * the wrist. Replies to turns sent from the watch stream live and do not depend on this. Stands
 * for the background-checking switch and its interval.
 */
enum class ReplySpeed(val seconds: Int?, val label: String, val note: String) {
    FAST(30, "Fast", "Uses the most battery."),
    NORMAL(60, "Normal", "Recommended."),
    SAVER(300, "Battery saver", "Fine if you mostly start chats from the watch."),
    WATCH_ONLY(null, "Only while Wristbound is open",
        "No background checks and no ongoing notification. Replies from elsewhere wait until you open Wristbound on the watch.");

    val title: String get() = seconds?.let { "$label · within ${within(it)}" } ?: label

    companion object {
        fun of(background: Boolean, seconds: Int): ReplySpeed = when {
            !background -> WATCH_ONLY
            seconds <= 30 -> FAST
            seconds <= 120 -> NORMAL
            else -> SAVER
        }

        /** "~30 s", "~1 min", "~5 min". */
        fun within(seconds: Int): String = if (seconds < 60) "~$seconds s" else "~${seconds / 60} min"
    }
}

/** How messaging chats (Telegram, Discord, Slack…) appear on the watch. */
enum class MessagingChats(val label: String, val note: String) {
    HIDDEN("Hidden", "Not listed on the watch."),
    READ_ONLY("Read only", "Read and play them; answer in the chat app itself."),
    REPLY("Read and reply", "What you send from the watch is posted into the chat.");

    val listed: Boolean get() = this != HIDDEN
    val replies: Boolean get() = this == REPLY

    companion object {
        fun of(listed: Boolean, replies: Boolean): MessagingChats = when {
            !listed -> HIDDEN
            replies -> REPLY
            else -> READ_ONLY
        }
    }
}

/** The one tool-activity choice, in place of two switches in two sections. */
fun ToolActivity.label(): String = when (this) {
    ToolActivity.HIDDEN -> "Hide"
    ToolActivity.SILENT -> "On watch"
    ToolActivity.NOTIFY -> "+ Notify"
}

/** Alerts can arrive as the Core app's mirrored notification, or as Wristbound's own banner. */
enum class AlertStyle(val label: String, val note: String) {
    NOTIFICATION("Pebble notification", "Arrives over anything. Text only; open Wristbound to play audio."),
    BANNER("Wristbound banner", "Opens Wristbound over your watchface with Play. Up to 10 s later; falls back to a notification."),
}
