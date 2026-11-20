package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.Gateway
import dev.wristbound.companion.hermes.GatewayClosedException
import dev.wristbound.companion.hermes.GatewayEvent
import dev.wristbound.companion.hermes.HermesHttpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeListenerTest {
    private class FakeSocket(var answersPing: Boolean = true) : Gateway {
        val channel = Channel<GatewayEvent>(Channel.UNLIMITED)
        var closed = false
        override val events: ReceiveChannel<GatewayEvent> get() = channel
        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long, onSent: () -> Unit): JsonObject {
            if (!answersPing || closed) throw GatewayClosedException("no answer")
            return JsonObject(emptyMap())
        }
        override fun answer(requestId: String, result: JsonObject) = true
        override fun decline(requestId: String, code: Int, message: String) = true
        override fun close() {
            closed = true
            channel.close(GatewayClosedException("closed"))
        }
        fun emit(type: String) = channel.trySend(GatewayEvent(type, null, JsonObject(emptyMap())))
    }

    private class Rig(scope: TestScope, val failures: MutableList<Exception> = mutableListOf()) {
        val sockets = mutableListOf<FakeSocket>()
        var checks = 0
        val states = mutableListOf<Live>()
        val listener = ChangeListener(
            scope.backgroundScope,
            open = {
                failures.removeFirstOrNull()?.let { throw it }
                FakeSocket().also { sockets += it }
            },
            onChange = { checks++ },
        ).apply { onStateChanged = { states += it } }
    }

    @Test
    fun aBurstOfChangesIsOneCheck() = runTest {
        val rig = Rig(this)
        rig.listener.start("client")
        runCurrent()
        assertEquals(Live.LIVE, rig.listener.state.value)
        advanceTimeBy(ChangeListener.DEBOUNCE_MS + 1)
        assertEquals("connecting catches up once", 1, rig.checks)

        repeat(5) { rig.sockets[0].emit(ChangeListener.SESSIONS_CHANGED); advanceTimeBy(300) }
        rig.sockets[0].emit("skin.changed")
        advanceTimeBy(ChangeListener.DEBOUNCE_MS + 1)
        assertEquals(2, rig.checks)
    }

    @Test
    fun aLostSocketGoesDownAndReconnects() = runTest {
        val rig = Rig(this)
        rig.listener.start("client")
        runCurrent()
        rig.sockets[0].close()
        runCurrent()
        assertEquals(Live.DOWN, rig.listener.state.value)
        advanceTimeBy(ChangeListener.backoff(0) + 1)
        assertEquals(Live.LIVE, rig.listener.state.value)
        assertEquals(2, rig.sockets.size)
        assertEquals(listOf(Live.CONNECTING, Live.LIVE, Live.DOWN, Live.CONNECTING, Live.LIVE), rig.states)
    }

    @Test
    fun anAlarmReopensASocketThatStoppedAnswering() = runTest {
        val rig = Rig(this)
        rig.listener.start("client")
        runCurrent()
        assertTrue(rig.listener.ensure())
        assertEquals(1, rig.sockets.size)

        rig.sockets[0].answersPing = false
        assertTrue(rig.listener.ensure())
        assertTrue(rig.sockets[0].closed)
        assertEquals(2, rig.sockets.size)
    }

    @Test
    fun anAlarmRetriesWithoutWaitingOutTheBackoff() = runTest {
        val rig = Rig(this, mutableListOf(IOException("offline")))
        rig.listener.start("client")
        runCurrent()
        assertEquals(Live.DOWN, rig.listener.state.value)
        // The phone slept through the backoff; the alarm should not have to wait for it.
        assertTrue(rig.listener.ensure())
        assertEquals(Live.LIVE, rig.listener.state.value)
    }

    @Test
    fun aRejectedSignInStopsRetryingUntilANewSignIn() = runTest {
        val rig = Rig(this, mutableListOf(HermesHttpException(401, "unauthorized", "no")))
        rig.listener.start("old")
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        assertEquals(Live.DOWN, rig.listener.state.value)
        assertTrue(rig.sockets.isEmpty())

        rig.listener.start("new")
        runCurrent()
        assertEquals(Live.LIVE, rig.listener.state.value)
    }

    @Test
    fun theSameClientKeepsItsSocketAndStopClosesIt() = runTest {
        val rig = Rig(this)
        rig.listener.start("client")
        runCurrent()
        rig.listener.start("client")
        runCurrent()
        assertEquals(1, rig.sockets.size)
        rig.listener.stop()
        runCurrent()
        assertTrue(rig.sockets[0].closed)
        assertEquals(Live.OFF, rig.listener.state.value)
        assertFalse(rig.listener.ensure())
    }
}
