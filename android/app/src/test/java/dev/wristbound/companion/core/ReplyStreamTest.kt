package dev.wristbound.companion.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyStreamTest {
    @Test
    fun sendsTheFirstChunkAtOnceThenAtMostOncePerInterval() = runTest {
        val sent = mutableListOf<String>()
        val stream = ReplyStream(backgroundScope, intervalMs = 700) { sent += it }
        stream.delta("Hel")
        runCurrent()
        assertEquals(listOf("Hel"), sent)
        stream.delta("lo")
        stream.delta(" there")
        advanceTimeBy(300)
        assertEquals(listOf("Hel"), sent)
        advanceTimeBy(500)
        assertEquals(listOf("Hel", "Hello there"), sent)
        stream.delta("!")
        stream.finish()
        advanceTimeBy(2000)
        assertEquals(listOf("Hel", "Hello there"), sent)
    }

    @Test
    fun aNewSegmentReplacesTheTextBeforeIt() = runTest {
        val sent = mutableListOf<String>()
        val stream = ReplyStream(backgroundScope, intervalMs = 700) { sent += it }
        stream.delta("Checking the calendar")
        stream.delta(null)
        runCurrent()
        stream.delta(null)
        stream.delta("You are free at 4.")
        advanceTimeBy(800)
        assertEquals(listOf("Checking the calendar", "You are free at 4."), sent)
    }
}
