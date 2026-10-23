package dev.wristbound.companion.notify

import dev.wristbound.companion.pebble.Text

/** Content and action policy shared by native rendering and JVM contract tests. */
data class ReplyNotice(val key: String, val title: String, val body: String, val actions: List<Action>) {
    val sender = "Wristbound"
    data class Action(val action: String, val label: String, val showsUserInterface: Boolean = false) {
        fun identity(key: String) = "$key:$action"
    }
    companion object {
        const val ACTION_PLAY = "dev.wristbound.companion.PLAY"
        const val ACTION_PLAY_SPEAKER = "dev.wristbound.companion.PLAY_SPEAKER"
        const val ACTION_OPEN_ON_WATCH = "dev.wristbound.companion.OPEN_ON_WATCH"
        fun create(sessionId: String, messageId: String, title: String, text: String, private: Boolean, audio: Boolean): ReplyNotice {
            val preview = if (private) "" else Text.forSpeech(Text.forWatch(text)).trim()
            // Put availability first: the native watch preview can truncate long reply text.
            val body = if (audio) {
                if (preview.isBlank()) "Audio reply available" else "Audio reply available\n$preview"
            } else preview.ifBlank { "New reply" }
            val conversation = if (private) "" else title.trim()
            val heading = if (conversation.isBlank() || conversation == "Wristbound") "Wristbound"
                else "Wristbound · $conversation"
            val actions = buildList {
                if (audio) {
                    add(Action(ACTION_PLAY, "Play audio"))
                    add(Action(ACTION_PLAY_SPEAKER, "Play on phone speaker"))
                }
                add(Action(ACTION_OPEN_ON_WATCH, "Open in Wristbound"))
            }
            return ReplyNotice("${sessionId.length}:$sessionId$messageId", heading, body, actions)
        }
    }
}
