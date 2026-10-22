package dev.wristbound.companion.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun parseAll(text: String): List<SseEvent> {
        val parser = SseParser()
        val out = text.split("\n").mapNotNull { parser.feed(it) }.toMutableList()
        parser.flush()?.let { out += it }
        return out
    }

    @Test
    fun parsesNamedEventsAndSkipsKeepalives() {
        val events = parseAll(
            "event: run.started\ndata: {\"run_id\":\"run_1\"}\n\n: keepalive\n\nevent: assistant.delta\ndata: {\"delta\":\"Hi\"}\n\n",
        )
        assertEquals(listOf("run.started", "assistant.delta"), events.map { it.name })
        assertEquals("{\"run_id\":\"run_1\"}", events[0].data)
    }

    @Test
    fun joinsMultilineDataAndDefaultsName() {
        val events = parseAll("data: a\ndata: b\n\n")
        assertEquals(1, events.size)
        assertEquals("message", events[0].name)
        assertEquals("a\nb", events[0].data)
    }

    @Test
    fun flushesTrailingEventWithoutBlankLine() {
        val parser = SseParser()
        assertNull(parser.feed("event: done"))
        assertNull(parser.feed("data: {}"))
        assertEquals("done", parser.flush()?.name)
    }
}
