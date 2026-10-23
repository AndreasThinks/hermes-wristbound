package dev.wristbound.companion.notify

import dev.wristbound.companion.pebble.Text

/** Content and action policy shared by native rendering and JVM contract tests. */
data class ReplyNotice(val key: String, val title: String, val body: String, val actions: List<Action>) {
    val sender = "Hermes"
    data class Action(val action: String, val label: String, val showsUserInterface: Boolean = false) {
        fun identity(key: String) = "$key:$action"
    }
    companion object {
        const val ACTION_PLAY = "dev.wristbound.companion.PLAY"
        const val ACTION_PLAY_SPEAKER = "dev.wristbound.companion.PLAY_SPEAKER"
        const val ACTION_OPEN_ON_WATCH = "dev.wristbound.companion.OPEN_ON_WATCH"
        fun create(sessionId: String, messageId: String, title: String, text: String, private: Boolean, audio: Boolean): ReplyNotice {
            val fallback = if (audio) "New audio reply" else "New reply"
            val body = if (private) fallback else Text.forSpeech(Text.forWatch(text)).ifBlank { fallback }
            val actions = buildList {
                if (audio) {
                    add(Action(ACTION_PLAY, "Play audio"))
                    add(Action(ACTION_PLAY_SPEAKER, "Play on phone speaker"))
                }
                add(Action(ACTION_OPEN_ON_WATCH, "Open in Wristbound"))
            }
            return ReplyNotice("${sessionId.length}:$sessionId$messageId", if (private) "Hermes" else title.ifBlank { "Hermes" }, body, actions)
        }
    }
}
