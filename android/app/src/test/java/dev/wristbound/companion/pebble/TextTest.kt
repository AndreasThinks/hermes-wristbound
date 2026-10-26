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
        assertEquals("Result\nDone see docs\n\n[image]\n[audio]", Text.forWatch(raw))
    }

    @Test
    fun listsKeepBulletsNumbersAndNesting() {
        val raw = "Shopping:\n- oat milk\n* eggs\n    + free range\n1. first\n2) second\n- [ ] call\n- [x] done"
        assertEquals(
            "Shopping:\n\u2022 oat milk\n\u2022 eggs\n    \u2022 free range\n1. first\n2. second\n[ ] call\n[x] done",
            Text.forWatch(raw),
        )
    }

    @Test
    fun singleEmphasisIsStrippedButIdentifiersAndMathAreNot() {
        assertEquals("really and also gone", Text.forWatch("*really* and _also_ ~~gone~~"))
        assertEquals("snake_case_name and 2*3*4", Text.forWatch("snake_case_name and 2*3*4"))
        assertEquals("a *literal* star", Text.forWatch("a \\*literal\\* star"))
    }

    @Test
    fun tablesBecomeRowsOfCells() {
        val raw = "| Day | Rain |\n|-----|:----:|\n| Mon | 20% |\n| **Tue** | 80% |"
        assertEquals("Day \u00B7 Rain\nMon \u00B7 20%\nTue \u00B7 80%", Text.forWatch(raw))
    }

    @Test
    fun quotesRulesAndHtmlAreFlattened() {
        val raw = "> quoted\n> > deeper\n\n---\n\nline<br>next <b>bold</b> &amp; done"
        assertEquals("quoted\ndeeper\n\nline\nnext bold & done", Text.forWatch(raw))
    }

    @Test
    fun codeBlocksKeepTheirLinesAsWritten() {
        val raw = "Run:\n```bash\n  ls -la *.kt \\.\n```\nthen **go**"
        assertEquals("Run:\n  ls -la *.kt \\.\nthen go", Text.forWatch(raw))
    }

    @Test
    fun urlsShrinkToTheirHost() {
        assertEquals(
            "See github.com/\u2026 or example.org and docs",
            Text.forWatch("See https://github.com/AndreasThinks/hermes-wristbound/pull/18 or <https://www.example.org/> and [docs](https://x.y)"),
        )
    }
}
