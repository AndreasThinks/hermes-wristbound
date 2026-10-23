package dev.wristbound.companion.pebble

import dev.wristbound.companion.core.WatchMessage
import dev.wristbound.companion.pebble.Protocol.Key
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchCodecTest {
    private fun u8(v: Int) = PebbleDictionaryItem.UInt8(v.toUByte())
    private fun t(v: String) = PebbleDictionaryItem.Text(v)

    @Test
    fun decodesHello() {
        val cmd = WatchCodec.decode(
            mapOf(Key.CMD to u8(1), Key.TEXT_BUDGET to PebbleDictionaryItem.UInt16(1200.toUShort()),
                Key.PROTO_VERSION to u8(5), Key.STATE to u8(4), Key.SESSION_ID to t("s1")),
        )
        assertEquals(WatchCommand.Hello(1200, 5, 4, "s1"), cmd)
        // An older watch sends no Quick launch, and an unknown one is ignored.
        assertEquals(WatchCommand.Hello(320, 4, null, null), WatchCodec.decode(mapOf(Key.CMD to u8(1), Key.PROTO_VERSION to u8(4))))
        assertEquals(null, (WatchCodec.decode(mapOf(Key.CMD to u8(1), Key.STATE to u8(9))) as WatchCommand.Hello).quickLaunch)
    }

    @Test
    fun approvalsBothWays() {
        val hello = WatchCodec.decode(mapOf(Key.CMD to u8(1), Key.FLAGS to u8(Protocol.HelloFlag.APPROVALS))) as WatchCommand.Hello
        assertEquals(true, hello.approvals)
        assertEquals(false, (WatchCodec.decode(mapOf(Key.CMD to u8(1))) as WatchCommand.Hello).approvals)
        assertEquals(
            WatchCommand.Approve("s1", "srq-1", "deny"),
            WatchCodec.decode(mapOf(Key.CMD to u8(16), Key.SESSION_ID to t("s1"), Key.MESSAGE_ID to t("srq-1"), Key.STATE to u8(3))),
        )
        assertNull(WatchCodec.decode(mapOf(Key.CMD to u8(16), Key.MESSAGE_ID to t("srq-1"), Key.STATE to u8(9))))
        val msg = WatchCodec.approval("s1", "srq-1", "Repo", "rm -rf build", listOf("once", "deny"), closed = false, textBudget = 1200)
        assertEquals(Protocol.Type.APPROVAL, (msg[Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals(0b1001, (msg[Key.FLAGS] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals(0, (msg[Key.STATE] as PebbleDictionaryItem.UInt8).value.toInt())
    }

    @Test
    fun encodesStreamAndConfig() {
        val stream = WatchCodec.stream("s1", "Hello there, this is long", 12)
        assertEquals(Protocol.Type.STREAM, (stream[Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals("s1", (stream[Key.SESSION_ID] as PebbleDictionaryItem.Text).value)
        assertEquals(true, (stream[Key.TEXT] as PebbleDictionaryItem.Text).value.encodeToByteArray().size <= 12)
        val config = WatchCodec.config(Protocol.QuickLaunch.RECENT_AUDIO)
        assertEquals(Protocol.Type.CONFIG, (config[Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals(Protocol.QuickLaunch.RECENT_AUDIO, (config[Key.STATE] as PebbleDictionaryItem.UInt8).value.toInt())
    }

    @Test
    fun decodesSendAndRejectsBlank() {
        assertEquals(
            WatchCommand.Send("s1", "Yes"),
            WatchCodec.decode(mapOf(Key.CMD to u8(4), Key.SESSION_ID to t("s1"), Key.TEXT to t("Yes"))),
        )
        assertNull(WatchCodec.decode(mapOf(Key.CMD to u8(4), Key.SESSION_ID to t("s1"), Key.TEXT to t("  "))))
    }

    @Test
    fun decodesPlayRoute() {
        assertEquals(
            WatchCommand.Play("s1", "7", speaker = true),
            WatchCodec.decode(mapOf(Key.CMD to u8(6), Key.SESSION_ID to t("s1"), Key.MESSAGE_ID to t("7"), Key.ROUTE to u8(1))),
        )
        assertEquals(
            WatchCommand.Replay("s1", "7", speaker = false),
            WatchCodec.decode(mapOf(Key.CMD to u8(9), Key.SESSION_ID to t("s1"), Key.MESSAGE_ID to t("7"), Key.ROUTE to u8(0))),
        )
        assertEquals(WatchCommand.Resume(speaker = true), WatchCodec.decode(mapOf(Key.CMD to u8(8), Key.ROUTE to u8(1))))
        // An older watch sends no route: never assume the speaker is allowed.
        assertEquals(WatchCommand.Resume(speaker = false), WatchCodec.decode(mapOf(Key.CMD to u8(8))))
    }

    @Test
    fun encodesAudioRoute() {
        val msg = WatchCodec.audioRoute(headphones = true)
        assertEquals(Protocol.Type.AUDIO_ROUTE, (msg[Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals(1, (msg[Key.STATE] as PebbleDictionaryItem.UInt8).value.toInt())
    }

    @Test
    fun decodesRecentAudioCommands() {
        assertEquals(WatchCommand.ListAudio, WatchCodec.decode(mapOf(Key.CMD to u8(11))))
        assertEquals(
            WatchCommand.OpenReply("s1", "7"),
            WatchCodec.decode(mapOf(Key.CMD to u8(12), Key.SESSION_ID to t("s1"), Key.MESSAGE_ID to t("7"))),
        )
        assertNull(WatchCodec.decode(mapOf(Key.CMD to u8(12), Key.SESSION_ID to t("s1"))))
    }

    @Test
    fun decodesWatchSpeakerCommands() {
        fun u32(v: Int) = PebbleDictionaryItem.UInt32(v.toUInt())
        assertEquals(
            WatchCommand.PlayOnWatch("s1", "7", 4000),
            WatchCodec.decode(mapOf(Key.CMD to u8(13), Key.SESSION_ID to t("s1"), Key.MESSAGE_ID to t("7"),
                Key.TEXT_BUDGET to PebbleDictionaryItem.UInt16(4000.toUShort()))),
        )
        assertEquals(
            WatchCommand.AudioPull("7", 8192, 40960),
            WatchCodec.decode(mapOf(Key.CMD to u8(14), Key.MESSAGE_ID to t("7"), Key.OFFSET to u32(8192), Key.LENGTH to u32(40960))),
        )
        assertNull(WatchCodec.decode(mapOf(Key.CMD to u8(14), Key.MESSAGE_ID to t("7"))))
        assertEquals(WatchCommand.AudioStop("7"), WatchCodec.decode(mapOf(Key.CMD to u8(15), Key.MESSAGE_ID to t("7"))))
    }

    @Test
    fun audioPageCarriesTheReplyAndItsTime() {
        val item = dev.wristbound.companion.core.AudioItem("s1", "7", "Groceries", "Here is the list", 1_792_700_000.5, telegram = true)
        val page = WatchCodec.audioPage(2, 5, item, isNew = true)
        assertEquals(Protocol.Type.AUDIO, (page[Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals("7", (page[Key.MESSAGE_ID] as PebbleDictionaryItem.Text).value)
        assertEquals(Protocol.AudioFlag.NEW or Protocol.AudioFlag.TELEGRAM, (page[Key.FLAGS] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals(1_792_700_000u, (page[Key.TIME] as PebbleDictionaryItem.UInt32).value)
        val empty = WatchCodec.audioPage(0, 0, null, false)
        assertNull(empty[Key.MESSAGE_ID])
    }

    @Test
    fun unknownCommandIsIgnored() {
        assertNull(WatchCodec.decode(mapOf(Key.CMD to u8(99))))
    }

    @Test
    fun messagePageTruncatesAndFlags() {
        val page = WatchCodec.messagePage("s1", 0, 1, WatchMessage("7", Protocol.Role.ASSISTANT, 0, "x".repeat(500)), 100)
        val text = (page[Key.TEXT] as PebbleDictionaryItem.Text).value
        assertEquals(99, Text.utf8Len(text))
        assertEquals(Protocol.MessageFlag.TRUNCATED, (page[Key.FLAGS] as PebbleDictionaryItem.UInt8).value.toInt())
        assertEquals(Protocol.Type.MESSAGE, (page[Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt())
    }

    @Test
    fun messageKeysMatchWatchPackageOrder() {
        // watch/package.json messageKeys, in order; the SDK numbers them from 10000.
        val order = listOf("Cmd", "Type", "ProtoVersion", "TextBudget", "SessionId", "Title", "Flags", "Index",
            "Total", "MessageId", "Role", "Text", "State", "Detail", "Filter", "Route", "Time", "Data", "Offset", "Length")
        val keys = listOf(Key.CMD, Key.TYPE, Key.PROTO_VERSION, Key.TEXT_BUDGET, Key.SESSION_ID, Key.TITLE, Key.FLAGS,
            Key.INDEX, Key.TOTAL, Key.MESSAGE_ID, Key.ROLE, Key.TEXT, Key.STATE, Key.DETAIL, Key.FILTER, Key.ROUTE, Key.TIME, Key.DATA, Key.OFFSET, Key.LENGTH)
        // Gradle runs unit tests from the module directory (android/app).
        val pkg = java.io.File("../../watch/package.json").readText()
        val declared = Regex(""""messageKeys":\s*\[([^\]]*)]""").find(pkg)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertEquals(order, declared)
        keys.forEachIndexed { i, k -> assertEquals(10000u + i.toUInt(), k) }
    }
}
