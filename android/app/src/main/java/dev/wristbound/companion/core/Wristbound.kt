package dev.wristbound.companion.core

import android.content.Context
import android.util.Log
import dev.wristbound.companion.hermes.AudioClip
import dev.wristbound.companion.hermes.DashboardAuth
import dev.wristbound.companion.hermes.DashboardClient
import dev.wristbound.companion.hermes.Endpoint
import dev.wristbound.companion.hermes.HermesApi
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesProtocolException
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.notify.Notifier
import dev.wristbound.companion.pebble.Protocol
import dev.wristbound.companion.pebble.Text
import dev.wristbound.companion.pebble.WatchCodec
import dev.wristbound.companion.pebble.WatchCommand
import dev.wristbound.companion.pebble.WatchLink
import dev.wristbound.companion.playback.PlaybackController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The companion's single coordinator: answers watch commands, runs turns against Hermes,
 * delivers replies (to the open watchapp, or as a phone notification the Pebble mirrors),
 * and controls playback of one exact reply.
 */
class Wristbound private constructor(context: Context) {
    private val app = context.applicationContext
    val settings = Settings(app)
    val state = LocalState(app)
    /**
     * Every watch command, turn and poll runs here. Expected failures are handled where they
     * happen; this handler only stops an unexpected one from taking the whole companion down.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unhandled error in companion work", e)
    })
    private val watch = WatchLink(app)
    private val notifier = Notifier(app)
    private val playback = PlaybackController(app) { id, s, detail -> onPlaybackState(id, s, detail) }

    @Volatile var watchOpen = false
        private set
    @Volatile private var textBudget = Protocol.DEFAULT_TEXT_BUDGET
    @Volatile private var lastAllowSpeaker = false
    @Volatile private var pendingReply: Pair<String, String>? = null
    @Volatile private var lastSend: Triple<String, String, Long>? = null

    private val activeTurns = ConcurrentHashMap<String, Job>()
    private val sessionsById = ConcurrentHashMap<String, HermesSession>()
    /** Full reply text and Hermes audio paths by message id, for playback. Bounded. */
    private val replies = object : LinkedHashMap<String, Reply>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Reply>?) = size > 64
    }
    private val pollMutex = Mutex()
    private var monitor: Job? = null
    private val alerts = ReplyAlerts(state, ::alertPolicy) { deliverAlert(it) }
    private val audioDir = File(app.cacheDir, "audio").apply { mkdirs() }

    private data class Reply(val text: String, val audio: List<String>)

    @Volatile private var client: DashboardClient? = null

    /** The dashboard client for the active URL; rebuilt when the URL changes. */
    fun client(): DashboardClient? {
        val url = settings.baseUrl
        if (url.isBlank()) return null
        client?.let { if (Endpoint.same(it.auth.base.toString(), url)) return it }
        return runCatching { DashboardClient(DashboardAuth(url, settings.credentialsFor(url))) }
            .onFailure { Log.w(TAG, "active dashboard URL is unusable: ${it.javaClass.simpleName}") }
            .getOrNull().also { client = it }
    }

    private fun api(): DashboardClient? = if (settings.isConfigured) client() else null

    // -- backend state --------------------------------------------------------------------------

    private val _backend = MutableStateFlow(BackendState(BackendLink.UNKNOWN))

    /** How the last request to Hermes went: separate from whether credentials are saved. */
    val backend: StateFlow<BackendState> = _backend

    private fun recordBackend(error: Throwable?) {
        _backend.value = when (error) {
            null -> BackendState(BackendLink.CONNECTED)
            is HermesHttpException -> if (error.isAuth) BackendState(BackendLink.SIGN_IN_REQUIRED, "Hermes rejected the sign-in")
                else BackendState(BackendLink.ERROR, "Hermes error: ${error.message}")
            is HermesProtocolException -> BackendState(BackendLink.ERROR, error.message.orEmpty())
            else -> BackendState(BackendLink.OFFLINE, error.message.orEmpty())
        }
    }

    /**
     * Check the active dashboard end to end: the session list and the gateway socket that
     * sends replies. Returns the number of recent sessions and how many are from Telegram.
     */
    suspend fun checkConnection(): Pair<Int, Int> {
        val client = api() ?: throw HermesHttpException(401, "signed_out", "Sign in to Hermes")
        _backend.value = BackendState(BackendLink.CHECKING)
        try {
            val sessions = client.listSessions(20)
            client.openGateway().close()
            recordBackend(null)
            return sessions.size to sessions.count { it.isTelegram }
        } catch (e: IOException) {
            recordBackend(e)
            throw e
        }
    }

    /** Sign-in or sign-out changed the credentials or the active dashboard. */
    fun onCredentialsChanged() {
        client = null
        _backend.value = BackendState(if (settings.isConfigured) BackendLink.UNKNOWN else BackendLink.SIGNED_OUT)
        updateMonitoring()
        if (watchOpen) scope.launch { pushSessions() }
    }

    // -- watch lifecycle ------------------------------------------------------------------------

    fun onWatchAppOpened() = setWatchOpen(true)

    fun onWatchAppClosed() = setWatchOpen(false)

    private fun setWatchOpen(open: Boolean) {
        if (watchOpen == open) return
        watchOpen = open
        Log.i(TAG, if (open) "watchapp open" else "watchapp closed")
        updateMonitoring()
    }

    suspend fun onWatchCommand(cmd: WatchCommand) {
        // Any command proves the watchapp is open, even if the open event was missed.
        setWatchOpen(true)
        when (cmd) {
            is WatchCommand.Hello -> {
                textBudget = cmd.textBudget.coerceIn(64, 4000)
                settings.includeTelegram = cmd.includeTelegram
                if (cmd.protoVersion != Protocol.VERSION) {
                    watch.send(WatchCodec.status(Protocol.Conn.ERROR, "Update watch or phone app"))
                }
                pushSessions()
                pendingReply?.let { (sid, mid) ->
                    pendingReply = null
                    sendReplyToWatch(sid, mid)
                }
            }
            is WatchCommand.ListSessions -> {
                settings.includeTelegram = cmd.includeTelegram
                pushSessions()
            }
            is WatchCommand.OpenSession -> openSession(cmd.sessionId)
            is WatchCommand.Send -> submit(cmd.sessionId, cmd.text)
            is WatchCommand.NewSession -> newSession(cmd.text)
            is WatchCommand.Play -> play(cmd.sessionId, cmd.messageId, cmd.speaker)
            is WatchCommand.Replay -> play(cmd.sessionId, cmd.messageId, lastAllowSpeaker)
            WatchCommand.Pause -> playback.pause()
            WatchCommand.Resume -> playback.resume(lastAllowSpeaker)
            WatchCommand.Stop -> playback.stop()
        }
    }

    // -- sessions & history ---------------------------------------------------------------------

    private suspend fun fetchSessions(api: HermesApi): List<HermesSession> {
        val sessions = try {
            api.listSessions(100).also { recordBackend(null) }
        } catch (e: IOException) {
            recordBackend(e)
            throw e
        }
        sessions.forEach { sessionsById[it.id] = it }
        state.baseline(sessions.associate { it.id to it.activity })
        return sessions
    }

    suspend fun pushSessions() {
        val api = api()
        if (api == null) {
            watch.send(WatchCodec.status(Protocol.Conn.NOT_CONFIGURED, "Set up phone app"))
            sendSessionPages(emptyList())
            return
        }
        try {
            val visible = SessionMapper.visibleSessions(
                fetchSessions(api), settings.includeTelegram, state.lastSeen(), state.readOnly(), activeTurns.keys,
                settings.allowTelegramReplies,
            )
            state.saveSessions(visible)
            watch.send(WatchCodec.status(Protocol.Conn.OK, ""))
            sendSessionPages(visible)
        } catch (e: HermesHttpException) {
            watch.send(WatchCodec.status(if (e.isAuth) Protocol.Conn.AUTH_ERROR else Protocol.Conn.ERROR, e.message.orEmpty()))
            sendSessionPages(state.loadSessions()?.sessions.orEmpty())
        } catch (e: HermesProtocolException) {
            Log.w(TAG, "unreadable session list", e)
            watch.send(WatchCodec.status(Protocol.Conn.ERROR, "Hermes sent bad data"))
            sendSessionPages(state.loadSessions()?.sessions.orEmpty())
        } catch (e: IOException) {
            val cached = state.loadSessions()
            watch.send(WatchCodec.status(Protocol.Conn.OFFLINE, if (cached != null) "Offline - cached" else "Hermes offline"))
            sendSessionPages(cached?.sessions.orEmpty())
        }
    }

    private suspend fun sendSessionPages(sessions: List<WatchSession>) {
        val pages = if (sessions.isEmpty()) listOf(WatchCodec.sessionPage(0, 0, null))
        else sessions.mapIndexed { i, s -> WatchCodec.sessionPage(i, sessions.size, s) }
        watch.sendAll(pages)
    }

    private suspend fun openSession(sessionId: String) {
        state.openSessionId = sessionId
        sessionsById[sessionId]?.let { state.markSeen(sessionId, it.activity) }
        pushMessages(sessionId)
    }

    private suspend fun pushMessages(sessionId: String) {
        val api = api()
        val title = sessionsById[sessionId]?.displayTitle.orEmpty()
        val messages: List<WatchMessage> = try {
            if (api == null) throw IOException("not configured")
            val raw = api.recentMessages(sessionId, 40)
            rememberReplies(raw)
            SessionMapper.watchMessages(raw, settings.toolActivity).also { state.saveMessages(sessionId, title, it) }
        } catch (e: HermesProtocolException) {
            Log.w(TAG, "unreadable history", e)
            watch.send(WatchCodec.status(Protocol.Conn.ERROR, "Hermes sent bad data"))
            state.loadMessages(sessionId)?.messages.orEmpty()
        } catch (e: IOException) {
            watch.send(WatchCodec.status(Protocol.Conn.OFFLINE, "Offline - cached"))
            state.loadMessages(sessionId)?.messages.orEmpty()
        }
        val pages = if (messages.isEmpty()) listOf(WatchCodec.messagePage(sessionId, 0, 0, null, textBudget))
        else messages.mapIndexed { i, m -> WatchCodec.messagePage(sessionId, i, messages.size, m, textBudget) }
        watch.sendAll(pages)
    }

    private fun rememberReplies(raw: List<HermesMessage>) = synchronized(replies) {
        SessionMapper.replies(raw).forEach { r -> replies[r.id] = Reply(Text.forWatch(r.message.text), r.audio) }
    }

    // -- submissions ----------------------------------------------------------------------------

    /** The watch retries an AppMessage whose ack was lost, so the same command can arrive twice. */
    @Synchronized
    private fun isDuplicate(target: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = lastSend
        if (previous != null && previous.first == target && previous.second == text && now - previous.third < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "ignoring duplicate command for $target")
            return true
        }
        lastSend = Triple(target, text, now)
        return false
    }

    private suspend fun newSession(firstMessage: String) {
        if (api() == null) return submitState(null, Protocol.Submit.FAILED, "Set up phone app")
        if (isDuplicate(NEW_SESSION_TARGET, firstMessage)) return
        startTurn(null, firstMessage)
    }

    private suspend fun submit(sessionId: String, text: String) {
        val api = api() ?: return submitState(sessionId, Protocol.Submit.FAILED, "Set up phone app")
        if (!settings.allowTelegramReplies) {
            // Enforced here, not only on the watch: an unknown session is looked up first and
            // refused if it still cannot be checked.
            if (sessionsById[sessionId] == null) runCatching { fetchSessions(api) }
            SessionMapper.replyRefusal(sessionsById[sessionId], telegramReplies = false)?.let {
                return submitState(sessionId, Protocol.Submit.READ_ONLY, it)
            }
        }
        if (isDuplicate(sessionId, text)) return
        if (activeTurns.containsKey(sessionId)) {
            return submitState(sessionId, Protocol.Submit.FAILED, "Hermes still working here")
        }
        startTurn(sessionId, text)
    }

    /** Run one turn in [existing], or in a new session when it is null. */
    private fun startTurn(existing: String?, text: String) {
        val api = api() ?: return
        val key = existing ?: NEW_SESSION_TARGET
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var sessionId = existing
            val title = existing?.let { sessionsById[it]?.displayTitle } ?: Text.forWatch(text.lineSequence().first().take(40))
            val tool = settings.toolActivity
            val outcome = TurnRunner(api).run(
                existing, text,
                onProgress = { s, d -> submitState(sessionId, s, d) },
                onTool = { name ->
                    if (tool != ToolActivity.HIDDEN) submitState(sessionId, Protocol.Submit.WORKING, "Using $name")
                    if (tool == ToolActivity.NOTIFY && !watchOpen) notifier.toolActivity(title, name)
                },
                onCreated = { id ->
                    sessionId = id
                    sessionsById[id] = HermesSession(id, source = "webui", title = title)
                    state.openSessionId = id
                    currentCoroutineContext()[Job]?.let { activeTurns[id] = it }
                    watch.send(WatchCodec.sessionOpened(WatchSession(id, title, 0)))
                },
            )
            notifier.clearToolActivity()
            val sid = sessionId
            when (outcome) {
                is TurnOutcome.Completed -> submitState(sid, Protocol.Submit.COMPLETED, "Reply received")
                is TurnOutcome.Failed -> problem(sid, title, Protocol.Submit.FAILED, outcome.detail)
                TurnOutcome.Cancelled -> submitState(sid, Protocol.Submit.CANCELLED, "Cancelled")
                is TurnOutcome.ReadOnly -> {
                    sid?.let { state.markReadOnly(it) }
                    problem(sid, title, Protocol.Submit.READ_ONLY, outcome.detail.take(60))
                }
                is TurnOutcome.Uncertain -> problem(sid, title, Protocol.Submit.UNCERTAIN, outcome.detail)
                TurnOutcome.AuthFailed -> problem(sid, title, Protocol.Submit.FAILED, "Sign in to Hermes again")
            }
            // Read back the authoritative transcript: it carries the real message ids.
            if (sid != null && (outcome is TurnOutcome.Completed || outcome is TurnOutcome.Uncertain)) {
                deliverLatestReply(api, sid, title)
                if (watchOpen && state.openSessionId == sid) pushMessages(sid)
            }
        }
        job.invokeOnCompletion { activeTurns.values.removeAll { it === job } }
        activeTurns[key] = job
        job.start()
    }

    private suspend fun problem(sessionId: String?, title: String, s: Protocol.Submit, detail: String) {
        submitState(sessionId, s, detail)
        if (!watchOpen) notifier.problem(title, detail)
    }

    private suspend fun submitState(sessionId: String?, s: Protocol.Submit, detail: String) {
        if (watchOpen) watch.send(WatchCodec.submit(sessionId, s, detail))
    }

    /** The reply to a turn sent from the watch, through the same alert policy as polling. */
    private suspend fun deliverLatestReply(api: HermesApi, sessionId: String, title: String) {
        val raw = runCatching { api.recentMessages(sessionId, 20) }.getOrNull() ?: return
        rememberReplies(raw)
        val session = sessionsById[sessionId] ?: HermesSession(sessionId, title = title)
        alerts.turnFinished(session, raw)
    }

    private fun alertPolicy() = AlertPolicy(
        replies = settings.notifyReplies,
        scope = settings.notifyScope,
        telegramText = settings.notifyTelegram,
        telegramAudio = settings.notifyTelegramAudio,
        openSessionId = state.openSessionId,
    )

    /**
     * Show one decided alert: on the open watchapp, otherwise as a phone notification (which
     * the Pebble mirrors). Returns whether it reached the user: the watch acknowledged the card,
     * or a notification was posted. A watch that does not acknowledge is treated as closed, so
     * a missed close event cannot swallow alerts, and the phone notification is used instead.
     */
    private suspend fun deliverAlert(alert: ReplyAlert): Boolean {
        val sessionId = alert.sessionId
        if (alert.silent) {
            // Audio arrived for a reply that already alerted. The watch's message list picks it
            // up on its next refresh; a notification is updated without alerting again.
            if (!watchOpen) notifier.reply(sessionId, alert.messageId, alert.title, Text.forWatch(alert.text), settings.privatePreviews, alert.hasAudio, true)
            return true
        }
        if (watchOpen) {
            if (sendReplyToWatch(sessionId, alert.messageId, alert.text, alert.hasAudio)) {
                Log.i(TAG, "alert ${alert.messageId}: shown on watch")
                sessionsById[sessionId]?.let { if (state.openSessionId == sessionId) state.markSeen(sessionId, it.activity) }
                return true
            }
            Log.w(TAG, "alert ${alert.messageId}: watch did not acknowledge; treating the watchapp as closed")
            setWatchOpen(false)
        }
        val posted = notifier.reply(sessionId, alert.messageId, alert.title, Text.forWatch(alert.text), settings.privatePreviews, alert.hasAudio, false)
        Log.i(TAG, "alert ${alert.messageId}: " + if (posted) "phone notification posted" else "not delivered (notifications blocked)")
        return posted
    }

    /** Show one reply as a card on the watch. True when the watch acknowledged it. */
    private suspend fun sendReplyToWatch(sessionId: String, messageId: String, fallbackText: String? = null, hasAudio: Boolean? = null): Boolean {
        val reply = replyFor(sessionId, messageId)
        val text = reply?.text ?: fallbackText?.let(Text::forWatch) ?: return false
        val audio = hasAudio ?: reply?.audio?.isNotEmpty() ?: false
        val title = sessionsById[sessionId]?.displayTitle ?: "Hermes"
        val flags = if (audio) Protocol.MessageFlag.HAS_AUDIO else 0
        return watch.send(WatchCodec.reply(sessionId, title, WatchMessage(messageId, Protocol.Role.ASSISTANT, flags, text), textBudget))
    }

    /** Notification action "Read on watch": open the watchapp, then show this reply once it says hello. */
    suspend fun openReplyOnWatch(sessionId: String, messageId: String) {
        if (watchOpen && sendReplyToWatch(sessionId, messageId)) return
        pendingReply = sessionId to messageId
        state.openSessionId = sessionId
        watch.startApp()
    }

    suspend fun openWatchApp(): Boolean = watch.startApp()

    // -- reply monitoring -----------------------------------------------------------------------

    /**
     * Replies from other surfaces (Telegram, Ringbearer, desktop) are found by polling. One loop
     * runs while the watchapp is open, and also while it is closed when background sync is on
     * (the foreground service keeps the process alive for that). Every transition goes through
     * here, so there is never more than one loop.
     */
    @Synchronized
    fun updateMonitoring() {
        val wanted = settings.isConfigured && (watchOpen || settings.backgroundSync)
        if (wanted && monitor?.isActive != true) {
            Log.i(TAG, "reply monitoring on")
            monitor = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(pollIntervalSeconds() * 1000L)
                }
            }
        } else if (!wanted && monitor != null) {
            Log.i(TAG, "reply monitoring off")
            monitor?.cancel()
            monitor = null
        }
    }

    /** How often replies are checked right now: faster while the watchapp is open. */
    fun pollIntervalSeconds(): Int =
        if (watchOpen) minOf(settings.pollSeconds, FOREGROUND_POLL_SECONDS) else settings.pollSeconds

    /** What reply monitoring is doing, for the setup screen. */
    fun monitoringSummary(): String = when {
        !settings.isConfigured -> "Off until you sign in."
        watchOpen -> "Checking every ${pollIntervalSeconds()} s while the watchapp is open."
        settings.backgroundSync -> "Checking every ${settings.pollSeconds} s in the background."
        else -> "Paused: checks run while the watchapp is open, or always with background checking on."
    }

    /**
     * One background check for replies that arrived through other paths (Telegram, Ringbearer,
     * desktop) or after the phone lost a stream. See [ReplyAlerts.poll] for the checkpoints.
     */
    suspend fun pollOnce() = pollMutex.withLock {
        val api = api() ?: return@withLock
        val sessions = try {
            fetchSessions(api)
        } catch (e: IOException) {
            Log.d(TAG, "poll failed: ${e.message}")
            return@withLock
        }
        val moved = alerts.poll(sessions, busy = { activeTurns.containsKey(it) }) { id ->
            api.recentMessages(id, 20).also { rememberReplies(it) }
        }
        if (moved.isNotEmpty() && watchOpen) {
            pushSessions()
            state.openSessionId?.let { open -> if (open in moved) pushMessages(open) }
        }
    }

    // -- playback -------------------------------------------------------------------------------

    /**
     * Play one exact reply. In order: audio Hermes attached to it (its text-to-speech tool
     * output), then the reply text spoken again in Hermes' configured voice (newly generated,
     * not the voice note Telegram may have received), then the phone's voice.
     */
    suspend fun play(sessionId: String, messageId: String, allowSpeaker: Boolean) {
        lastAllowSpeaker = allowSpeaker
        val reply = replyFor(sessionId, messageId)
        if (reply == null) {
            onPlaybackState(messageId, Protocol.Playback.ERROR, "Reply not found")
            return
        }
        if (!allowSpeaker && !playback.hasPrivateOutput()) {
            onPlaybackState(messageId, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        val api = api()
        var clip: Pair<AudioClip, String>? = null
        if (api != null && reply.audio.isNotEmpty()) {
            onPlaybackState(messageId, Protocol.Playback.IDLE, "Getting Hermes audio")
            clip = reply.audio.firstNotNullOfOrNull { path ->
                runCatching { api.fetchAudio(path, audioDir) }.onFailure { Log.d(TAG, "audio fetch failed: ${it.message}") }.getOrNull()
            }?.let { it to "Playing Hermes audio" }
        }
        if (clip == null && api != null && settings.hermesVoice) {
            onPlaybackState(messageId, Protocol.Playback.IDLE, "Getting Hermes voice")
            clip = runCatching { api.speak(Text.forSpeech(reply.text), messageId, audioDir) }
                .onFailure { Log.d(TAG, "Hermes voice failed: ${it.message}") }.getOrNull()
                ?.let { it to "Hermes voice, read again" }
        }
        pruneAudio()
        if (clip != null) playback.playFile(messageId, clip.first.file, allowSpeaker, clip.second)
        else playback.play(messageId, Text.forSpeech(reply.text), allowSpeaker)
    }

    private suspend fun replyFor(sessionId: String, messageId: String): Reply? {
        synchronized(replies) { replies[messageId] }?.let { return it }
        val raw = runCatching { api()?.recentMessages(sessionId, 40) }.getOrNull() ?: return null
        rememberReplies(raw)
        return synchronized(replies) { replies[messageId] }
    }

    /** Keep the audio cache small: the newest files only. */
    private fun pruneAudio() {
        val files = audioDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_AUDIO_FILES).forEach { it.delete() }
    }

    private fun onPlaybackState(messageId: String?, s: Protocol.Playback, detail: String) {
        if (watchOpen) scope.launch { watch.send(WatchCodec.playback(messageId, s, detail)) }
    }

    companion object {
        private const val TAG = "Wristbound"
        private const val DUPLICATE_WINDOW_MS = 15_000L
        private const val NEW_SESSION_TARGET = "\u0000new"
        private const val MAX_AUDIO_FILES = 24
        const val FOREGROUND_POLL_SECONDS = 30

        @Volatile private var instance: Wristbound? = null

        fun get(context: Context): Wristbound =
            instance ?: synchronized(this) { instance ?: Wristbound(context).also { instance = it } }
    }
}
