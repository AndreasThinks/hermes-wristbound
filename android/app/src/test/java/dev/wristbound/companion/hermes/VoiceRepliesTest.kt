package dev.wristbound.companion.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRepliesTest {
    private fun msg(json: String) = HermesHttp.json.decodeFromString(HermesMessage.serializer(), json)
    private val rows = listOf(
        msg("""{"id":1,"role":"user","content":"capture","timestamp":10.0}"""),
        msg("""{"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c"}],"timestamp":11.0}"""),
        msg("""{"id":3,"role":"assistant","content":"answer","timestamp":12.0}"""),
    )
    private fun clip(id: String) = AudioAttachment(id, "audio/ogg", 13.0)

    @Test fun nothingFromThePluginLeavesRowsAlone() {
        assertEquals(rows, VoiceReplies.merge(rows, emptyList()))
    }

    @Test fun aReadyReplyCarriesItsClipsInOrder() {
        val merged = VoiceReplies.merge(rows, listOf(VoiceReply("t", "3", "ready", 10.0, listOf(clip("a"), clip("b")))))
        assertEquals(listOf("a", "b"), merged[2].audio_attachments.map { it.id })
        assertFalse(merged[2].audio_pending)
    }

    @Test fun aPendingTurnNotYetTiedToARowMarksTheNewestAnswerAfterIt() {
        val merged = VoiceReplies.merge(rows, listOf(VoiceReply("t", null, "pending", 9.5)))
        assertEquals(listOf(false, false, true), merged.map { it.audio_pending })
        // A pending turn that began after every answer is not this transcript's yet.
        assertTrue(VoiceReplies.merge(rows, listOf(VoiceReply("t", null, "pending", 50.0))).none { it.audio_pending })
    }

    @Test fun aFailedReplyStaysText() {
        val merged = VoiceReplies.merge(rows, listOf(VoiceReply("t", "3", "failed", 10.0)))
        assertFalse(merged[2].audio_pending)
        assertTrue(merged[2].audio_attachments.isEmpty())
    }
}
