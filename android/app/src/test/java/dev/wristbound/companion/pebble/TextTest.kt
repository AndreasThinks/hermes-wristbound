package dev.wristbound.companion.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test
    fun shortTextIsUntouched() {
        assertEquals("hello" to false, Text.fit("hello", 10))
    }

    @Test
    fun truncatesWithinByteBudgetWithoutSplittingCodePoints() {
        val (out, truncated) = Text.fit("héllo wörld 🎉🎉🎉", 16)
        assertTrue(truncated)
        assertTrue(Text.utf8Len(out) <= 15)
        assertTrue(out.endsWith("…"))
        // Round-trips as valid UTF-8.
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun emojiIsNotCutInHalf() {
        val (out, _) = Text.fit("🎉🎉🎉🎉", 9)
        assertFalse(out.contains('�'))
        assertEquals("🎉…", out)
    }

    @Test
    fun stripsMarkdownAndMediaTags() {
        val raw = "## Result\n**Done** see [docs](https://x.y)\n\n\n\n![chart](data:image/png;base64,AAA)\nMEDIA:/tmp/a.mp3"
        assertEquals("Result\nDone see docs\n\n[image]\n[media]", Text.forWatch(raw))
    }
}
