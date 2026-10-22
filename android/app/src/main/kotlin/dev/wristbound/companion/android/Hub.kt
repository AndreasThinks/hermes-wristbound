package dev.wristbound.companion.android

import android.content.Context
import android.util.Log
import dev.wristbound.companion.core.ActiveRun
import dev.wristbound.companion.core.AlertPolicy
import dev.wristbound.companion.core.FileStateStore
import dev.wristbound.companion.core.HermesApi
import dev.wristbound.companion.core.HermesClient
import dev.wristbound.companion.core.HermesErrorKind
import dev.wristbound.companion.core.HermesException
import dev.wristbound.companion.core.MessageClassifier
import dev.wristbound.companion.core.MessageDto
import dev.wristbound.companion.core.MessageKind
import dev.wristbound.companion.core.SessionDto
import dev.wristbound.companion.core.SessionFilter
import dev.wristbound.companion.core.Submission
import dev.wristbound.companion.core.SubmissionState
import dev.wristbound.companion.core.Submitter
import dev.wristbound.companion.core.SyncEngine
import dev.wristbound.companion.core.SyncItem
import dev.wristbound.companion.core.SyncState
import dev.wristbound.companion.core.ToolActivityMode
import dev.wristbound.companion.core.TurnMedia
import dev.wristbound.companion.core.WP
import dev.wristbound.companion.core.WatchCommand
import dev.wristbound.companion.core.WatchEncoder
import dev.wristbound.companion.core.displayTitle
import dev.wristbound.companion.core.isTelegram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** UI-facing connection summary. */
data class HubStatus(
    val code: Int = WP.ST_UNCONFIGURED,
    val text: String = "Not configured",
    val sessions: List<SessionDto> = emptyList(),
    val lastPollOkAt: Long? = null,
)

/**
 * Process-wide coordinator. One instance per process (see [WristboundApp.hub]).
 * Hermes remains authoritative; this keeps only cursors, a bounded message cache and the
 * submission/alert ledgers needed for catch-up and deduplication.
 */
class Hub(private val context: Context) {
    val settings = Settings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = FileStateStore(File(context.filesDir, "sync-state.json"))
    private val stateLock = Mutex()
    private var state: SyncState = store.load().let {
        if (it.installId.isBlank()) it.copy(installId = UUID.randomUUID().toString().take(12)) else it
    }.also { store.save(it) }

    private val engine = SyncEngine()
    private val submitter = Submitter()
    val notifier = Notifier(context)
    val watch = WatchBridge(context, scope)
    val player = PlaybackController(context, this)

    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status

    /** Bounded in-memory message cache: session id -> chronological messages. */
    private val messageCache = object : LinkedHashMap<String, List<MessageDto>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MessageDto>>?) = size > 8
    }
    private var sessions: List<SessionDto> = emptyList()
    private val runJobs = ConcurrentHashMap<String, Job>()
    private var pollJob: Job? = null
    private val pollNow = MutableStateFlow(0L)
    private var durableIdempotency: Boolean? = null
    private var listGen = 0
    @Volatile private var pendingFocus: Pair<String, Long>? = null

    private var cachedApi: Pair<String, HermesClient>? = null

    fun api(): HermesApi? {
        if (!settings.isConfigured) return null
        val key = settings.baseUrl
        cachedApi?.let { (k, c) -> if (k == key) return c }
        return runCatching { HermesClient(settings.baseUrl, { settings.apiToken }) }
            .getOrNull()?.also { cachedApi = key to it }
    }

    fun onSettingsChanged() {
        cachedApi = null
        durableIdempotency = null
        requestPoll()
    }

    fun snapshot(): SyncState = state

    private suspend fun mutate(f: (SyncState) -> SyncState): SyncState = stateLock.withLock {
        state = f(state); store.save(state); state
    }

    // ------------------------------------------------------------------------------- polling

    /** Started by [WristboundService]; runs until the scope's job is cancelled. */
    fun startLoop() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            resumeActiveRuns()
            var failures = 0
            while (isActive) {
                val ok = pollOnce()
                failures = if (ok) 0 else failures + 1
                val base = settings.pollSeconds * 1000L
                val wait = if (ok) base else (base * (1 shl failures.coerceAtMost(4))).coerceAtMost(15 * 60_000L)
                val requested = pollNow.value
                // Sleep in small steps so requestPoll() wakes us promptly.
                var slept = 0L
                while (slept < wait && pollNow.value == requested && isActive) {
                    delay(500); slept += 500
                }
            }
        }
    }

    fun stopLoop() {
        pollJob?.cancel(); pollJob = null
    }

    fun requestPoll() {
        pollNow.value = System.nanoTime()
        if (pollJob?.isActive != true) scope.launch { pollOnce() }
    }

    private val pollLock = Mutex()

    suspend fun pollOnce(force: Set<String> = emptySet()): Boolean = pollLock.withLock {
        val api = api()
        if (api == null) {
            publishStatus(WP.ST_UNCONFIGURED, "Set Hermes URL and API key on phone")
            return false
        }
        try {
            if (durableIdempotency == null) {
                durableIdempotency = runCatching { Submitter.durableIdempotency(api.capabilities()) }.getOrNull()
            }
            val prefs = settings.alertPrefs
            val before = stateLock.withLock { state }
            val result = engine.poll(api, before, prefs, System.currentTimeMillis() / 1000.0, force)
            mutate { cur ->
                // Merge: keep submissions/runs/read marks that changed while we were polling.
                result.state.copy(
                    submissions = cur.submissions, activeRuns = cur.activeRuns,
                    activeSessionId = cur.activeSessionId,
                    lastRead = cur.lastRead + result.state.lastRead.filterKeys { it !in cur.lastRead },
                )
            }
            sessions = result.sessions
            synchronized(messageCache) { result.fetched.forEach { (k, v) -> messageCache[k] = v } }
            handleItems(result.items)
            publishStatus(WP.ST_OK, "Synced")
            if (watch.appOpen) pushSessionList()
            return true
        } catch (e: HermesException) {
            val code = if (e.kind == HermesErrorKind.UNAUTHORIZED) WP.ST_AUTH else WP.ST_OFFLINE
            val text = if (code == WP.ST_AUTH) "Hermes rejected the API key" else "Hermes unreachable"
            mutate { it.copy(lastError = e.message) }
            publishStatus(code, text)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "poll failed", e)
            mutate { it.copy(lastError = e.javaClass.simpleName) }
            publishStatus(WP.ST_OFFLINE, "Sync error")
            return false
        }
    }

    private suspend fun publishStatus(code: Int, text: String) {
        val visible = SessionFilter.visible(sessions, settings.alertPrefs.includeTelegram)
        _status.value = HubStatus(code, text, visible, state.lastPollOkAt)
        notifier.updateServiceStatus(text)
        if (watch.appOpen) watch.send(WatchEncoder.status(code, text))
    }

    private suspend fun handleItems(items: List<SyncItem>) {
        val prefs = settings.alertPrefs
        for (item in items) {
            val sid = item.session.id
            when (item.kind) {
                MessageKind.ASSISTANT_FINAL -> {
                    val text = MessageClassifier.displayText(item.message)
                    if (item.decision.alert) {
                        val hasAudio = TurnMedia.audioFor(cached(sid), item.message.id).isNotEmpty()
                        notifier.showReply(item.session, item.message.id, text, item.collapsed, hasAudio, prefs.notificationPreviews)
                        if (watch.appOpen) {
                            watch.send(WatchEncoder.alert(sid, item.message.id, item.session.displayTitle(), text, replyFlags(sid, item.message.id)))
                        }
                    } else if (item.decision.display && watch.appOpen && sid == state.activeSessionId) {
                        pushMessages(sid)
                    }
                    // A reply in a session closes any running submission's "completed" loop.
                    markRunsCompletedFor(sid)
                }
                MessageKind.TOOL_RESULT, MessageKind.ASSISTANT_TOOL_CALL -> {
                    val label = MessageClassifier.toolLabel(item.message) ?: "tool"
                    if (item.decision.display && watch.appOpen) watch.send(WatchEncoder.toolStatus(sid, "Tool: $label"))
                    if (item.decision.alert) notifier.showToolActivity(item.session, item.message.id, label)
                }
                else -> Unit
            }
        }
    }

    fun cached(sessionId: String): List<MessageDto> = synchronized(messageCache) { messageCache[sessionId] } ?: emptyList()

    suspend fun messagesFor(sessionId: String, refresh: Boolean): List<MessageDto> {
        if (!refresh) cached(sessionId).takeIf { it.isNotEmpty() }?.let { return it }
        val api = api() ?: return cached(sessionId)
        return runCatching { api.latestMessages(sessionId, 30).data.sortedBy { it.id } }
            .onSuccess { msgs -> synchronized(messageCache) { messageCache[sessionId] = msgs } }
            .getOrElse { cached(sessionId) }
    }

    fun sessionById(id: String): SessionDto? = sessions.firstOrNull { it.id == id }

    // ---------------------------------------------------------------------------- submissions

    suspend fun submit(sessionIdOrNull: String?, text: String, watchSubmitId: Long?): Submission? {
        val api = api() ?: run {
            watchSubmitId?.let { watch.send(WatchEncoder.submitState(it, WP.SS_FAILED, "Phone not configured", "")) }
            return null
        }
        val sessionId = sessionIdOrNull ?: newSessionId(watchSubmitId)
        if (sessionIdOrNull == null) {
            try {
                api.createSession(sessionId, null)
            } catch (e: HermesException) {
                watchSubmitId?.let { watch.send(WatchEncoder.submitState(it, WP.SS_FAILED, "New session failed", "")) }
                return null
            }
        }
        val result = submitter.submit(
            api, stateLock.withLock { state }, sessionId, text, watchSubmitId, UUID.randomUUID().toString(),
            durableIdempotency = durableIdempotency ?: false,
        ) { st, sub ->
            mutate { cur -> cur.copy(submissions = st.submissions, activeRuns = st.activeRuns) }
            reportSubmission(sub)
        }
        if (result.duplicate) {
            reportSubmission(result.submission)
            return result.submission
        }
        if (sessionIdOrNull == null) mutate { it.copy(activeSessionId = sessionId) }
        result.submission.runId?.let { followRun(ActiveRun(it, sessionId, result.submission.key)) }
        return result.submission
    }

    private fun newSessionId(watchSubmitId: Long?): String {
        // Deterministic per watch submission so a retried "new session" cannot create two.
        val suffix = watchSubmitId?.toString(16) ?: UUID.randomUUID().toString().take(8)
        return "wristbound_${state.installId}_$suffix"
    }

    private suspend fun reportSubmission(sub: Submission) {
        val wid = sub.watchSubmitId ?: return
        val code = when (sub.state) {
            SubmissionState.PENDING -> WP.SS_PENDING
            SubmissionState.ACCEPTED -> WP.SS_ACCEPTED
            SubmissionState.RUNNING -> WP.SS_RUNNING
            SubmissionState.COMPLETED -> WP.SS_COMPLETED
            SubmissionState.FAILED -> WP.SS_FAILED
            SubmissionState.UNCERTAIN -> WP.SS_UNCERTAIN
        }
        val text = when (sub.state) {
            SubmissionState.PENDING -> if (sub.attempts > 0) "Retrying…" else "Sending…"
            SubmissionState.ACCEPTED -> "Queued in Hermes"
            SubmissionState.RUNNING -> "Hermes is working"
            SubmissionState.COMPLETED -> "Reply received"
            SubmissionState.FAILED -> sub.detail ?: "Failed"
            SubmissionState.UNCERTAIN -> "Unknown if sent; check before resending"
        }
        watch.send(WatchEncoder.submitState(wid, code, text, sub.sessionId))
    }

    private suspend fun resumeActiveRuns() {
        state.activeRuns.forEach { followRun(it) }
    }

    /** Follows one run's SSE events, resuming from the last seen seq; falls back to polling status. */
    private fun followRun(run: ActiveRun) {
        if (runJobs[run.runId]?.isActive == true) return
        runJobs[run.runId] = scope.launch {
            WristboundService.withWakeLock(context, "run", 10 * 60_000L) { followRunLocked(run) }
            runJobs.remove(run.runId)
        }
    }

    private suspend fun followRunLocked(run: ActiveRun) {
        run {
            val api = api() ?: return
            var lastSeq = run.lastSeq
            var terminal: String? = null
            var attempts = 0
            var running = false
            while (currentCoroutineContext().isActive && terminal == null && attempts < 30) {
                attempts++
                try {
                    api.streamRunEvents(run.runId, lastSeq) { ev ->
                        ev.seq?.let { s -> lastSeq = s; persistRunSeq(run.runId, s) }
                        if (!running) {
                            running = true
                            setSubmissionState(run.submissionKey, SubmissionState.RUNNING)
                        }
                        when {
                            ev.name.startsWith("run.") && ev.name.removePrefix("run.") in setOf("completed", "failed", "cancelled", "interrupted") ->
                                terminal = ev.name.removePrefix("run.")
                            ev.name == "tool.started" -> onRunTool(run, ev.string("tool") ?: "tool")
                        }
                    }
                    if (terminal == null) {
                        // Stream closed without a terminal event (or run unknown): ask for status.
                        val st = api.getRun(run.runId)
                        if (st.isTerminal) terminal = st.status
                    }
                } catch (e: HermesException) {
                    if (e.kind == HermesErrorKind.NOT_FOUND) {
                        // Run retention expired (status is kept ~1h). The reply, if any, is in the
                        // session history and will be found by polling.
                        terminal = "unknown"
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "run stream ${run.runId} interrupted: ${e.message}")
                }
                if (terminal == null) delay((2000L * attempts).coerceAtMost(30_000))
            }
            val final = when (terminal) {
                "completed" -> SubmissionState.COMPLETED
                "unknown", null -> null
                else -> SubmissionState.FAILED
            }
            final?.let { setSubmissionState(run.submissionKey, it, if (it == SubmissionState.FAILED) "Run $terminal" else null) }
            mutate { it.copy(activeRuns = it.activeRuns.filterNot { r -> r.runId == run.runId }) }
            // Single alert path: the reply is discovered (and deduplicated) by polling.
            pollOnce(force = setOf(run.sessionId))
        }
    }

    private suspend fun persistRunSeq(runId: String, seq: Long) {
        mutate { st -> st.copy(activeRuns = st.activeRuns.map { if (it.runId == runId) it.copy(lastSeq = seq) else it }) }
    }

    private suspend fun onRunTool(run: ActiveRun, tool: String) {
        val session = sessionById(run.sessionId) ?: SessionDto(id = run.sessionId)
        val d = AlertPolicy.decide(MessageKind.TOOL_RESULT, session, settings.alertPrefs, state.activeSessionId)
        if (d.display && watch.appOpen) watch.send(WatchEncoder.toolStatus(run.sessionId, "Tool: $tool"))
        if (d.alert) notifier.showToolActivity(session, null, tool)
    }

    private suspend fun setSubmissionState(key: String, s: SubmissionState, detail: String? = null) {
        var changed: Submission? = null
        mutate { st ->
            val sub = st.submissions.firstOrNull { it.key == key } ?: return@mutate st
            if (sub.state == s || sub.state == SubmissionState.COMPLETED) return@mutate st
            changed = sub.copy(state = s, detail = detail ?: sub.detail, updatedAt = System.currentTimeMillis())
            st.upsertSubmission(changed!!)
        }
        changed?.let { reportSubmission(it) }
    }

    private suspend fun markRunsCompletedFor(sessionId: String) {
        state.submissions.filter { it.sessionId == sessionId && it.state in setOf(SubmissionState.ACCEPTED, SubmissionState.RUNNING) }
            .filter { sub -> state.activeRuns.none { it.submissionKey == sub.key } }
            .forEach { setSubmissionState(it.key, SubmissionState.COMPLETED) }
    }

    // ---------------------------------------------------------------------------------- watch

    fun onWatchAppOpened() {
        scope.launch {
            watch.appOpen = true
            watch.send(WatchEncoder.status(_status.value.code, _status.value.text))
            pushPrefs()
            pushSessionList()
            pendingFocus?.let { (sid, mid) ->
                pendingFocus = null
                focusOnWatch(sid, mid)
            }
        }
    }

    fun onWatchAppClosed() {
        watch.appOpen = false
    }

    /** "Open on watch" from a notification: launch the watchapp and show that exact reply. */
    fun openOnWatch(sessionId: String, msgId: Long) {
        pendingFocus = sessionId to msgId
        scope.launch {
            if (watch.appOpen) {
                pendingFocus = null
                focusOnWatch(sessionId, msgId)
            } else {
                watch.startApp()
            }
        }
    }

    private suspend fun focusOnWatch(sessionId: String, msgId: Long) {
        val msg = messagesFor(sessionId, refresh = false).firstOrNull { it.id == msgId }
            ?: messagesFor(sessionId, refresh = true).firstOrNull { it.id == msgId } ?: return
        val title = sessionById(sessionId)?.displayTitle() ?: "Hermes"
        watch.send(WatchEncoder.alert(sessionId, msgId, title, MessageClassifier.displayText(msg), replyFlags(sessionId, msgId)))
    }

    fun handleWatch(cmd: WatchCommand) {
        scope.launch {
            when (cmd) {
                is WatchCommand.Hello -> onWatchAppOpened()
                WatchCommand.ListSessions -> { requestPoll(); pushSessionList() }
                is WatchCommand.OpenSession -> {
                    mutate { it.copy(activeSessionId = cmd.sessionId) }
                    pushMessages(cmd.sessionId, refresh = true)
                }
                is WatchCommand.GetBody -> {
                    val msg = messagesFor(cmd.sessionId, refresh = false).firstOrNull { it.id == cmd.msgId }
                        ?: messagesFor(cmd.sessionId, refresh = true).firstOrNull { it.id == cmd.msgId }
                    val text = msg?.let { MessageClassifier.displayText(it).ifBlank { toolText(it) } } ?: "(message not found)"
                    WatchEncoder.bodyChunks(cmd.msgId, text).forEach { watch.send(it) }
                    if (msg != null && MessageClassifier.kind(msg) == MessageKind.ASSISTANT_FINAL) markRead(cmd.sessionId, cmd.msgId)
                }
                is WatchCommand.Submit -> submit(cmd.sessionId, cmd.text, cmd.submitId)
                is WatchCommand.Play -> player.play(cmd.sessionId, cmd.msgId, cmd.speaker)
                is WatchCommand.Playback -> player.control(cmd.action)
                is WatchCommand.SetPref -> applyWatchPref(cmd.id, cmd.value)
                is WatchCommand.MarkRead -> markRead(cmd.sessionId, cmd.msgId)
                is WatchCommand.Unknown -> Log.w(TAG, "unknown watch command ${cmd.type}")
            }
        }
    }

    private fun toolText(m: MessageDto): String = "[${MessageClassifier.toolLabel(m) ?: m.role}]"

    suspend fun markRead(sessionId: String, msgId: Long) {
        mutate { st ->
            val prev = st.lastRead[sessionId] ?: 0
            if (msgId <= prev) st else st.copy(lastRead = st.lastRead + (sessionId to msgId))
        }
        notifier.cancelReply(sessionId, msgId)
    }

    private suspend fun applyWatchPref(id: Int, value: Int) {
        when (id) {
            WP.PREF_INCLUDE_TELEGRAM -> settings.updateAlertPrefs { it.copy(includeTelegram = value != 0) }
            WP.PREF_TOOL_MODE -> settings.updateAlertPrefs { it.copy(toolActivity = Settings.toolModeFromWire(value)) }
            WP.PREF_REPLY_MODE -> settings.updateAlertPrefs { it.copy(replies = Settings.replyModeFromWire(value)) }
        }
        pushPrefs()
        pushSessionList()
    }

    suspend fun pushPrefs() {
        val p = settings.alertPrefs
        watch.send(WatchEncoder.pref(WP.PREF_INCLUDE_TELEGRAM, if (p.includeTelegram) 1 else 0))
        watch.send(WatchEncoder.pref(WP.PREF_TOOL_MODE, Settings.toolModeToWire(p.toolActivity)))
        watch.send(WatchEncoder.pref(WP.PREF_REPLY_MODE, Settings.replyModeToWire(p.replies)))
    }

    suspend fun pushSessionList() {
        val visible = SessionFilter.visible(sessions, settings.alertPrefs.includeTelegram).take(WP.MAX_LIST)
        val gen = (++listGen) and 0xFF
        if (visible.isEmpty()) {
            watch.send(WatchEncoder.sessionItem(gen, 0, 0, "", "", 0))
            return
        }
        visible.forEachIndexed { i, s ->
            var flags = 0
            if (state.isUnread(s.id)) flags = flags or WP.SF_UNREAD
            if (s.isTelegram()) flags = flags or WP.SF_TELEGRAM
            if (s.id == state.activeSessionId) flags = flags or WP.SF_SELECTED
            if (s.lastActive != null && System.currentTimeMillis() / 1000.0 - s.lastActive < 300) flags = flags or WP.SF_ACTIVE
            watch.send(WatchEncoder.sessionItem(gen, i, visible.size, s.id, s.displayTitle(), flags))
        }
    }

    suspend fun pushMessages(sessionId: String, refresh: Boolean = false) {
        val prefs = settings.alertPrefs
        val all = messagesFor(sessionId, refresh)
        val shown = all.filter { m ->
            when (MessageClassifier.kind(m)) {
                MessageKind.USER, MessageKind.ASSISTANT_FINAL -> true
                MessageKind.TOOL_RESULT, MessageKind.ASSISTANT_TOOL_CALL -> prefs.toolActivity != ToolActivityMode.HIDDEN
                MessageKind.OTHER -> false
            }
        }.takeLast(WP.MAX_LIST)
        val gen = (++listGen) and 0xFF
        if (shown.isEmpty()) {
            watch.send(WatchEncoder.messageItem(gen, 0, 0, sessionId, 0, 0, 0, ""))
            return
        }
        // Newest first on the watch.
        shown.asReversed().forEachIndexed { i, m ->
            val kind = MessageClassifier.kind(m)
            val role = when (kind) {
                MessageKind.USER -> WP.ROLE_USER
                MessageKind.ASSISTANT_FINAL -> WP.ROLE_ASSISTANT
                else -> WP.ROLE_TOOL
            }
            val preview = when (kind) {
                MessageKind.USER, MessageKind.ASSISTANT_FINAL -> MessageClassifier.displayText(m)
                else -> toolText(m)
            }
            val flags = if (kind == MessageKind.ASSISTANT_FINAL) replyFlags(sessionId, m.id) else 0
            watch.send(WatchEncoder.messageItem(gen, i, shown.size, sessionId, m.id, role, flags, preview))
        }
    }

    private fun replyFlags(sessionId: String, msgId: Long): Int {
        var f = WP.MF_PLAYABLE
        if (TurnMedia.audioFor(cached(sessionId), msgId).isNotEmpty()) f = f or WP.MF_HERMES_AUDIO
        return f
    }

    companion object {
        private const val TAG = "WristboundHub"
    }
}
