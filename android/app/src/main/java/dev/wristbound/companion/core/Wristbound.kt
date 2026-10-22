package dev.wristbound.companion.core

import android.content.Context
import android.util.Log
import dev.wristbound.companion.hermes.AudioClip
import dev.wristbound.companion.hermes.DashboardAuth
import dev.wristbound.companion.hermes.DashboardClient
import dev.wristbound.companion.hermes.HermesApi
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.notify.Notifier
import dev.wristbound.companion.pebble.Protocol
import dev.wristbound.companion.pebble.Text
import dev.wristbound.companion.pebble.WatchCodec
import dev.wristbound.companion.pebble.WatchCommand
import dev.wristbound.companion.pebble.WatchLink
import dev.wristbound.companion.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    private var lastPolled: MutableMap<String, Double>? = null
    private val audioDir = File(app.cacheDir, "audio").apply { mkdirs() }

    private data class Reply(val text: String, val audio: List<String>)

    @Volatile private var client: DashboardClient? = null

    /** The dashboard client for the configured URL; rebuilt when the URL changes. */
    fun client(): DashboardClient? {
        val url = settings.baseUrl
        if (url.isBlank()) return null
        client?.let { if (it.auth.base.toString().trimEnd('/') == url.trimEnd('/')) return it }
        return runCatching { DashboardClient(DashboardAuth(url, settings)) }.getOrNull().also { client = it }
    }

    private fun api(): DashboardClient? = if (settings.isConfigured) client() else null

    // -- watch lifecycle ------------------------------------------------------------------------

    fun onWatchAppOpened() {
        watchOpen = true
    }

    fun onWatchAppClosed() {
        watchOpen = false
    }

    suspend fun onWatchCommand(cmd: WatchCommand) {
        when (cmd) {
            is WatchCommand.Hello -> {
                watchOpen = true
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
        val sessions = api.listSessions(100)
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
            )
            state.saveSessions(visible)
            watch.send(WatchCodec.status(Protocol.Conn.OK, ""))
            sendSessionPages(visible)
        } catch (e: HermesHttpException) {
            watch.send(WatchCodec.status(if (e.isAuth) Protocol.Conn.AUTH_ERROR else Protocol.Conn.ERROR, e.message.orEmpty()))
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
        } catch (e: IOException) {
            watch.send(WatchCodec.status(Protocol.Conn.OFFLINE, "Offline - cached"))
            state.loadMessages(sessionId)?.messages.orEmpty()
        }
        val pages = if (messages.isEmpty()) listOf(WatchCodec.messagePage(sessionId, 0, 0, null, textBudget))
        else messages.mapIndexed { i, m -> WatchCodec.messagePage(sessionId, i, messages.size, m, textBudget) }
        watch.sendAll(pages)
    }

    private fun rememberReplies(raw: List<HermesMessage>) = synchronized(replies) {
        raw.forEachIndexed { i, m ->
            if (m.isFinalAssistantText) replies[m.stableId] = Reply(Text.forWatch(m.text), SessionMapper.audioFor(raw, i))
        }
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
        if (api() == null) return submitState(sessionId, Protocol.Submit.FAILED, "Set up phone app")
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

    private suspend fun deliverLatestReply(api: HermesApi, sessionId: String, title: String) {
        val raw = runCatching { api.recentMessages(sessionId, 10) }.getOrNull() ?: return
        rememberReplies(raw)
        val latest = raw.lastOrNull { it.isFinalAssistantText } ?: return
        // Only the reply to this turn: it must come after the last user message.
        val lastUser = raw.indexOfLast { it.role == "user" }
        if (raw.indexOf(latest) < lastUser) return
        deliverReply(sessionId, title, latest)
    }

    /** Deliver one reply exactly once: to the open watchapp, otherwise as a phone notification. */
    private suspend fun deliverReply(sessionId: String, title: String, message: HermesMessage) {
        if (!state.markNotified(message.stableId)) return
        sessionsById[sessionId]?.let { if (state.openSessionId == sessionId && watchOpen) state.markSeen(sessionId, it.activity) }
        if (watchOpen) {
            sendReplyToWatch(sessionId, message.stableId)
        } else if (settings.notifyReplies) {
            notifier.reply(sessionId, message.stableId, title, Text.forWatch(message.text), settings.privatePreviews)
        }
    }

    private suspend fun sendReplyToWatch(sessionId: String, messageId: String) {
        val text = textFor(sessionId, messageId) ?: return
        val title = sessionsById[sessionId]?.displayTitle ?: "Hermes"
        watch.send(WatchCodec.reply(sessionId, title, WatchMessage(messageId, Protocol.Role.ASSISTANT, 0, text), textBudget))
    }

    /** Notification action "Read on watch": open the watchapp, then show this reply once it says hello. */
    suspend fun openReplyOnWatch(sessionId: String, messageId: String) {
        if (watchOpen) {
            sendReplyToWatch(sessionId, messageId)
            return
        }
        pendingReply = sessionId to messageId
        state.openSessionId = sessionId
        watch.startApp()
    }

    suspend fun openWatchApp(): Boolean = watch.startApp()

    // -- background polling ---------------------------------------------------------------------

    /**
     * One background check for replies that arrived through other paths (Telegram, Ringbearer,
     * desktop) or after the phone lost a stream. The first poll only records a baseline, so
     * enabling sync does not replay old history as notifications.
     */
    suspend fun pollOnce() = pollMutex.withLock {
        val api = api() ?: return@withLock
        val sessions = try {
            fetchSessions(api)
        } catch (e: IOException) {
            Log.d(TAG, "poll failed: ${e.message}")
            return@withLock
        }
        val previous = lastPolled
        val current = sessions.associate { it.id to it.activity }.toMutableMap()
        lastPolled = current
        if (previous == null) return@withLock

        var changed = false
        for (session in sessions) {
            val before = previous[session.id]
            if (before != null && session.activity <= before + 0.001) continue
            changed = true
            if (activeTurns.containsKey(session.id)) continue // that turn delivers its own reply
            val eligible = SessionMapper.shouldNotify(session, settings.notifyScope, state.openSessionId, settings.notifyTelegram)
            val raw = runCatching { api.recentMessages(session.id, 6) }.getOrNull() ?: continue
            rememberReplies(raw)
            val fresh = SessionMapper.newReplies(raw, state.notified())
                .filter { (it.timestamp ?: Double.MAX_VALUE) > (before ?: 0.0) }
            val latest = fresh.lastOrNull()
            if (!eligible) {
                // Visible but not alerting: mark as handled so it never alerts later.
                fresh.forEach { state.markNotified(it.stableId) }
                continue
            }
            if (latest != null) {
                fresh.dropLast(1).forEach { state.markNotified(it.stableId) }
                deliverReply(session.id, session.displayTitle, latest)
            }
        }
        if (changed && watchOpen) {
            pushSessions()
            state.openSessionId?.let { open -> if (previous[open] != current[open]) pushMessages(open) }
        }
    }

    // -- playback -------------------------------------------------------------------------------

    /**
     * Play one exact reply. In order: audio Hermes attached to it (its text-to-speech tool
     * output), then the reply spoken in Hermes' own configured voice, then the phone's voice.
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
                ?.let { it to "Playing Hermes voice" }
        }
        pruneAudio()
        if (clip != null) playback.playFile(messageId, clip.first.file, allowSpeaker, clip.second)
        else playback.play(messageId, Text.forSpeech(reply.text), allowSpeaker)
    }

    private suspend fun textFor(sessionId: String, messageId: String): String? = replyFor(sessionId, messageId)?.text

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

        @Volatile private var instance: Wristbound? = null

        fun get(context: Context): Wristbound =
            instance ?: synchronized(this) { instance ?: Wristbound(context).also { instance = it } }
    }
}
