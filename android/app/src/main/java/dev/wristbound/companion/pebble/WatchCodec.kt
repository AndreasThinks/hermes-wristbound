package dev.wristbound.companion.pebble

import dev.wristbound.companion.core.AudioItem
import dev.wristbound.companion.core.WatchMessage
import dev.wristbound.companion.core.WatchSession
import dev.wristbound.companion.pebble.Protocol.Key
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

/** Commands decoded from watch AppMessages. */
sealed class WatchCommand {
    data class Hello(val textBudget: Int, val protoVersion: Int, val includeTelegram: Boolean, val sessionId: String?) : WatchCommand()
    data class ListSessions(val includeTelegram: Boolean) : WatchCommand()
    data class OpenSession(val sessionId: String) : WatchCommand()
    data class Send(val sessionId: String, val text: String) : WatchCommand()
    data class NewSession(val text: String) : WatchCommand()
    data class Play(val sessionId: String, val messageId: String, val speaker: Boolean) : WatchCommand()
    data class Replay(val sessionId: String, val messageId: String) : WatchCommand()
    data object Pause : WatchCommand()
    data object Resume : WatchCommand()
    data object Stop : WatchCommand()
    data class ListAudio(val includeTelegram: Boolean) : WatchCommand()
    data class OpenReply(val sessionId: String, val messageId: String) : WatchCommand()
}

/** Pure encoding/decoding between the protocol and PebbleKit dictionaries. */
object WatchCodec {

    fun decode(data: PebbleDictionary): WatchCommand? {
        fun str(key: UInt): String? = (data[key] as? PebbleDictionaryItem.Text)?.value
        fun int(key: UInt): Int? = when (val v = data[key]) {
            is PebbleDictionaryItem.UInt8 -> v.value.toInt()
            is PebbleDictionaryItem.Int8 -> v.value.toInt()
            is PebbleDictionaryItem.UInt16 -> v.value.toInt()
            is PebbleDictionaryItem.Int16 -> v.value.toInt()
            is PebbleDictionaryItem.UInt32 -> v.value.toInt()
            is PebbleDictionaryItem.Int32 -> v.value
            else -> null
        }

        return when (int(Key.CMD)) {
            Protocol.Cmd.HELLO -> WatchCommand.Hello(
                textBudget = int(Key.TEXT_BUDGET) ?: Protocol.DEFAULT_TEXT_BUDGET,
                protoVersion = int(Key.PROTO_VERSION) ?: 0,
                includeTelegram = (int(Key.FILTER) ?: 1) != 0,
                sessionId = str(Key.SESSION_ID)?.takeIf { it.isNotEmpty() },
            )
            Protocol.Cmd.LIST_SESSIONS -> WatchCommand.ListSessions((int(Key.FILTER) ?: 1) != 0)
            Protocol.Cmd.OPEN_SESSION -> str(Key.SESSION_ID)?.let { WatchCommand.OpenSession(it) }
            Protocol.Cmd.SEND -> {
                val sid = str(Key.SESSION_ID)
                val text = str(Key.TEXT)
                if (sid.isNullOrEmpty() || text.isNullOrBlank()) null else WatchCommand.Send(sid, text)
            }
            Protocol.Cmd.NEW_SESSION -> str(Key.TEXT)?.takeIf { it.isNotBlank() }?.let { WatchCommand.NewSession(it) }
            Protocol.Cmd.PLAY -> {
                val sid = str(Key.SESSION_ID) ?: return null
                val mid = str(Key.MESSAGE_ID) ?: return null
                WatchCommand.Play(sid, mid, int(Key.ROUTE) == Protocol.Route.SPEAKER)
            }
            Protocol.Cmd.REPLAY -> {
                val sid = str(Key.SESSION_ID) ?: return null
                val mid = str(Key.MESSAGE_ID) ?: return null
                WatchCommand.Replay(sid, mid)
            }
            Protocol.Cmd.PAUSE -> WatchCommand.Pause
            Protocol.Cmd.RESUME -> WatchCommand.Resume
            Protocol.Cmd.STOP -> WatchCommand.Stop
            Protocol.Cmd.LIST_AUDIO -> WatchCommand.ListAudio((int(Key.FILTER) ?: 1) != 0)
            Protocol.Cmd.OPEN_REPLY -> {
                val sid = str(Key.SESSION_ID)?.takeIf { it.isNotEmpty() } ?: return null
                val mid = str(Key.MESSAGE_ID)?.takeIf { it.isNotEmpty() } ?: return null
                WatchCommand.OpenReply(sid, mid)
            }
            else -> null
        }
    }

    fun sessionPage(index: Int, total: Int, session: WatchSession?): PebbleDictionary = buildMap {
        put(Key.TYPE, u8(Protocol.Type.SESSION))
        put(Key.INDEX, u8(index))
        put(Key.TOTAL, u8(total))
        if (session != null) {
            put(Key.SESSION_ID, text(session.id, Protocol.SESSION_ID_BUDGET))
            put(Key.TITLE, text(session.title, Protocol.TITLE_BUDGET))
            put(Key.FLAGS, u8(session.flags))
        }
    }

    fun messagePage(sessionId: String, index: Int, total: Int, message: WatchMessage?, textBudget: Int): PebbleDictionary =
        buildMap {
            put(Key.TYPE, u8(Protocol.Type.MESSAGE))
            put(Key.SESSION_ID, text(sessionId, Protocol.SESSION_ID_BUDGET))
            put(Key.INDEX, u8(index))
            put(Key.TOTAL, u8(total))
            if (message != null) {
                val (body, truncated) = Text.fit(message.text, textBudget)
                put(Key.MESSAGE_ID, text(message.id, Protocol.MESSAGE_ID_BUDGET))
                put(Key.ROLE, u8(message.role))
                put(Key.FLAGS, u8(message.flags or if (truncated) Protocol.MessageFlag.TRUNCATED else 0))
                put(Key.TEXT, PebbleDictionaryItem.Text(body))
            }
        }

    /** One Recent audio row. [item] null with total 0 means an empty list. */
    fun audioPage(index: Int, total: Int, item: AudioItem?, isNew: Boolean): PebbleDictionary = buildMap {
        put(Key.TYPE, u8(Protocol.Type.AUDIO))
        put(Key.INDEX, u8(index))
        put(Key.TOTAL, u8(total))
        if (item != null) {
            var flags = 0
            if (isNew) flags = flags or Protocol.AudioFlag.NEW
            if (item.telegram) flags = flags or Protocol.AudioFlag.TELEGRAM
            put(Key.SESSION_ID, text(item.sessionId, Protocol.SESSION_ID_BUDGET))
            put(Key.MESSAGE_ID, text(item.messageId, Protocol.MESSAGE_ID_BUDGET))
            put(Key.TITLE, text(item.sessionTitle, Protocol.TITLE_BUDGET))
            put(Key.TEXT, text(item.preview, Protocol.PREVIEW_BUDGET))
            put(Key.FLAGS, u8(flags))
            put(Key.TIME, PebbleDictionaryItem.UInt32(item.timestamp.toLong().coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt()))
        }
    }

    fun status(state: Protocol.Conn, detail: String): PebbleDictionary = mapOf(
        Key.TYPE to u8(Protocol.Type.STATUS),
        Key.STATE to u8(state.code),
        Key.DETAIL to text(detail, Protocol.DETAIL_BUDGET),
    )

    fun reply(sessionId: String, title: String, message: WatchMessage, textBudget: Int): PebbleDictionary {
        val (body, truncated) = Text.fit(message.text, textBudget)
        return mapOf(
            Key.TYPE to u8(Protocol.Type.REPLY),
            Key.SESSION_ID to text(sessionId, Protocol.SESSION_ID_BUDGET),
            Key.MESSAGE_ID to text(message.id, Protocol.MESSAGE_ID_BUDGET),
            Key.TITLE to text(title, Protocol.TITLE_BUDGET),
            Key.TEXT to PebbleDictionaryItem.Text(body),
            Key.FLAGS to u8(message.flags or if (truncated) Protocol.MessageFlag.TRUNCATED else 0),
        )
    }

    fun playback(messageId: String?, state: Protocol.Playback, detail: String): PebbleDictionary = mapOf(
        Key.TYPE to u8(Protocol.Type.PLAYBACK),
        Key.MESSAGE_ID to text(messageId.orEmpty(), Protocol.MESSAGE_ID_BUDGET),
        Key.STATE to u8(state.code),
        Key.DETAIL to text(detail, Protocol.DETAIL_BUDGET),
    )

    fun submit(sessionId: String?, state: Protocol.Submit, detail: String): PebbleDictionary = mapOf(
        Key.TYPE to u8(Protocol.Type.SUBMIT),
        Key.SESSION_ID to text(sessionId.orEmpty(), Protocol.SESSION_ID_BUDGET),
        Key.STATE to u8(state.code),
        Key.DETAIL to text(detail, Protocol.DETAIL_BUDGET),
    )

    fun sessionOpened(session: WatchSession): PebbleDictionary = mapOf(
        Key.TYPE to u8(Protocol.Type.SESSION_OPENED),
        Key.SESSION_ID to text(session.id, Protocol.SESSION_ID_BUDGET),
        Key.TITLE to text(session.title, Protocol.TITLE_BUDGET),
        Key.FLAGS to u8(session.flags),
    )

    private fun u8(v: Int) = PebbleDictionaryItem.UInt8(v.coerceIn(0, 255).toUByte())

    private fun text(v: String, budget: Int) = PebbleDictionaryItem.Text(Text.fit(v, budget).first)
}
