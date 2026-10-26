package dev.wristbound.companion.core

/**
 * Why the companion must keep running while the watchapp is closed. The Pebble app only binds
 * the companion while the watchapp is open; after that Android freezes a cached process within
 * seconds, so anything still in flight needs the foreground service.
 */
data class KeepAlive(
    /** Background checking is on and there is a Hermes account to check. */
    val checking: Boolean,
    /** Turns sent from the watch that have not finished (approvals only happen inside one). */
    val turns: Int,
    /** A reply is playing or paused on the phone. */
    val playing: Boolean,
) {
    val needed: Boolean get() = checking || turns > 0 || playing

    /** The service notification's text: the most pressing reason first. */
    val label: String get() = when {
        playing -> "Playing a Hermes reply"
        turns > 0 -> "Waiting for Hermes to reply"
        else -> "Watching Hermes for replies"
    }
}

/** How replies from other surfaces are checked for right now. */
enum class Monitor {
    OFF,
    /** The watchapp is open: the Pebble app's binding keeps the process awake, so a timer loop works. */
    LOOP,
    /**
     * Background checking with the watchapp closed: alarms wake the phone for each check, since
     * a coroutine `delay()` stops counting while the CPU sleeps.
     */
    ALARMS;

    companion object {
        fun of(configured: Boolean, watchOpen: Boolean, backgroundSync: Boolean): Monitor = when {
            !configured -> OFF
            watchOpen -> LOOP
            backgroundSync -> ALARMS
            else -> OFF
        }
    }
}
