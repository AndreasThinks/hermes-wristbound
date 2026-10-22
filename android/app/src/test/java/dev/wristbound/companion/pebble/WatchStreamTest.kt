package dev.wristbound.companion.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStreamTest {
    private val sent = mutableListOf<PebbleDictionary>()
    private var acceptUntil = Int.MAX_VALUE
    private val ended = mutableListOf<Pair<String, String>>()

    private fun TestScope.stream() = WatchStream(this, { d -> (sent.size < acceptUntil).also { if (it) sent += d } }) { id, why -> ended += id to why }

    private val PebbleDictionary.type get() = (this[Protocol.Key.TYPE] as PebbleDictionaryItem.UInt8).value.toInt()
    private val PebbleDictionary.offset get() = (this[Protocol.Key.OFFSET] as PebbleDictionaryItem.UInt32).value.toInt()
    private val PebbleDictionary.data get() = (this[Protocol.Key.DATA] as PebbleDictionaryItem.Bytes).value
    private fun chunks() = sent.filter { it.type == Protocol.Type.AUDIO_DATA }

    private val pcm = ByteArray(1000) { it.toByte() }

    @Test
    fun sendsOnlyWhatTheWatchHasRoomFor() = runTest(StandardTestDispatcher()) {
        val s = stream()
        s.start("m1", pcm, chunk = 300, label = "Hermes audio")
        assertEquals(Protocol.Type.AUDIO_START, sent.single().type)
        assertEquals(1000u, (sent.single()[Protocol.Key.LENGTH] as PebbleDictionaryItem.UInt32).value)
        assertEquals(emptyList<PebbleDictionary>(), chunks()) // nothing before a pull

        s.pull("m1", 0, 400)
        advanceUntilIdle()
        assertEquals(listOf(0, 300), chunks().map { it.offset })
        assertEquals(listOf(300, 100), chunks().map { it.data.size })

        // The watch played some and has room again; in-flight data is never resent.
        s.pull("m1", 400, 1000)
        advanceUntilIdle()
        assertEquals(listOf(0, 300, 400, 700), chunks().map { it.offset })
        assertArrayEquals(pcm, chunks().map { it.data }.reduce { a, b -> a + b })
    }

    @Test
    fun pullsForAnotherReplyAreIgnored() = runTest(StandardTestDispatcher()) {
        val s = stream()
        s.start("m1", pcm, 300, "x")
        s.pull("other", 0, 1000)
        advanceUntilIdle()
        assertEquals(emptyList<PebbleDictionary>(), chunks())
    }

    @Test
    fun aChunkTheWatchRefusesEndsTheStream() = runTest(StandardTestDispatcher()) {
        val s = stream()
        s.start("m1", pcm, 300, "x")
        acceptUntil = 2 // header and one chunk
        s.pull("m1", 0, 1000)
        advanceUntilIdle()
        assertEquals(1, chunks().size)
        assertEquals(listOf("m1" to "Watch stopped receiving"), ended)
        assertNull(s.activeMessageId)
    }

    @Test
    fun stopAndANewClipCancelTheOldOne() = runTest(StandardTestDispatcher()) {
        val s = stream()
        s.start("m1", pcm, 300, "x")
        s.start("m2", pcm, 300, "x")
        s.pull("m1", 0, 1000)
        advanceUntilIdle()
        assertEquals(emptyList<PebbleDictionary>(), chunks())
        s.stop("m2")
        assertNull(s.activeMessageId)
        assertEquals(listOf("m2" to "Stopped"), ended)
    }

    @Test
    fun replayStartsAgainFromTheBeginning() = runTest(StandardTestDispatcher()) {
        val s = stream()
        s.start("m1", pcm, 500, "x")
        s.pull("m1", 0, 1000)
        advanceUntilIdle()
        s.start("m1", pcm, 500, "x")
        s.pull("m1", 0, 500)
        advanceUntilIdle()
        assertEquals(listOf(0, 500, 0), chunks().map { it.offset })
    }
}
