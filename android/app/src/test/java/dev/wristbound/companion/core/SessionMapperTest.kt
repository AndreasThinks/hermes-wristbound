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
        assertEquals(listOf("/home/h/.hermes/cache/audio/tts_1.ogg"), SessionMapper.audioFor(raw, 3))
        assertEquals(emptyList<String>(), SessionMapper.audioFor(raw, 5))
        val shown = SessionMapper.watchMessages(raw, ToolActivity.HIDDEN).associateBy { it.id }
        assertEquals(Protocol.MessageFlag.HAS_AUDIO, shown.getValue("4").flags)
        assertEquals(0, shown.getValue("6").flags)
    }

    @Test
    fun newRepliesSkipsNotifiedAndToolSteps() {
        val raw = listOf(
            msg("""{"id":4,"role":"assistant","content":"First"}"""),
            msg("""{"id":5,"role":"assistant","content":"","tool_calls":[{"id":"c"}]}"""),
            msg("""{"id":6,"role":"assistant","content":"Second"}"""),
        )
        assertEquals(listOf("6"), SessionMapper.newReplies(raw, setOf("4")).map { it.stableId })
    }

    @Test
    fun telegramNotificationsAreOptIn() {
        val tg = sessions[0]
        assertFalse(SessionMapper.shouldNotify(tg, NotifyScope.ALL_ELIGIBLE, null, notifyTelegram = false))
        assertTrue(SessionMapper.shouldNotify(tg, NotifyScope.ALL_ELIGIBLE, null, notifyTelegram = true))
        val api = sessions[1]
        assertTrue(SessionMapper.shouldNotify(api, NotifyScope.OPEN_SESSION, "api", false))
        assertFalse(SessionMapper.shouldNotify(api, NotifyScope.OPEN_SESSION, "other", false))
    }
}
