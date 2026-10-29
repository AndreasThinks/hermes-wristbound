package dev.wristbound.companion.core

import android.content.Context
import android.util.Log
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
import dev.wristbound.companion.pebble.WatchStream
import dev.wristbound.companion.playback.AudioDecoder
import dev.wristbound.companion.playback.PcmConverter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.wristbound.companion.playback.PlaybackController
import dev.wristbound.companion.sync.CheckAlarm
import dev.wristbound.companion.sync.SyncService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
    /** The open watchapp answers approvals itself (HELLO); otherwise they go to a notification. */
    @Volatile private var watchApproves = false

    /** An approval Hermes is waiting on, in a turn this phone runs. */
    private class OpenApproval(val sessionId: String, val title: String, val request: ApprovalRequest, val answer: (String) -> Boolean)
    private val approvals = ConcurrentHashMap<String, OpenApproval>()
    @Volatile private var pendingReply: Pair<String, String>? = null
    @Volatile private var lastSend: Triple<String, String, Long>? = null

    private val activeTurns = ConcurrentHashMap<String, Job>()
    private val sessionsById = ConcurrentHashMap<String, HermesSession>()
    /** Full reply text and Hermes audio paths by message id, for playback. Bounded. */
    private val replies = object : LinkedHashMap<String, Reply>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Reply>?) = size > 128
    }
    private val inboxMutex = Mutex()
    private val watchStream = WatchStream(scope, { watch.send(it) }) { id, reason -> Log.i(TAG, "watch speaker $id: $reason") }
    /** Watch-speaker PCM for the last few replies, so Replay does not decode again. */
    private val watchPcm = object : LinkedHashMap<String, Pair<ByteArray, String>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ByteArray, String>>?) = size > 2
    }
    private val pollMutex = Mutex()
    private var mode = Monitor.OFF
    /** A reply is playing or paused on the phone, which keeps the service up. */
    @Volatile private var playing = false
    private val alerts = ReplyAlerts(state, ::alertPolicy) { deliverAlert(it) }
    private val audioDir = File(app.cacheDir, "audio").apply { mkdirs() }

    private data class Reply(val text: String, val audio: List<String>)

    init {
        // The watch offers "Play on headphones" only while there are some, so keep it told.
        playback.watchHeadphones { connected ->
            if (watchOpen) scope.launch { watch.send(WatchCodec.audioRoute(connected)) }
        }
    }

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
        if (!open) scope.launch { watchStream.stop() }
        updateMonitoring()
    }

    suspend fun onWatchCommand(cmd: WatchCommand) {
        // Any command proves the watchapp is open, even if the open event was missed.
        setWatchOpen(true)
        when (cmd) {
            is WatchCommand.Hello -> {
                textBudget = cmd.textBudget.coerceIn(64, 4000)
                watchApproves = cmd.approvals
                if (cmd.protoVersion != Protocol.VERSION) {
                    watch.send(WatchCodec.status(Protocol.Conn.ERROR, "Update watch or phone app"))
                }
                // Watch options live here now; the watch's earlier choice stands until one is made.
                val quick = settings.quickLaunch
                if (quick == null) cmd.quickLaunch?.let { settings.quickLaunch = it }
                else watch.send(WatchCodec.config(quick))
                watch.send(WatchCodec.audioRoute(playback.hasPrivateOutput()))
                pushSessions()
                // The saved list only: the watch asks for a refresh when Recent audio is opened.
                sendAudioPages(state.loadAudio())
                pendingReply?.let { (sid, mid) ->
                    pendingReply = null
                    sendReplyToWatch(sid, mid)
                }
                // A question still waiting: the watch takes it over from the notification.
                approvals.values.forEach { showApproval(it) }
            }
            WatchCommand.ListSessions -> pushSessions()
            is WatchCommand.OpenSession -> openSession(cmd.sessionId)
            is WatchCommand.Send -> submit(cmd.sessionId, cmd.text)
            is WatchCommand.NewSession -> newSession(cmd.text)
            is WatchCommand.Play -> play(cmd.sessionId, cmd.messageId, cmd.speaker)
            is WatchCommand.Replay -> play(cmd.sessionId, cmd.messageId, cmd.speaker)
            WatchCommand.Pause -> playback.pause()
            is WatchCommand.Resume -> playback.resume(cmd.speaker)
            WatchCommand.Stop -> playback.stop()
            WatchCommand.ListAudio -> pushAudio(refresh = true)
            is WatchCommand.PlayOnWatch -> playOnWatch(cmd.sessionId, cmd.messageId, cmd.chunkBytes)
            is WatchCommand.AudioPull -> watchStream.pull(cmd.messageId, cmd.offset, cmd.length)
            is WatchCommand.AudioStop -> watchStream.stop(cmd.messageId)
            is WatchCommand.Approve -> answerApproval(cmd.requestId, cmd.choice)
            is WatchCommand.OpenReply -> {
                state.markHeard(cmd.messageId)
                sendReplyToWatch(cmd.sessionId, cmd.messageId, requested = true)
            }
        }
    }

    // -- sessions & history ---------------------------------------------------------------------

    /**
     * Recent sessions. The watch's list leaves automation sessions out at the source with
     * [forList], so they cannot crowd out chats; polling and Recent audio look at all of them.
     */
    private suspend fun fetchSessions(api: HermesApi, forList: Boolean = false): List<HermesSession> {
        val exclude = if (forList && !settings.showAutomation) HermesSession.AUTOMATION_SOURCES else emptyList()
        val sessions = try {
            api.listSessions(100, exclude).also { recordBackend(null) }
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
                fetchSessions(api, forList = true), settings.showMessaging, state.lastSeen(), state.readOnly(), activeTurns.keys,
                settings.allowTelegramReplies, settings.showAutomation,
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
            noteAudio(sessionsById[sessionId], raw)
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

    // -- recent audio ---------------------------------------------------------------------------

    /**
     * Send the Recent audio list. The saved list goes first, so the watch shows something at
     * once; with [refresh] it is then rebuilt from Hermes (see [AudioInbox] for the window).
     */
    suspend fun pushAudio(refresh: Boolean) {
        sendAudioPages(state.loadAudio())
        if (!refresh) return
        val api = api()
        if (api == null) {
            watch.send(WatchCodec.status(Protocol.Conn.NOT_CONFIGURED, "Set up phone app"))
            return
        }
        try {
            val sessions = fetchSessions(api)
                .filter { it.archived != true && it.hidden != true && it.is_internal_child != true }
                .sortedByDescending { it.activity }
                .take(AudioInbox.SESSIONS)
            val found = coroutineScope {
                val limit = Semaphore(4)
                sessions.map { s ->
                    async {
                        limit.withPermit {
                            val raw = runCatching { api.recentMessages(s.id, AudioInbox.MESSAGES) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull() ?: return@withPermit emptyList()
                            rememberReplies(raw)
                            AudioInbox.collect(s, raw)
                        }
                    }
                }.awaitAll().flatten()
            }
            val items = inboxMutex.withLock { AudioInbox.merge(state.loadAudio(), found).also { state.saveAudio(it) } }
            sendAudioPages(items)
        } catch (e: IOException) {
            watch.send(WatchCodec.status(Protocol.Conn.OFFLINE, "Offline - cached"))
        }
    }

    /** Add audio replies found in one session's transcript; refresh the watch if any were new. */
    private suspend fun noteAudio(session: HermesSession?, raw: List<HermesMessage>) {
        if (session == null) return
        val found = AudioInbox.collect(session, raw)
        if (found.isEmpty()) return
        val changed = inboxMutex.withLock {
            val before = state.loadAudio()
            val after = AudioInbox.merge(before, found)
            (after != before).also { if (it) state.saveAudio(after) }
        }
        if (changed && watchOpen) sendAudioPages(state.loadAudio())
    }

    private suspend fun sendAudioPages(items: List<AudioItem>) {
        val visible = AudioInbox.visible(items, settings.showMessaging, Protocol.MAX_AUDIO)
        val seen = state.lastSeen()
        val pages = if (visible.isEmpty()) listOf(WatchCodec.audioPage(0, 0, null, false))
        else visible.mapIndexed { i, item ->
            val isNew = !state.isHeard(item.messageId) && item.timestamp > (seen[item.sessionId] ?: 0.0) + 0.001
            WatchCodec.audioPage(i, visible.size, item, isNew)
        }
        watch.sendAll(pages)
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
            // The open conversation shows the reply as Hermes writes it.
            var lastStreamed = ""
            val stream = ReplyStream(scope) { soFar ->
                val sid = sessionId ?: return@ReplyStream
                val text = Text.fit(Text.forWatch(soFar), textBudget).first
                if (watchOpen && text != lastStreamed) {
                    lastStreamed = text
                    watch.send(WatchCodec.stream(sid, text, textBudget))
                }
            }
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
                onDelta = { stream.delta(it) },
                onApproval = { request, answer ->
                    val sid = sessionId
                    if (sid == null) {
                        answer("deny") // cannot happen: Hermes asks inside a session
                    } else {
                        OpenApproval(sid, title, request, answer).also { approvals[request.id] = it; showApproval(it) }
                    }
                },
                onApprovalClosed = { closeApproval(it) },
            )
            stream.finish()
            // The turn is over: any question it asked is settled.
            approvals.values.filter { it.sessionId == sessionId }.forEach { closeApproval(it.request.id) }
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
        job.invokeOnCompletion {
            activeTurns.values.removeAll { it === job }
            refreshService()
        }
        activeTurns[key] = job
        // Started while the watchapp is still open, so the turn can finish after it closes.
        refreshService()
        job.start()
    }

    private suspend fun problem(sessionId: String?, title: String, s: Protocol.Submit, detail: String) {
        submitState(sessionId, s, detail)
        if (!watchOpen) notifier.problem(title, detail)
    }

    private suspend fun submitState(sessionId: String?, s: Protocol.Submit, detail: String) {
        if (watchOpen) watch.send(WatchCodec.submit(sessionId, s, detail))
    }

    // -- approvals ------------------------------------------------------------------------------

    /**
     * Put an approval in front of the user: on the open watchapp when it can answer, otherwise as
     * a notification with the answers as actions, which the Pebble mirrors. Always alerts.
     */
    private suspend fun showApproval(open: OpenApproval) {
        val r = open.request
        val text = listOf(r.description, r.command).filter { it.isNotBlank() }.distinct().joinToString("\n")
            .ifBlank { "Hermes wants to go ahead" }
        if (watchOpen && watchApproves &&
            watch.send(WatchCodec.approval(open.sessionId, r.id, open.title, Text.forWatch(text), r.choices, closed = false, textBudget))
        ) {
            notifier.clearApproval(r.id)
            return
        }
        notifier.approval(open.sessionId, r.id, open.title, Text.forWatch(text), r.choices)
    }

    /** The user's answer, from the watch or a notification action. False if Hermes no longer waits. */
    suspend fun answerApproval(requestId: String, choice: String): Boolean {
        val open = approvals.remove(requestId) ?: return false
        notifier.clearApproval(requestId)
        val sent = open.answer(choice)
        submitState(open.sessionId, Protocol.Submit.WORKING, if (!sent) "Could not answer; check the desktop" else if (choice == "deny") "Denied" else "Approved")
        return sent
    }

    /** Hermes stopped waiting (answered elsewhere, timed out, or the turn ended). */
    private suspend fun closeApproval(requestId: String) {
        val open = approvals.remove(requestId) ?: return
        notifier.clearApproval(requestId)
        if (watchOpen && watchApproves) {
            watch.send(WatchCodec.approval(open.sessionId, requestId, open.title, "", emptyList(), closed = true, textBudget))
        }
    }

    /** The reply to a turn sent from the watch, through the same alert policy as polling. */
    private suspend fun deliverLatestReply(api: HermesApi, sessionId: String, title: String) {
        val raw = runCatching { api.recentMessages(sessionId, 20) }.getOrNull() ?: return
        rememberReplies(raw)
        val session = sessionsById[sessionId] ?: HermesSession(sessionId, title = title)
        noteAudio(session, raw)
        alerts.turnFinished(session, raw)
    }

    private fun alertPolicy() = AlertPolicy(
        enabled = settings.notifyReplies,
        audio = settings.alertAudio,
        watchTurns = settings.alertWatchTurns,
        chats = settings.alertChats,
        messagingText = settings.alertMessagingText,
        background = settings.alertBackground,
    )

    /**
     * Show one decided alert: on the open watchapp, otherwise as a phone notification (which
     * the Pebble mirrors). Returns whether it reached the user: the watch acknowledged the card,
     * or a notification was posted. A watch that does not acknowledge is treated as closed, so
     * a missed close event cannot swallow alerts, and the phone notification is used instead.
     */
    private suspend fun deliverAlert(alert: ReplyAlert): Boolean = ReplyDelivery(
        watchOpen = { watchOpen },
        sendWatch = { reply ->
            val acknowledged = sendReplyToWatch(reply.sessionId, reply.messageId, reply.text, reply.hasAudio)
            if (acknowledged && state.openSessionId == reply.sessionId) {
                sessionsById[reply.sessionId]?.let { state.markSeen(it.id, it.activity) }
            }
            acknowledged
        },
        closeWatch = { setWatchOpen(false) },
        postNative = { reply ->
            notifier.reply(reply.sessionId, reply.messageId, reply.title, reply.text,
                settings.privatePreviews, reply.hasAudio, reply.silent)
        },
    ).deliver(alert)

    /** Show one reply as a card on the watch. True when the watch acknowledged it. */
    private suspend fun sendReplyToWatch(
        sessionId: String, messageId: String, fallbackText: String? = null, hasAudio: Boolean? = null, requested: Boolean = false,
    ): Boolean {
        val reply = replyFor(sessionId, messageId)
        val text = reply?.text ?: fallbackText?.let(Text::forWatch) ?: return false
        val audio = hasAudio ?: reply?.audio?.isNotEmpty() ?: false
        val title = sessionsById[sessionId]?.displayTitle ?: "Hermes"
        var flags = if (audio) Protocol.MessageFlag.HAS_AUDIO else 0
        if (requested) flags = flags or Protocol.MessageFlag.REQUESTED
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

    /** A watch option changed in the companion app: tell an open watchapp now. */
    fun onWatchSettingsChanged() {
        if (!watchOpen) return
        scope.launch {
            settings.quickLaunch?.let { watch.send(WatchCodec.config(it)) }
            pushSessions()
            sendAudioPages(state.loadAudio())
        }
    }

    // -- reply monitoring -----------------------------------------------------------------------

    /**
     * Replies from other surfaces (Telegram, Ringbearer, desktop) are found by polling, every 30 s
     * while the watchapp is open and, with background checking on, at the chosen interval while it
     * is closed. Each check is an alarm ([CheckAlarm]) that wakes the phone: a coroutine timer stops
     * while the CPU sleeps, and the Pebble app's binding while the watchapp is open keeps the
     * process alive but does not keep the CPU awake. Every transition goes through here, so there
     * is never more than one schedule, and the foreground service is kept in line with it.
     */
    @Synchronized
    fun updateMonitoring() {
        val wanted = Monitor.of(settings.isConfigured, watchOpen, settings.backgroundSync)
        if (wanted != mode) {
            Log.i(TAG, "reply monitoring: $mode -> $wanted")
            // The watchapp opened or closed, or setup changed: check soon, then at the new pace.
            if (wanted == Monitor.OFF) CheckAlarm.cancel(app) else CheckAlarm.schedule(app, FIRST_ALARM_SECONDS)
            mode = wanted
        }
        refreshService()
    }

    /** Why the companion must keep running with the watchapp closed. */
    fun keepAlive() = KeepAlive(
        checking = settings.isConfigured && settings.backgroundSync,
        turns = activeTurns.size,
        playing = playing,
    )

    private fun refreshService() = SyncService.sync(app, keepAlive())

    /** One alarm-driven check, then the next alarm. */
    suspend fun scheduledCheck() {
        // Restores the schedule and the service if Android killed the process since the last one;
        // an exact alarm is allowed to start a foreground service.
        updateMonitoring()
        try {
            withTimeoutOrNull(CHECK_TIMEOUT_MS) { pollOnce() }
        } finally {
            // Whatever went wrong in this check, the next one is always scheduled: an alarm that
            // is not set again stops checking until the app is next opened.
            synchronized(this) {
                if (mode != Monitor.OFF) CheckAlarm.schedule(app, pollIntervalSeconds())
            }
        }
    }

    /** The network came back: catch up now rather than at the next check. */
    fun onNetworkBack() {
        if (mode != Monitor.OFF) scope.launch { pollOnce() }
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
        settings.lastCheckAt = System.currentTimeMillis()
        val moved = alerts.poll(sessions, busy = { activeTurns.containsKey(it) }) { id ->
            api.recentMessages(id, 20).also {
                rememberReplies(it)
                noteAudio(sessionsById[id], it)
            }
        }
        if (moved.isNotEmpty() && watchOpen) {
            pushSessions()
            state.openSessionId?.let { open -> if (open in moved) pushMessages(open) }
        }
    }

    // -- playback -------------------------------------------------------------------------------

    /**
     * Play the audio Hermes attached to one exact reply (its text-to-speech tool output). A reply
     * without audio is never read aloud. [speaker] plays it on the phone's own speaker; otherwise
     * only on headphones.
     */
    suspend fun play(sessionId: String, messageId: String, speaker: Boolean) {
        val reply = replyFor(sessionId, messageId)
        if (reply == null) {
            onPlaybackState(messageId, Protocol.Playback.ERROR, "Reply not found")
            return
        }
        if (reply.audio.isEmpty()) {
            onPlaybackState(messageId, Protocol.Playback.ERROR, "No Hermes audio for this reply")
            return
        }
        if (!speaker && !playback.hasPrivateOutput()) {
            onPlaybackState(messageId, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        val api = api()
        if (api == null) {
            onPlaybackState(messageId, Protocol.Playback.ERROR, "Set up phone app")
            return
        }
        onPlaybackState(messageId, Protocol.Playback.IDLE, "Getting Hermes audio")
        val clips = try {
            if (reply.audio.any { it.startsWith("attachment:") }) {
                // Automatic speech parts are sequential, not alternative encodings.
                reply.audio.filter { it.startsWith("attachment:") }.map { api.fetchAudio(it, audioDir) }
            } else listOfNotNull(reply.audio.firstNotNullOfOrNull { path ->
                runCatching { api.fetchAudio(path, audioDir) }.getOrNull()
            })
        } catch (e: IOException) {
            emptyList()
        }
        pruneAudio(clips.map { it.file }.toSet())
        if (clips.isNotEmpty()) playback.playFiles(messageId, clips.map { it.file }, speaker, "Playing Hermes audio")
        else onPlaybackState(messageId, Protocol.Playback.ERROR, "Could not get the audio")
    }

    // -- watch speaker ----------------------------------------------------------------------------

    /**
     * Play one exact reply on the watch's own speaker. The watch has no codec, so the reply's
     * Hermes audio is decoded here and sent as 8 kHz PCM that the watch pulls as it has room.
     * Only that reply's own audio is used, never the phone or headphones, and a failure is
     * reported to the watch, not rerouted.
     */
    private suspend fun playOnWatch(sessionId: String, messageId: String, chunk: Int) {
        suspend fun fail(reason: String) {
            Log.i(TAG, "watch speaker $messageId: $reason")
            watch.send(WatchCodec.audioStart(messageId, 0, Protocol.PcmFormat.PCM_8KHZ_8BIT, reason))
        }
        val cached = synchronized(watchPcm) { watchPcm[messageId] }
        val (pcm, label) = cached ?: run {
            val api = api() ?: return fail("Set up phone app")
            val reply = replyFor(sessionId, messageId) ?: return fail("Reply not found")
            if (reply.audio.isEmpty()) return fail("No Hermes audio for this reply")
            val sources = try {
                if (reply.audio.any { it.startsWith("attachment:") }) {
                    reply.audio.filter { it.startsWith("attachment:") }.map { api.fetchAudio(it, audioDir) }
                } else listOfNotNull(reply.audio.firstNotNullOfOrNull { path ->
                    runCatching { api.fetchAudio(path, audioDir) }.getOrNull()
                })
            } catch (e: IOException) {
                return fail("Could not get the audio")
            }
            if (sources.isEmpty()) return fail("Could not get the audio")
            pruneAudio(sources.map { it.file }.toSet())
            val converted = try {
                withContext(Dispatchers.Default) {
                    val output = java.io.ByteArrayOutputStream()
                    val limit = PcmConverter.WATCH_RATE * PcmConverter.MAX_SECONDS
                    for (source in sources) {
                        if (output.size() >= limit) break
                        val decoded = AudioDecoder.decode(source.file)
                        val bytes = PcmConverter.forWatch(decoded.samples, decoded.sampleRate, decoded.channels)
                        output.write(bytes, 0, bytes.size.coerceAtMost(limit - output.size()))
                    }
                    val suffix = if (output.size() >= limit) " (first 2 min)" else ""
                    output.toByteArray() to "Hermes audio$suffix"
                }
            } catch (e: IOException) {
                return fail("Cannot convert this audio")
            }
            synchronized(watchPcm) { watchPcm[messageId] = converted }
            converted
        }
        Log.i(TAG, "watch speaker $messageId: ${pcm.size} bytes, $label")
        watchStream.start(messageId, pcm, chunk, label)
    }

    private suspend fun replyFor(sessionId: String, messageId: String): Reply? {
        synchronized(replies) { replies[messageId] }?.let { return it }
        // Wide enough for anything the Recent audio list can show.
        val raw = runCatching { api()?.recentMessages(sessionId, AudioInbox.MESSAGES) }.getOrNull() ?: return null
        rememberReplies(raw)
        return synchronized(replies) { replies[messageId] }
    }

    /** Keep the audio cache small: the newest files only. */
    private fun pruneAudio(keep: Set<File> = emptySet()) {
        val files = audioDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.filter { it !in keep }.drop((MAX_AUDIO_FILES - keep.size).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun onPlaybackState(messageId: String?, s: Protocol.Playback, detail: String) {
        val active = s == Protocol.Playback.PLAYING || s == Protocol.Playback.PAUSED
        if (active != playing) {
            playing = active
            refreshService()
        }
        if (watchOpen) scope.launch { watch.send(WatchCodec.playback(messageId, s, detail)) }
    }

    init {
        // Last, once every field is set: a fresh process (boot, an alarm, the Pebble app binding)
        // picks up the check schedule and the foreground service where they were.
        updateMonitoring()
    }

    companion object {
        private const val TAG = "Wristbound"
        private const val DUPLICATE_WINDOW_MS = 15_000L
        private const val NEW_SESSION_TARGET = "\u0000new"
        private const val MAX_AUDIO_FILES = 24
        const val FOREGROUND_POLL_SECONDS = 30
        private const val FIRST_ALARM_SECONDS = 5
        private const val CHECK_TIMEOUT_MS = 30_000L

        @Volatile private var instance: Wristbound? = null

        fun get(context: Context): Wristbound =
            instance ?: synchronized(this) { instance ?: Wristbound(context).also { instance = it } }
    }
}
