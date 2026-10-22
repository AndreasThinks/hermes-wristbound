package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesHttp
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.pebble.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMapperTest {
    private val sessions = listOf(
        HermesSession("tg", source = "telegram", title = "Index note", last_active = 30.0),
        HermesSession("api", source = "api_server", title = "Watch chat", last_active = 20.0),
        HermesSession("old", source = "cli", title = "Old", last_active = 10.0),
        HermesSession("arch", source = "cli", title = "Archived", last_active = 40.0, archived = true),
        HermesSession("child", source = "api_server", title = "Subagent", last_active = 50.0, is_internal_child = true),
    )

    @Test
    fun filterControlsTelegramVisibilityAndHidesArchived() {
        val shown = SessionMapper.visibleSessions(sessions, true, emptyMap(), emptySet(), emptySet())
        assertEquals(listOf("tg", "api", "old"), shown.map { it.id })
        val hidden = SessionMapper.visibleSessions(sessions, false, emptyMap(), emptySet(), emptySet())
        assertEquals(listOf("api", "old"), hidden.map { it.id })
    }

    @Test
    fun flagsUnreadTelegramReadOnlyAndActive() {
        val shown = SessionMapper.visibleSessions(
            sessions, true, lastSeen = mapOf("tg" to 25.0, "api" to 20.0), readOnly = setOf("old"), active = setOf("api"),
        ).associateBy { it.id }
        assertEquals(Protocol.SessionFlag.UNREAD or Protocol.SessionFlag.TELEGRAM, shown.getValue("tg").flags)
        assertEquals(Protocol.SessionFlag.ACTIVE, shown.getValue("api").flags)
        // Never-seen sessions are not unread: LocalState records a baseline the first time.
        assertEquals(Protocol.SessionFlag.READ_ONLY, shown.getValue("old").flags)
    }

    private fun msg(json: String) = HermesHttp.json.decodeFromString(HermesMessage.serializer(), json)

    @Test
    fun watchMessagesHideToolStepsByDefault() {
        val raw = listOf(
            msg("""{"id":1,"role":"user","content":"Check disk"}"""),
            msg("""{"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c"}]}"""),
            msg("""{"id":3,"role":"tool","content":"92%","tool_name":"terminal"}"""),
            msg("""{"id":4,"role":"assistant","content":"Disk is 92% full. MEDIA:/home/h/.hermes/audio_cache/r.mp3"}"""),
        )
        val hidden = SessionMapper.watchMessages(raw, ToolActivity.HIDDEN)
        assertEquals(listOf("1", "4"), hidden.map { it.id })
        assertEquals(Protocol.MessageFlag.HAS_AUDIO, hidden[1].flags)
        val shown = SessionMapper.watchMessages(raw, ToolActivity.SILENT)
        assertEquals(listOf("1", "2", "3", "4"), shown.map { it.id })
        assertEquals("Used terminal", shown[2].text)
    }

    @Test
    fun audioFromTheTextToSpeechToolBelongsToTheReplyOfThatTurn() {
        val raw = listOf(
            msg("""{"id":1,"role":"user","content":"Say hi"}"""),
            msg("""{"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c"}]}"""),
            msg("""{"id":3,"role":"tool","tool_name":"text_to_speech","content":"{\"success\": true, \"media_tag\": \"[[audio_as_voice]]\\nMEDIA:/home/h/.hermes/cache/audio/tts_1.ogg\"}"}"""),
            msg("""{"id":4,"role":"assistant","content":"Here you go."}"""),
            msg("""{"id":5,"role":"user","content":"Thanks"}"""),
            msg("""{"id":6,"role":"assistant","content":"Any time."}"""),
        )
        val replies = SessionMapper.replies(raw).associateBy { it.id }
        assertEquals(listOf("/home/h/.hermes/cache/audio/tts_1.ogg"), replies.getValue("4").audio)
        assertEquals(emptyList<String>(), replies.getValue("6").audio)
        val shown = SessionMapper.watchMessages(raw, ToolActivity.HIDDEN).associateBy { it.id }
        assertEquals(Protocol.MessageFlag.HAS_AUDIO, shown.getValue("4").flags)
        assertEquals(0, shown.getValue("6").flags)
    }

    @Test
    fun theUsersOwnRecordingIsNeverTheAssistantsAudio() {
        // A Ringbearer or Telegram voice note lands in the same cache/audio folder as speech.
        val raw = listOf(
            msg("""{"id":1,"role":"user","content":"[The user sent a voice message: /home/h/.hermes/cache/audio/audio_ab12.ogg (duration: 0:07)]"}"""),
            msg("""{"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c"}]}"""),
            msg("""{"id":3,"role":"tool","tool_name":"transcribe","content":"MEDIA:/home/h/.hermes/cache/audio/audio_ab12.ogg -> hello"}"""),
            msg("""{"id":4,"role":"assistant","content":"You said hello. MEDIA:/workspace/cache/audio/audio_ab12.ogg"}"""),
        )
        val reply = SessionMapper.replies(raw).single()
        assertEquals("4", reply.id)
        assertFalse(reply.hasAudio)
    }

    @Test
    fun anInboundRecordingIsExcludedEvenWhenItsUserRowIsOutOfView() {
        val raw = listOf(
            msg("""{"id":7,"role":"assistant","content":"Saved your note. MEDIA:/home/h/.hermes/cache/audio/audio_0123456789ab.ogg"}"""),
        )
        assertFalse(SessionMapper.replies(raw).single().hasAudio)
    }

    @Test
    fun onlyTextToSpeechToolOutputCountsAsGeneratedAudio() {
        val raw = listOf(
            msg("""{"id":1,"role":"user","content":"Find the podcast"}"""),
            msg("""{"id":2,"role":"tool","tool_name":"terminal","content":"saved MEDIA:/tmp/download.mp3"}"""),
            msg("""{"id":3,"role":"assistant","content":"Found it."}"""),
        )
        assertFalse(SessionMapper.replies(raw).single().hasAudio)
    }

    @Test
    fun audioThatArrivesAfterTheTextJoinsThatReply() {
        val text = listOf(
            msg("""{"id":1,"role":"user","content":"Read me the summary"}"""),
            msg("""{"id":2,"role":"assistant","content":"Here is the summary."}"""),
        )
        assertFalse(SessionMapper.replies(text).single().hasAudio)
        val later = text + msg("""{"id":3,"role":"assistant","content":"[[audio_as_voice]]\nMEDIA:/home/h/.hermes/cache/audio/tts_9.ogg"}""")
        val reply = SessionMapper.replies(later).single()
        assertEquals("2", reply.id)
        assertEquals(listOf("/home/h/.hermes/cache/audio/tts_9.ogg"), reply.audio)
        // The media-only row is not a second message on the watch.
        assertEquals(listOf("1", "2"), SessionMapper.watchMessages(later, ToolActivity.HIDDEN).map { it.id })
    }

    @Test
    fun aMediaOnlyAnswerIsStillAReply() {
        val raw = listOf(
            msg("""{"id":1,"role":"user","content":"Say it"}"""),
            msg("""{"id":2,"role":"assistant","content":"MEDIA:/home/h/.hermes/cache/audio/tts_2.mp3"}"""),
        )
        val reply = SessionMapper.replies(raw).single()
        assertEquals("2", reply.id)
        assertTrue(reply.hasAudio)
    }

    @Test
    fun latestTurnRepliesIgnoreEarlierTurns() {
        val raw = listOf(
            msg("""{"id":1,"role":"user","content":"a"}"""),
            msg("""{"id":2,"role":"assistant","content":"old"}"""),
            msg("""{"id":3,"role":"user","content":"b"}"""),
            msg("""{"id":4,"role":"assistant","content":"new"}"""),
        )
        assertEquals(listOf("4"), SessionMapper.latestTurnReplies(raw).map { it.id })
        assertEquals(emptyList<String>(), SessionMapper.latestTurnReplies(raw.take(3)).map { it.id })
    }

    @Test
    fun telegramReadOnlyModeKeepsOtherSessionsWritable() {
        val shown = SessionMapper.visibleSessions(
            sessions, true, emptyMap(), emptySet(), emptySet(), telegramReplies = false,
        ).associateBy { it.id }
        assertEquals(Protocol.SessionFlag.TELEGRAM or Protocol.SessionFlag.READ_ONLY, shown.getValue("tg").flags)
        assertEquals(0, shown.getValue("api").flags)

        assertEquals("Reply in Telegram", SessionMapper.replyRefusal(sessions[0], telegramReplies = false))
        assertEquals(null, SessionMapper.replyRefusal(sessions[1], telegramReplies = false))
        assertEquals("Refresh sessions first", SessionMapper.replyRefusal(null, telegramReplies = false))
        assertEquals(null, SessionMapper.replyRefusal(sessions[0], telegramReplies = true))
        assertEquals(null, SessionMapper.replyRefusal(null, telegramReplies = true))
    }
}
