package dev.wristbound.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoicesTest {
    private val defaults = AlertPolicy(enabled = true, audio = true, watchTurns = true, chats = true, messagingText = false, background = false)

    @Test
    fun todaysDefaultsAreNormal() = assertEquals(AlertPreset.NORMAL, AlertPreset.of(defaults))

    @Test
    fun everyPresetReadsBackAsItself() {
        for (preset in listOf(AlertPreset.QUIET, AlertPreset.NORMAL, AlertPreset.ALL)) {
            assertEquals(preset, AlertPreset.of(preset.policy(defaults)!!))
        }
    }

    @Test
    fun offKeepsTheKindsSoTurningBackOnRestoresThem() {
        val custom = defaults.copy(background = true, chats = false)
        val off = AlertPreset.OFF.policy(custom)!!
        assertFalse(off.enabled)
        assertEquals(AlertPreset.OFF, AlertPreset.of(off))
        assertEquals(custom, off.copy(enabled = true))
    }

    @Test
    fun aHandPickedMixIsCustomAndCustomChangesNothing() {
        val mix = defaults.copy(background = true)
        assertEquals(AlertPreset.CUSTOM, AlertPreset.of(mix))
        assertEquals(null, AlertPreset.CUSTOM.policy(mix))
        assertEquals(AlertPreset.CUSTOM, AlertPreset.of(AlertKind.CHATS.set(defaults, false).copy(messagingText = true)))
    }

    @Test
    fun eachKindReadsAndWritesItsOwnSwitch() {
        val none = AlertPolicy(enabled = true, audio = false, watchTurns = false, chats = false, messagingText = false, background = false)
        for (kind in AlertKind.entries) {
            val on = kind.set(none, true)
            assertTrue(kind.isOn(on))
            assertEquals(1, AlertKind.entries.count { it.isOn(on) })
        }
    }

    @Test
    fun replySpeedReadsOldIntervalsAsTheNearestChoice() {
        assertEquals(ReplySpeed.WATCH_ONLY, ReplySpeed.of(background = false, seconds = 30))
        assertEquals(ReplySpeed.FAST, ReplySpeed.of(true, 30))
        assertEquals(ReplySpeed.NORMAL, ReplySpeed.of(true, 60))
        assertEquals(ReplySpeed.NORMAL, ReplySpeed.of(true, 120))
        assertEquals(ReplySpeed.SAVER, ReplySpeed.of(true, 300))
        assertEquals("Normal · within ~1 min", ReplySpeed.NORMAL.title)
        assertEquals("~30 s", ReplySpeed.within(30))
        assertEquals("~2 min", ReplySpeed.within(120))
    }

    @Test
    fun messagingChoiceStandsForListingAndReplies() {
        assertEquals(MessagingChats.HIDDEN, MessagingChats.of(listed = false, replies = true))
        assertEquals(MessagingChats.READ_ONLY, MessagingChats.of(listed = true, replies = false))
        assertEquals(MessagingChats.REPLY, MessagingChats.of(listed = true, replies = true))
        for (c in MessagingChats.entries) assertEquals(c, MessagingChats.of(c.listed, c.replies))
    }
}
