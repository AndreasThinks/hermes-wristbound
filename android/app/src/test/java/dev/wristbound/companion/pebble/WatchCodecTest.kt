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
                Key.PROTO_VERSION to u8(1), Key.FILTER to u8(0), Key.SESSION_ID to t("s1")),
        )
        assertEquals(WatchCommand.Hello(1200, 1, false, "s1"), cmd)
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
            "Total", "MessageId", "Role", "Text", "State", "Detail", "Filter", "Route")
        val keys = listOf(Key.CMD, Key.TYPE, Key.PROTO_VERSION, Key.TEXT_BUDGET, Key.SESSION_ID, Key.TITLE, Key.FLAGS,
            Key.INDEX, Key.TOTAL, Key.MESSAGE_ID, Key.ROLE, Key.TEXT, Key.STATE, Key.DETAIL, Key.FILTER, Key.ROUTE)
        // Gradle runs unit tests from the module directory (android/app).
        val pkg = java.io.File("../../watch/package.json").readText()
        val declared = Regex(""""messageKeys":\s*\[([^\]]*)]""").find(pkg)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertEquals(order, declared)
        keys.forEachIndexed { i, k -> assertEquals(10000u + i.toUInt(), k) }
    }
}
