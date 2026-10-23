package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesHttp
import dev.wristbound.companion.hermes.MessageList
import org.junit.Assert.*
import org.junit.Test

class DurableAudioTest {
    @Test fun automaticAudioBelongsOnlyToExactAssistantEvenAcrossTurns() {
        val rows = HermesHttp.decode(MessageList.serializer(), """{"messages":[
          {"id":1,"role":"user","content":"capture","audio_attachments":[{"id":"bad","created_at":20,"mime_type":"audio/ogg"}]},
          {"id":2,"role":"assistant","content":"first","audio_attachments":[{"id":"abc.ogg","created_at":20,"mime_type":"audio/ogg"}]},
          {"id":3,"role":"assistant","content":"second"},
          {"id":4,"role":"user","content":"another"},
          {"id":5,"role":"assistant","content":"third"}
        ]}""", "history").messages
        val replies = SessionMapper.replies(rows)
        assertEquals(listOf("attachment:abc.ogg"), replies[0].audio)
        assertFalse(replies[1].hasAudio)
        assertFalse(replies[2].hasAudio)
    }
}
