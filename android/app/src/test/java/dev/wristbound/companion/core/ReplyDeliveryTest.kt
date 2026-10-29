package dev.wristbound.companion.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReplyDeliveryTest {
    @Test fun closedWatchLaunchesBeforeSendingWithoutNativeDuplicate() = runTest {
        var launches = 0
        var native = 0
        var sent = 0
        val delivery = ReplyDelivery({ false }, { sent++; true }, {}, { native++; true },
            prepareWatch = { launches++; true })
        assertTrue(delivery.deliver(ReplyAlert("s", "Chat", "1", "Hi", true, false)))
        assertEquals(1, launches)
        assertEquals(1, sent)
        assertEquals(0, native)
    }
    @Test fun failedLaunchFallsBackWithoutSending() = runTest {
        var sent = 0
        var native = 0
        val delivery = ReplyDelivery({ false }, { sent++; true }, {}, { native++; true },
            prepareWatch = { false })
        assertTrue(delivery.deliver(ReplyAlert("s", "Chat", "1", "Hi", true, false)))
        assertEquals(0, sent)
        assertEquals(1, native)
    }
    @Test fun silentUpdateNeverLaunchesWatch() = runTest {
        var launches = 0
        val delivery = ReplyDelivery({ false }, { fail("silent watch send"); false }, {}, { true },
            prepareWatch = { launches++; true })
        assertTrue(delivery.deliver(ReplyAlert("s", "Chat", "1", "Hi", true, true)))
        assertEquals(0, launches)
    }
    @Test fun openWatchNeverLaunchesAgain() = runTest {
        val delivery = ReplyDelivery({ true }, { true }, {}, { fail("duplicate"); false },
            prepareWatch = { fail("already open"); false })
        assertTrue(delivery.deliver(ReplyAlert("s", "Chat", "1", "Hi", true, false)))
    }
    @Test fun launchedWatchMissingAckFallsBack() = runTest {
        var closed = false
        var native = 0
        val delivery = ReplyDelivery({ false }, { false }, { closed = true }, { native++; true },
            prepareWatch = { true })
        assertTrue(delivery.deliver(ReplyAlert("s", "Chat", "1", "Hi", true, false)))
        assertTrue(closed)
        assertEquals(1, native)
    }
    @Test fun watchAckOrNativeFallbackButNeverBothSuccessfulDeliveries() = runTest {
        for (open in listOf(false, true)) for (session in listOf("open-session", "other-session")) {
            var watchOpen = open
            var watchCalls = 0
            var nativeCalls = 0
            val delivery = ReplyDelivery({ watchOpen }, { watchCalls++; true }, { watchOpen = false }, { nativeCalls++; true })
            assertTrue(delivery.deliver(ReplyAlert(session, "Chat", "1", "Hi", true, false)))
            assertEquals(if (open) 1 else 0, watchCalls)
            assertEquals(if (open) 0 else 1, nativeCalls)
        }
    }
    @Test fun failedAckFallsBackAndFailedSilentUpdateRemainsRetryable() = runTest {
        var open = true
        var calls = 0
        val delivery = ReplyDelivery({ open }, { false }, { open = false }, { calls++; false })
        val alert = ReplyAlert("s", "Chat", "1", "Hi", true, false)
        assertFalse(delivery.deliver(alert))
        assertFalse(open)
        assertEquals(1, calls)
        assertFalse(delivery.deliver(alert.copy(silent = true)))
        assertEquals(2, calls)
    }
}
