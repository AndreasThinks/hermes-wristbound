package dev.wristbound.companion.notify

import org.junit.Assert.*
import org.junit.Test

class ReplyNoticeTest {
    @Test fun cleanConversationAndPrivatePreview() {
        val n = ReplyNotice.create("s", "m", "Weather", "**Sunny** MEDIA:/secret/voice.ogg [audio]", false, true)
        assertEquals("Weather", n.title)
        assertEquals("Sunny", n.body)
        assertEquals("Hermes", n.sender)
        assertEquals(listOf("Play audio", "Play on phone speaker", "Open in Wristbound"), n.actions.map { it.label })
        assertTrue(n.actions.all { !it.showsUserInterface })
        val hidden = ReplyNotice.create("s", "m", "Secret title", "secret text", true, true)
        assertEquals("Hermes", hidden.title)
        assertEquals("New audio reply", hidden.body)
        assertEquals(1, ReplyNotice.create("s", "m", "Chat", "Hello", false, false).actions.size)
    }

    @Test fun identitiesIncludeSessionAndActionWithoutConcatenationCollisions() {
        val a = ReplyNotice.create("ab", "c", "", "", false, true)
        val b = ReplyNotice.create("a", "bc", "", "", false, true)
        assertNotEquals(a.key, b.key)
        assertEquals(a.actions.size, a.actions.map { it.identity(a.key) }.toSet().size)
    }
}
