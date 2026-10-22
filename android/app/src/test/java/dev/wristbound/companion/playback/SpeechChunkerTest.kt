package dev.wristbound.companion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test
    fun splitsOnSentencesAndLines() {
        assertEquals(listOf("One.", "Two?", "Three", "Four!"), SpeechChunker.chunks("One. Two?\nThree\n\nFour!"))
    }

    @Test
    fun splitsVeryLongSentencesOnWords() {
        val long = (1..200).joinToString(" ") { "word$it" }
        val chunks = SpeechChunker.chunks(long)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 400 })
        assertEquals(long, chunks.joinToString(" "))
    }
}
