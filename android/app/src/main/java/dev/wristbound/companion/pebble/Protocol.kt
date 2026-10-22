package dev.wristbound.companion.pebble

import java.util.UUID

/**
 * Watch <-> phone AppMessage protocol. Mirrors watch/src/c/protocol.h; see docs/PROTOCOL.md.
 *
 * Message keys are assigned by the Pebble SDK from the order of `messageKeys` in
 * watch/package.json, starting at 10000. Reordering that list is a protocol break.
 */
object Protocol {
    val WATCHAPP_UUID: UUID = UUID.fromString("094c587f-8bd7-4377-aa6b-ff55eb724b27")
    const val VERSION = 1

    object Key {
        const val CMD = 10000u
        const val TYPE = 10001u
        const val PROTO_VERSION = 10002u
        const val TEXT_BUDGET = 10003u
        const val SESSION_ID = 10004u
        const val TITLE = 10005u
        const val FLAGS = 10006u
        const val INDEX = 10007u
        const val TOTAL = 10008u
        const val MESSAGE_ID = 10009u
        const val ROLE = 10010u
        const val TEXT = 10011u
        const val STATE = 10012u
        const val DETAIL = 10013u
        const val FILTER = 10014u
        const val ROUTE = 10015u
    }

    object Cmd {
        const val HELLO = 1
        const val LIST_SESSIONS = 2
        const val OPEN_SESSION = 3
        const val SEND = 4
        const val NEW_SESSION = 5
        const val PLAY = 6
        const val PAUSE = 7
        const val RESUME = 8
        const val REPLAY = 9
        const val STOP = 10
    }

    object Type {
        const val SESSION = 1
        const val MESSAGE = 2
        const val STATUS = 3
        const val REPLY = 4
        const val PLAYBACK = 5
        const val SUBMIT = 6
        const val SESSION_OPENED = 7
    }

    enum class Conn(val code: Int) { OK(0), OFFLINE(1), STALE(2), NOT_CONFIGURED(3), AUTH_ERROR(4), ERROR(5) }

    enum class Playback(val code: Int) { IDLE(0), PLAYING(1), PAUSED(2), NEEDS_SPEAKER(3), ERROR(4), DONE(5) }

    enum class Submit(val code: Int) {
        NONE(0), QUEUED(1), WORKING(2), COMPLETED(3), FAILED(4), UNCERTAIN(5), READ_ONLY(6), CANCELLED(7)
    }

    object Route {
        const val CURRENT = 0
        const val SPEAKER = 1
    }

    object Role {
        const val USER = 0
        const val ASSISTANT = 1
        const val TOOL = 2
        const val SYSTEM = 3

        fun fromHermes(role: String?): Int = when (role) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "tool" -> TOOL
            else -> SYSTEM
        }
    }

    object SessionFlag {
        const val UNREAD = 1
        const val TELEGRAM = 2
        const val READ_ONLY = 4
        const val ACTIVE = 8
    }

    object MessageFlag {
        const val TRUNCATED = 1
        const val HAS_AUDIO = 2
        const val PENDING = 4
    }

    /** Default text budget if the watch never said HELLO (matches the aplite budget). */
    const val DEFAULT_TEXT_BUDGET = 320
    const val TITLE_BUDGET = 47
    const val DETAIL_BUDGET = 63
    const val SESSION_ID_BUDGET = 71
    const val MESSAGE_ID_BUDGET = 39
    const val MAX_SESSIONS = 20
    const val MAX_MESSAGES = 12
}
