package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesHttp
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInboxTest {
    private fun msg(json: String) = HermesHttp.json.decodeFromString(HermesMessage.serializer(), json)
    private fun user(id: Int, ts: Double) = msg("""{"id":$id,"role":"user","content":"hi","timestamp":$ts}""")
    private fun voiceNote(id: Int, ts: Double) =
        msg("""{"id":$id,"role":"user","timestamp":$ts,"content":"[The user sent a voice message: /home/h/.hermes/cache/audio/audio_0123456789ab.ogg]"}""")
    private fun answer(id: Int, ts: Double, text: String = "Done.") = msg("""{"id":$id,"role":"assistant","content":"$text","timestamp":$ts}""")
    private fun speech(id: Int, ts: Double) = msg(
        """{"id":$id,"role":"tool","tool_name":"text_to_speech","timestamp":$ts,"content":"{\"media_tag\": \"MEDIA:/home/h/.hermes/cache/audio/tts_$id.ogg\"}"}""",
    )
    private fun lateAudio(id: Int, ts: Double) =
        msg("""{"id":$id,"role":"assistant","timestamp":$ts,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_$id.ogg"}""")

    private val tg = HermesSession("tg", source = "telegram", title = "Groceries", last_active = 30.0)
    private val cli = HermesSession("cli", source = "cli", title = "Work", last_active = 40.0)

    @Test
    fun onlyAssistantRepliesWithHermesAudioAreListed() {
        val items = AudioInbox.collect(tg, listOf(
            voiceNote(1, 1.0), answer(2, 2.0, "Text only"),
            user(3, 3.0), speech(4, 4.0), answer(5, 5.0, "Spoken\nreply"),
        ))
        assertEquals(listOf("5"), items.map { it.messageId })
        assertEquals("Spoken reply", items.single().preview)
        assertEquals("Groceries", items.single().sessionTitle)
        assertTrue(items.single().telegram)
    }

    @Test
    fun theUsersOwnVoiceNoteIsNeverAnItem() {
        val items = AudioInbox.collect(tg, listOf(voiceNote(1, 1.0), answer(2, 2.0, "I heard: audio_0123456789ab.ogg MEDIA:/home/h/.hermes/cache/audio/audio_0123456789ab.ogg")))
        assertEquals(emptyList<AudioItem>(), items)
    }

    @Test
    fun spansSessionsNewestFirstWithoutDuplicates() {
        val a = AudioInbox.collect(tg, listOf(user(1, 1.0), speech(2, 2.0), answer(3, 3.0)))
        val b = AudioInbox.collect(cli, listOf(user(1, 4.0), speech(2, 5.0), answer(30, 6.0)))
        val merged = AudioInbox.merge(a, b + a)
        assertEquals(listOf("30", "3"), merged.map { it.messageId })
        assertEquals(listOf("cli", "tg"), merged.map { it.sessionId })
    }

    @Test
    fun lateAudioJoinsItsReplyOnce() {
        val before = AudioInbox.collect(cli, listOf(user(1, 1.0), answer(2, 2.0)))
        assertEquals(emptyList<AudioItem>(), before)
        val after = AudioInbox.collect(cli, listOf(user(1, 1.0), answer(2, 2.0), lateAudio(3, 3.0)))
        assertEquals(listOf("2"), after.map { it.messageId })
        assertEquals(listOf("2"), AudioInbox.merge(after, after).map { it.messageId })
    }

    @Test
    fun olderItemsAreKeptUntilTheBoundAndTelegramFollowsTheFilter() {
        val old = AudioInbox.collect(tg, listOf(user(1, 1.0), speech(2, 2.0), answer(3, 3.0)))
        val new = AudioInbox.collect(cli, listOf(user(10, 10.0), speech(11, 11.0), answer(12, 12.0)))
        // A later refresh that only found the newer item keeps the older one.
        val merged = AudioInbox.merge(old, new)
        assertEquals(listOf("12", "3"), merged.map { it.messageId })
        assertEquals(listOf("12"), AudioInbox.merge(old, new, max = 1).map { it.messageId })
        assertEquals(listOf("12"), AudioInbox.visible(merged, includeMessaging = false, max = 10).map { it.messageId })
        assertEquals(listOf("12", "3"), AudioInbox.visible(merged, includeMessaging = true, max = 10).map { it.messageId })
    }

    @Test
    fun anEmptyTranscriptHasNoItems() {
        assertEquals(emptyList<AudioItem>(), AudioInbox.collect(cli, emptyList()))
    }
}
