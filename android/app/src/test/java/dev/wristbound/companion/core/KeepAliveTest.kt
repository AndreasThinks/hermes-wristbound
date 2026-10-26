package dev.wristbound.companion.core

import org.junit.Assert.*
import org.junit.Test

class KeepAliveTest {
    @Test fun anyReasonKeepsTheServiceUp() {
        assertFalse(KeepAlive(checking = false, turns = 0, playing = false).needed)
        assertTrue(KeepAlive(checking = true, turns = 0, playing = false).needed)
        // A turn or playback outlives the watchapp even with background checking off.
        assertTrue(KeepAlive(checking = false, turns = 1, playing = false).needed)
        assertTrue(KeepAlive(checking = false, turns = 0, playing = true).needed)
    }

    @Test fun labelNamesTheMostPressingReason() {
        assertEquals("Playing a Hermes reply", KeepAlive(checking = true, turns = 2, playing = true).label)
        assertEquals("Waiting for Hermes to reply", KeepAlive(checking = true, turns = 1, playing = false).label)
        assertEquals("Watching Hermes for replies", KeepAlive(checking = true, turns = 0, playing = false).label)
    }

    @Test fun monitorUsesTheLoopWhileOpenAndAlarmsWhileClosed() {
        assertEquals(Monitor.OFF, Monitor.of(configured = false, watchOpen = true, backgroundSync = true))
        assertEquals(Monitor.LOOP, Monitor.of(configured = true, watchOpen = true, backgroundSync = false))
        assertEquals(Monitor.LOOP, Monitor.of(configured = true, watchOpen = true, backgroundSync = true))
        assertEquals(Monitor.ALARMS, Monitor.of(configured = true, watchOpen = false, backgroundSync = true))
        assertEquals(Monitor.OFF, Monitor.of(configured = true, watchOpen = false, backgroundSync = false))
    }
}
