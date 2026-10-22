package dev.wristbound.companion.core

import kotlinx.serialization.Serializable
import java.io.File

/** Per-session sync cursor. Message ids are SQLite autoincrement row ids (monotonic). */
@Serializable
data class SessionCursor(
    val lastMessageId: Long = 0,
    val lastActive: Double? = null,
    val messageCount: Int? = null,
    val latestReplyId: Long = 0,
)

enum class SubmissionState { PENDING, ACCEPTED, RUNNING, COMPLETED, FAILED, UNCERTAIN }

@Serializable
data class Submission(
    /** Idempotency-Key sent to Hermes; stable across retries of this logical submission. */
    val key: String,
    val watchSubmitId: Long?,
    val sessionId: String,
    val textPreview: String,
    val state: SubmissionState,
    val runId: String? = null,
    val attempts: Int = 0,
    val detail: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ActiveRun(val runId: String, val sessionId: String, val submissionKey: String, val lastSeq: Long? = null)

/**
 * Everything the companion must remember across process death: cursors, alert ledger,
 * submissions and the user's selected session. Bounded so it cannot grow without limit.
 */
@Serializable
data class SyncState(
    val installId: String = "",
    val bootstrappedAt: Double? = null,
    val cursors: Map<String, SessionCursor> = emptyMap(),
    val lastRead: Map<String, Long> = emptyMap(),
    /** Alert ledger, oldest first: `"<sessionId>#<messageId>"`. */
    val alerted: List<String> = emptyList(),
    val submissions: List<Submission> = emptyList(),
    val activeRuns: List<ActiveRun> = emptyList(),
    val activeSessionId: String? = null,
    val lastPollOkAt: Long? = null,
    val lastError: String? = null,
) {
    fun alreadyAlerted(sessionId: String, messageId: Long) = alertKey(sessionId, messageId) in alerted

    fun withAlerted(sessionId: String, messageId: Long): SyncState {
        val key = alertKey(sessionId, messageId)
        if (key in alerted) return this
        return copy(alerted = (alerted + key).takeLast(MAX_ALERT_LEDGER))
    }

    fun isUnread(sessionId: String): Boolean {
        val latest = cursors[sessionId]?.latestReplyId ?: 0
        return latest > (lastRead[sessionId] ?: 0)
    }

    fun upsertSubmission(s: Submission): SyncState =
        copy(submissions = (submissions.filterNot { it.key == s.key } + s).takeLast(MAX_SUBMISSIONS))

    fun submissionByWatchId(id: Long): Submission? = submissions.lastOrNull { it.watchSubmitId == id }

    companion object {
        const val MAX_ALERT_LEDGER = 1000
        const val MAX_SUBMISSIONS = 50
        fun alertKey(sessionId: String, messageId: Long) = "$sessionId#$messageId"
    }
}

interface StateStore {
    fun load(): SyncState
    fun save(state: SyncState)
}

/** Atomic JSON file store in app-private storage. */
class FileStateStore(private val file: File) : StateStore {
    override fun load(): SyncState = runCatching {
        if (file.exists()) HermesJson.decodeFromString(SyncState.serializer(), file.readText()) else SyncState()
    }.getOrElse { SyncState() }

    override fun save(state: SyncState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(HermesJson.encodeToString(SyncState.serializer(), state))
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }
}

class MemoryStateStore(var state: SyncState = SyncState()) : StateStore {
    override fun load() = state
    override fun save(state: SyncState) { this.state = state }
}
