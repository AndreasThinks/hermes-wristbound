package dev.wristbound.companion.notify

import org.junit.Assert.*
import org.junit.Test

class ReplyNoticeTest {
    @Test fun audioPreviewIsBrandedAndAvailabilityComesBeforeReplyText() {
        val n = ReplyNotice.create("s", "m", "Weather", "**Sunny** MEDIA:/secret/voice.ogg [audio]", false, true)
        assertEquals("Wristbound · Weather", n.title)
        assertEquals("Audio reply available\nSunny", n.body)
        assertEquals("Wristbound", n.sender)
        assertEquals(listOf("Play audio", "Play on phone speaker", "Open in Wristbound"), n.actions.map { it.label })
        assertTrue(n.actions.all { !it.showsUserInterface })
    }

    @Test fun privateAudioHidesConversationAndReplyButKeepsAvailability() {
        val hidden = ReplyNotice.create("s", "m", "Secret title", "secret text", true, true)
        assertEquals("Wristbound", hidden.title)
        assertEquals("Audio reply available", hidden.body)
        assertEquals("Wristbound", hidden.sender)
        assertEquals("Play audio", hidden.actions.first().label)
    }

    @Test fun textOnlyPreviewDoesNotAdvertiseAudioOrOfferPlayback() {
        val n = ReplyNotice.create("s", "m", "Chat", "Hello", false, false)
        assertEquals("Wristbound · Chat", n.title)
        assertEquals("Hello", n.body)
        assertEquals(listOf("Open in Wristbound"), n.actions.map { it.label })
        assertEquals("New reply", ReplyNotice.create("s", "m", "Secret", "hidden", true, false).body)
    }

    @Test fun emptyAndMediaOnlyAudioRepliesStillHaveClearAvailability() {
        for (text in listOf("", "   ", "MEDIA:/secret/voice.ogg [audio]")) {
            val n = ReplyNotice.create("s", "m", "", text, false, true)
            assertEquals("Wristbound", n.title)
            assertEquals("Audio reply available", n.body)
        }
        assertEquals("New reply", ReplyNotice.create("s", "m", "", "", false, false).body)
    }

    @Test fun blankOrAlreadyBrandedConversationDoesNotDuplicateBrand() {
        for (title in listOf("", "   ", "Wristbound")) {
            assertEquals("Wristbound", ReplyNotice.create("s", "m", title, "reply", false, true).title)
        }
    }

    @Test fun lateAudioKeepsNotificationIdentityAndConversationGrouping() {
        val text = ReplyNotice.create("s", "m", "Chat", "Hello", false, false)
        val audio = ReplyNotice.create("s", "m", "Chat", "Hello", false, true)
        assertEquals(text.key, audio.key)
        assertEquals(text.title, audio.title)
        assertEquals(text.sender, audio.sender)
        assertEquals("Audio reply available\nHello", audio.body)
    }

    @Test fun identitiesIncludeSessionAndActionWithoutConcatenationCollisions() {
        val a = ReplyNotice.create("ab", "c", "", "", false, true)
        val b = ReplyNotice.create("a", "bc", "", "", false, true)
        assertNotEquals(a.key, b.key)
        assertEquals(a.actions.size, a.actions.map { it.identity(a.key) }.toSet().size)
    }
}
