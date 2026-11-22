package dev.wristbound.companion.core

/**
 * Keeps the watch's Quick Launch choice in step with the companion, which is where it is chosen.
 *
 * The phone holds two values: what was chosen here ([phone], null until a choice is made) and
 * what the watch last confirmed it holds ([watch]). The choice counts as applied only when the
 * watch acknowledged it, so a saved phone value is never shown as proof that it reached the watch.
 */
object QuickLaunchSync {
    enum class Status {
        /** Nothing chosen on the phone yet: the watch keeps its own earlier choice. */
        WATCH_OWN,
        /** The watch acknowledged this choice. */
        APPLIED,
        /** Saved on the phone; the watch gets it the next time Wristbound opens there. */
        PENDING,
    }

    fun status(phone: Int?, watch: Int?): Status = when {
        phone == null -> Status.WATCH_OWN
        phone == watch -> Status.APPLIED
        else -> Status.PENDING
    }

    /** What to do when the watchapp says hello, reporting its saved choice [fromWatch]. */
    data class Hello(
        /** Adopt this as the phone's choice (an upgrade from the watch-side setting). */
        val adopt: Int?,
        /** Send this to the watch and wait for its acknowledgement. */
        val send: Int?,
        /** The watch is known to hold this already. */
        val confirmed: Int?,
    )

    fun onHello(phone: Int?, fromWatch: Int?): Hello = when {
        // Upgrade: the phone has never chosen, so the watch's choice stands and becomes the phone's.
        phone == null -> Hello(adopt = fromWatch, send = null, confirmed = fromWatch)
        // Already there: nothing to send.
        phone == fromWatch -> Hello(adopt = null, send = null, confirmed = phone)
        // The phone's choice wins; it counts as applied once the watch acknowledges it.
        else -> Hello(adopt = null, send = phone, confirmed = null)
    }
}
