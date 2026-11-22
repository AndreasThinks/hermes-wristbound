package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.Gateway
import dev.wristbound.companion.hermes.HermesHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** Whether Hermes can tell the phone about new replies right away. */
enum class Live {
    /** Not wanted: reply monitoring is off. */
    OFF,
    CONNECTING,
    /** The socket is open: a reply anywhere reaches the phone within seconds. */
    LIVE,
    /** The socket could not be opened or was lost; checks fall back to the chosen interval. */
    DOWN,
}

/**
 * Hears about new replies the moment Hermes writes them, instead of asking every minute.
 *
 * Hermes broadcasts `sessions.changed` on the gateway socket to every connected client whenever
 * any session's rows change: Telegram, cron, the desktop, anything. That is the same signal the
 * desktop app refreshes on. This keeps one quiet socket open (no JSON heartbeat, only rare
 * WebSocket pings), and on each change runs one check ([onChange]), so the phone sleeps while
 * Hermes is idle and wakes only when something happened.
 *
 * The socket is a hint, never the only path. While it is down the alarm checks keep their usual
 * pace; while it is up they slow to a safety net, and each one [ensure]s the socket is still
 * answering. A change that arrives in a burst (a busy turn rewrites its session every few
 * seconds) is collapsed into one check.
 */
class ChangeListener(
    private val scope: CoroutineScope,
    /** Open the listening socket. A [HermesHttpException] with [HermesHttpException.isAuth] stops retrying. */
    private val open: suspend () -> Gateway,
    /** Something changed in Hermes: check for replies. */
    private val onChange: suspend () -> Unit,
    /** Keep the CPU awake while [block] runs; the change arrived on a packet that woke the phone briefly. */
    private val awake: suspend (suspend () -> Unit) -> Unit = { it() },
    private val debounceMs: Long = DEBOUNCE_MS,
    private val retryMs: (attempt: Int) -> Long = ::backoff,
    /**
     * The least time between two checks started by changes. While Hermes is busy anywhere it
     * reports a change every couple of seconds; when nobody is waiting on the wrist (watchapp
     * closed, no turn of ours running) one check every [BUSY_GAP_MS] is plenty. 0 checks each burst.
     */
    private val minGapMs: () -> Long = { 0L },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(Live.OFF)
    val state: StateFlow<Live> = _state

    val live: Boolean get() = _state.value == Live.LIVE

    /** Called whenever [state] changes, so the check schedule can follow it. */
    var onStateChanged: (Live) -> Unit = {}

    @Volatile private var job: Job? = null
    @Volatile private var gateway: Gateway? = null
    @Volatile private var key: Any? = null
    private val changes = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastCheck = Long.MIN_VALUE / 2

    init {
        scope.launch {
            for (unit in changes) {
                awake {
                    // Let a burst settle, then check once for all of it. Held awake through the
                    // wait: a check put off past the last change of a turn must still run.
                    delay(debounceMs)
                    val wait = minGapMs() - (now() - lastCheck)
                    if (wait > 0) delay(wait)
                    changes.tryReceive()
                    lastCheck = now()
                    runCatching { onChange() }.onFailure { if (it is CancellationException) throw it }
                }
            }
        }
    }

    /**
     * Keep listening for [forKey] (the dashboard client in use). A different key, after a sign-in
     * or a new dashboard URL, reconnects; the same key leaves a running listener alone.
     */
    @Synchronized
    fun start(forKey: Any) {
        if (key === forKey && job?.isActive == true) return
        stopLocked()
        key = forKey
        set(Live.CONNECTING)
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        key = null
        set(Live.OFF)
    }

    /**
     * Retry now rather than at the end of a backoff (the network came back, or an alarm fired):
     * a coroutine delay does not run while the phone sleeps.
     */
    @Synchronized
    fun kick() {
        if (_state.value == Live.LIVE || _state.value == Live.CONNECTING && job?.isActive == true) return
        restart()
    }

    /** Drop whatever socket there is and connect again now. */
    @Synchronized
    private fun restart() {
        if (key == null) return
        stopLocked()
        set(Live.CONNECTING)
        job = scope.launch { run() }
    }

    /**
     * Make sure the socket still answers, reconnecting if not, for up to [timeoutMs]. Run from the
     * safety-net alarm: a socket that died without a close (a dropped tunnel, a phone that slept
     * through it) is otherwise only noticed by OkHttp's next ping. True when live afterwards.
     */
    suspend fun ensure(timeoutMs: Long = ENSURE_TIMEOUT_MS): Boolean {
        if (key == null) return false
        val g = gateway
        if (_state.value == Live.LIVE && g != null) {
            val ok = withTimeoutOrNull(timeoutMs) {
                runCatching { g.call("gateway.ping", JsonObject(emptyMap()), timeoutMs) }.isSuccess
            } ?: false
            if (ok) return true
            restart()
        } else kick()
        return withTimeoutOrNull(timeoutMs) { _state.first { it != Live.CONNECTING } } == Live.LIVE
    }

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive) {
            set(Live.CONNECTING)
            val g = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesHttpException) {
                set(Live.DOWN)
                // Signed out or refused: the alarm checks report it; a new sign-in restarts this.
                if (e.isAuth || e.status == 404) return
                delay(retryMs(attempt++))
                continue
            } catch (e: Exception) {
                set(Live.DOWN)
                delay(retryMs(attempt++))
                continue
            }
            gateway = g
            attempt = 0
            set(Live.LIVE)
            // Replies may have landed while the socket was down: catch up once.
            changes.trySend(Unit)
            try {
                for (event in g.events) if (event.type == SESSIONS_CHANGED) changes.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Closed: reconnect below.
            } finally {
                gateway = null
                runCatching { g.close() }
            }
            set(Live.DOWN)
            delay(retryMs(attempt++))
        }
    }

    private fun stopLocked() {
        job?.cancel()
        job = null
        gateway?.let { runCatching { it.close() } }
        gateway = null
    }

    private fun set(s: Live) {
        if (_state.value == s) return
        _state.value = s
        onStateChanged(s)
    }

    companion object {
        const val SESSIONS_CHANGED = "sessions.changed"
        /** Hermes sends at most one change every 2 s; a little longer collects a burst. */
        const val DEBOUNCE_MS = 2_500L
        const val ENSURE_TIMEOUT_MS = 8_000L
        /** Change-driven checks while nobody waits on the wrist: at most one per 20 s. */
        const val BUSY_GAP_MS = 20_000L

        /** 5 s, 15 s, 1 min, then every 5 min. The alarm checks also retry while down. */
        fun backoff(attempt: Int): Long = when (attempt) {
            0 -> 5_000L
            1 -> 15_000L
            2 -> 60_000L
            else -> 300_000L
        }
    }
}
