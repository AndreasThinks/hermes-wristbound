package dev.wristbound.companion.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReplyDeliveryTest {
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
