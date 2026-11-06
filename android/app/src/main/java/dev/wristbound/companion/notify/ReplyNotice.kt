package dev.wristbound.companion.notify

import dev.wristbound.companion.pebble.Text

/** Content and action policy shared by native rendering and JVM contract tests. */
data class ReplyNotice(val key: String, val title: String, val body: String, val actions: List<Action>) {
    val sender = "Wristbound"
    /** [textInput]: the action carries a RemoteInput, so the Pebble offers dictation and canned replies. */
    data class Action(val action: String, val label: String, val showsUserInterface: Boolean = false, val textInput: Boolean = false) {
        fun identity(key: String) = "$key:$action"
    }
    companion object {
        const val ACTION_PLAY = "dev.wristbound.companion.PLAY"
        const val ACTION_PLAY_SPEAKER = "dev.wristbound.companion.PLAY_SPEAKER"
        const val ACTION_OPEN_ON_WATCH = "dev.wristbound.companion.OPEN_ON_WATCH"
        const val ACTION_REPLY = "dev.wristbound.companion.REPLY"
        /** The RemoteInput result key for [ACTION_REPLY]. */
        const val KEY_REPLY_TEXT = "reply_text"
        /** Offered by the Pebble next to dictation, for watches without a microphone. */
        val CANNED_REPLIES = listOf("Yes", "No", "Go ahead", "Thanks")

        /** The notification tag for one reply; the same for its text and late-audio versions. */
        fun keyFor(sessionId: String, messageId: String) = "${sessionId.length}:$sessionId$messageId"

        /** [canReply] is false where Hermes would refuse a reply (a messaging session with replies off). */
        fun create(
            sessionId: String, messageId: String, title: String, text: String, private: Boolean, audio: Boolean,
            canReply: Boolean = true,
        ): ReplyNotice {
            val preview = if (private) "" else Text.forSpeech(Text.forWatch(text)).trim()
            // Put availability first: the native watch preview can truncate long reply text.
            val body = if (audio) {
                if (preview.isBlank()) "Audio reply available" else "Audio reply available\n$preview"
            } else preview.ifBlank { "New reply" }
            val conversation = if (private) "" else title.trim()
            val heading = if (conversation.isBlank() || conversation == "Wristbound") "Wristbound"
                else "Wristbound · $conversation"
            // The Pebble lists actions in this order: answering comes first, then opening the
            // conversation in the watchapp, then playback.
            val actions = buildList {
                if (canReply) add(Action(ACTION_REPLY, "Reply", textInput = true))
                add(Action(ACTION_OPEN_ON_WATCH, "Open in Wristbound"))
                if (audio) {
                    add(Action(ACTION_PLAY, "Play audio"))
                    add(Action(ACTION_PLAY_SPEAKER, "Play on phone speaker"))
                }
            }
            return ReplyNotice(keyFor(sessionId, messageId), heading, body, actions)
        }
    }
}
