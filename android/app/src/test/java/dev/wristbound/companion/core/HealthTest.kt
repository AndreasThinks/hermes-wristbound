package dev.wristbound.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val now = 10_000_000L

    private fun health(
        signedIn: Boolean = true,
        backend: BackendState = BackendState(BackendLink.CONNECTED),
        canNotify: Boolean = true,
        batteryExempt: Boolean = true,
        speed: ReplySpeed = ReplySpeed.NORMAL,
        interval: Int = 60,
        watchOpen: Boolean = false,
        lastCheckAt: Long = now - 40_000L,
    ) = Health.of(signedIn, backend, canNotify, batteryExempt, speed, interval, watchOpen, lastCheckAt, now)

    @Test
    fun allGoodSaysHowLateARepliesCanBe() {
        val h = health()
        assertTrue(h.ok)
        assertEquals("All good. Replies reach your wrist within ~1 min.", h.headline)
        assertEquals("Last reached Hermes 40 s ago · watchapp closed", h.detail)
        assertEquals(LinkState.UNKNOWN, h.watch)
        assertEquals(LinkState.OK, health(watchOpen = true).watch)
    }

    @Test
    fun theFirstBrokenLinkWins() {
        assertEquals(HealthFix.SIGN_IN, health(signedIn = false, canNotify = false).fix)
        assertEquals(HealthFix.SIGN_IN_AGAIN, health(backend = BackendState(BackendLink.SIGN_IN_REQUIRED)).fix)
        val offline = health(backend = BackendState(BackendLink.OFFLINE), canNotify = false)
        assertEquals(HealthFix.RETRY, offline.fix)
        assertEquals(LinkState.PROBLEM, offline.hermes)
        val blocked = health(canNotify = false, batteryExempt = false)
        assertEquals(HealthFix.NOTIFICATIONS, blocked.fix)
        assertEquals(LinkState.PROBLEM, blocked.phone)
        assertEquals(HealthFix.BATTERY, health(batteryExempt = false).fix)
    }

    @Test
    fun batteryOnlyMattersWhenCheckingInTheBackground() {
        val h = health(batteryExempt = false, speed = ReplySpeed.WATCH_ONLY)
        assertTrue(h.ok)
        assertEquals("Replies wait until you open Wristbound on the watch", h.headline)
    }

    @Test
    fun checksThatStoppedAreCalledLate() {
        assertTrue(health(lastCheckAt = now - 200_000L).ok)
        val late = health(lastCheckAt = now - 20 * 60_000L)
        assertEquals(HealthFix.RETRY, late.fix)
        assertEquals("Checks are running late", late.headline)
        // Not checking at all is a choice, not a fault.
        assertTrue(health(speed = ReplySpeed.WATCH_ONLY, lastCheckAt = now - 86_400_000L).ok)
    }

    @Test
    fun agoReadsNaturally() {
        assertEquals("just now", Health.ago(3_000))
        assertEquals("40 s ago", Health.ago(40_000))
        assertEquals("3 min ago", Health.ago(200_000))
        assertEquals("2 h ago", Health.ago(7_500_000))
        assertEquals("3 d ago", Health.ago(260_000_000))
    }
}
