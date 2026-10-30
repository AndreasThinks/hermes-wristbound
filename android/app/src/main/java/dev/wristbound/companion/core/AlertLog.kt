package dev.wristbound.companion.core

import kotlinx.serialization.Serializable

/**
 * One alert decision, kept on the phone so the setup screen can show what buzzed, what stayed
 * silent and which switch decided it. Only first decisions are recorded: a refresh that adds
 * Play to an alert already shown is not a new alert.
 */
@Serializable
data class AlertRecord(
    /** Wall-clock ms. */
    val at: Long,
    val title: String,
    /** A short preview, or blank when previews are private. */
    val preview: String,
    val kind: ReplyKind,
    val audio: Boolean,
    val outcome: Outcome,
    /** For a silent reply, the switch that kept it silent. */
    val reason: String = "",
) {
    enum class Outcome { ALERTED, SILENT, FAILED }

    /** "BUZZED · CHAT REPLY", "SILENT · BACKGROUND OFF", "NOT DELIVERED". */
    val label: String get() = when (outcome) {
        Outcome.ALERTED -> "BUZZED · ${kindLabel(kind, audio)}"
        Outcome.SILENT -> "SILENT · $reason"
        Outcome.FAILED -> "NOT DELIVERED"
    }

    companion object {
        const val MAX = 50
        private const val PREVIEW_CHARS = 90

        fun kindLabel(kind: ReplyKind, audio: Boolean): String = if (audio) "VOICE" else when (kind) {
            ReplyKind.WATCH_TURN -> "WATCH REPLY"
            ReplyKind.CHAT -> "CHAT REPLY"
            ReplyKind.MESSAGING -> "MESSAGING"
            ReplyKind.BACKGROUND -> "BACKGROUND"
        }

        /** Which switch kept a reply of [kind] silent under [policy]. */
        fun silentReason(policy: AlertPolicy, kind: ReplyKind, audio: Boolean): String = when {
            !policy.enabled -> "ALERTS OFF"
            audio -> "VOICE OFF"
            else -> when (kind) {
                ReplyKind.WATCH_TURN -> "WATCH REPLIES OFF"
                ReplyKind.CHAT -> "CHATS OFF"
                ReplyKind.MESSAGING -> "MESSAGING OFF"
                ReplyKind.BACKGROUND -> "BACKGROUND OFF"
            }
        }

        fun preview(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS - 1).trimEnd() + "…"
        }
    }
}
