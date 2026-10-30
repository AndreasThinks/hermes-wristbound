package dev.wristbound.companion.core

/** One link in the Hermes → phone → watch chain. */
enum class LinkState { OK, PROBLEM, UNKNOWN }

/** The single thing to do about the first broken link. */
enum class HealthFix(val label: String) {
    SIGN_IN("Sign in"),
    SIGN_IN_AGAIN("Sign in again"),
    RETRY("Check again"),
    NOTIFICATIONS("Allow notifications"),
    BATTERY("Allow background use"),
}

/**
 * The answer to "will Hermes reach my wrist, and when?", for the top of the setup screen. It
 * names the first broken link and one fix, rather than every status at once.
 */
data class Health(
    val hermes: LinkState,
    val phone: LinkState,
    val watch: LinkState,
    val headline: String,
    val detail: String,
    val fix: HealthFix?,
) {
    val ok: Boolean get() = fix == null

    companion object {
        /** Checks that have not run for this many intervals (plus a minute's grace) are late. */
        private const val LATE_INTERVALS = 3

        fun of(
            signedIn: Boolean,
            backend: BackendState,
            canNotify: Boolean,
            batteryExempt: Boolean,
            speed: ReplySpeed,
            /** The current check interval: faster while the watchapp is open. */
            intervalSeconds: Int,
            watchOpen: Boolean,
            lastCheckAt: Long,
            now: Long,
        ): Health {
            val watch = if (watchOpen) LinkState.OK else LinkState.UNKNOWN
            val checking = watchOpen || speed != ReplySpeed.WATCH_ONLY
            val last = if (lastCheckAt > 0) "Last reached Hermes ${ago(now - lastCheckAt)}" else "Not checked yet"
            val where = if (watchOpen) "watchapp open" else "watchapp closed"
            val detail = "$last · $where"

            fun problem(hermes: LinkState, phone: LinkState, headline: String, why: String, fix: HealthFix) =
                Health(hermes, phone, watch, headline, why, fix)

            if (!signedIn) return problem(LinkState.PROBLEM, LinkState.UNKNOWN,
                "Not connected to Hermes", "Sign in to your Hermes dashboard to start.", HealthFix.SIGN_IN)
            when (backend.link) {
                BackendLink.SIGN_IN_REQUIRED -> return problem(LinkState.PROBLEM, LinkState.OK,
                    "Hermes rejected the sign-in", "Sign out and sign in again.", HealthFix.SIGN_IN_AGAIN)
                BackendLink.OFFLINE -> return problem(LinkState.PROBLEM, LinkState.OK,
                    "Cannot reach Hermes", "Is Tailscale connected and the dashboard running? $last.", HealthFix.RETRY)
                BackendLink.ERROR -> return problem(LinkState.PROBLEM, LinkState.OK,
                    "Hermes reported an error", backend.detail.ifBlank { "Try again in a moment." }, HealthFix.RETRY)
                else -> Unit
            }
            val hermes = if (backend.link == BackendLink.CONNECTED) LinkState.OK else LinkState.UNKNOWN
            if (!canNotify) return problem(hermes, LinkState.PROBLEM,
                "Notifications are blocked", "Replies cannot reach the watch while Wristbound is closed on it.", HealthFix.NOTIFICATIONS)
            if (speed != ReplySpeed.WATCH_ONLY && !batteryExempt) return problem(hermes, LinkState.PROBLEM,
                "Android may hold replies back", "Battery is optimised, so checks can wait many minutes while the phone sleeps.", HealthFix.BATTERY)
            if (checking && lastCheckAt > 0 && now - lastCheckAt > (LATE_INTERVALS * intervalSeconds + 60) * 1000L) return problem(
                hermes, LinkState.PROBLEM, "Checks are running late", "$last, but it should check every ${ReplySpeed.within(intervalSeconds)}.", HealthFix.RETRY)

            val headline = when {
                speed == ReplySpeed.WATCH_ONLY && !watchOpen -> "Replies wait until you open Wristbound on the watch"
                else -> "All good. Replies reach your wrist within ${ReplySpeed.within(intervalSeconds)}."
            }
            return Health(hermes, LinkState.OK, watch, headline, detail, null)
        }

        /** "just now", "40 s ago", "3 min ago", "2 h ago", "3 d ago". */
        fun ago(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return when {
                s < 10 -> "just now"
                s < 60 -> "$s s ago"
                s < 3600 -> "${s / 60} min ago"
                s < 86_400 -> "${s / 3600} h ago"
                else -> "${s / 86_400} d ago"
            }
        }
    }
}
