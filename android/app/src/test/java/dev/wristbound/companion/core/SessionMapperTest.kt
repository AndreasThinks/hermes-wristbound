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
    fun messagingAndAutomationSessionsAreFilteredAndPinnedComeFirst() {
        val mixed = listOf(
            HermesSession("cron", source = "cron", title = "Digest", last_active = 60.0),
            HermesSession("dc", source = "discord", title = "Discord", last_active = 55.0),
            HermesSession("kb", source = "kanban", title = "Worker", last_active = 50.0),
            HermesSession("desk", source = "desktop", title = "Desk", last_active = 40.0),
            HermesSession("pin", source = "cli", title = "Pinned", last_active = 5.0, pinned = true),
        )
        fun ids(messaging: Boolean, automation: Boolean) = SessionMapper.visibleSessions(
            mixed, messaging, emptyMap(), emptySet(), emptySet(), includeAutomation = automation,
        ).map { it.id }
        assertEquals(listOf("pin", "dc", "desk"), ids(messaging = true, automation = false))
        assertEquals(listOf("pin", "desk"), ids(messaging = false, automation = false))
        assertEquals(listOf("pin", "cron", "dc", "kb", "desk"), ids(messaging = true, automation = true))
    }

    @Test
    fun sourcesAreClassified() {
        fun s(source: String?) = HermesSession("x", source = source)
        assertTrue(s("telegram").isMessaging && s("Discord").isMessaging && s("irc").isMessaging)
        assertTrue(s("cron").isAutomation && s("subagent").isAutomation && s("webhook").isAutomation)
        for (local in listOf("desktop", "tui", "cli", "webui", "api_server", null)) {
            assertFalse(s(local).isMessaging || s(local).isAutomation)
        }
    }

    @Test
    fun repliesToTurnsHermesStartedAreBackgroundAndItsNoticeIsNotShownAsSent() {
        val rows = listOf(
            msg("""{"id":1,"role":"user","content":"Run the tests","timestamp":1.0}"""),
            msg("""{"id":2,"role":"assistant","content":"Started.","timestamp":2.0}"""),
            msg("""{"id":3,"role":"user","display_kind":"process_complete","content":"[Background process finished]","timestamp":3.0}"""),
            msg("""{"id":4,"role":"assistant","content":"All green.","timestamp":4.0}"""),
            msg("""{"id":5,"role":"user","display_kind":"hidden","content":"[System: nudge]","timestamp":5.0}"""),
        )
        assertEquals(listOf(false, true), SessionMapper.replies(rows).map { it.background })
        val shown = SessionMapper.watchMessages(rows, ToolActivity.HIDDEN)
        assertEquals(listOf("1", "2", "3", "4"), shown.map { it.id })
        assertEquals(Protocol.Role.SYSTEM, shown[2].role)
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
    fun messagingReadOnlyModeKeepsOtherSessionsWritable() {
        val discord = HermesSession("dc", source = "discord", title = "dc", last_active = 1.0)
        val shown = SessionMapper.visibleSessions(
            sessions + discord, true, emptyMap(), emptySet(), emptySet(), messagingReplies = false,
        ).associateBy { it.id }
        assertEquals(Protocol.SessionFlag.TELEGRAM or Protocol.SessionFlag.READ_ONLY, shown.getValue("tg").flags)
        assertEquals(Protocol.SessionFlag.READ_ONLY, shown.getValue("dc").flags)
        assertEquals(0, shown.getValue("api").flags)
        assertEquals("Reply in the chat app", SessionMapper.replyRefusal(discord, messagingReplies = false))

        assertEquals("Reply in Telegram", SessionMapper.replyRefusal(sessions[0], messagingReplies = false))
        assertEquals(null, SessionMapper.replyRefusal(sessions[1], messagingReplies = false))
        assertEquals("Refresh sessions first", SessionMapper.replyRefusal(null, messagingReplies = false))
        assertEquals(null, SessionMapper.replyRefusal(sessions[0], messagingReplies = true))
        assertEquals(null, SessionMapper.replyRefusal(null, messagingReplies = true))
    }

    @Test
    fun completionNoticesNameTheWorkTheyReport() {
        fun notice(content: String, kind: String = "internal_notification", meta: String = "") =
            msg("""{"role":"user","display_kind":"$kind",$meta"content":${kotlinx.serialization.json.JsonPrimitive(content)}}""").completedWork
        assertEquals(setOf("deleg_1a2b3c4d"), notice("x", "async_delegation_complete", """"display_metadata":{"delegation_id":"deleg_1a2b3c4d"},"""))
        assertEquals(setOf("deleg_1a2b3c4d"), notice("[ASYNC DELEGATION COMPLETE — deleg_1a2b3c4d]\nA background subagent…"))
        assertEquals(setOf("deleg_5"), notice("[ASYNC DELEGATION BATCH COMPLETE — deleg_5]"))
        assertEquals(setOf("deleg_5"), notice("[ASYNC DELEGATION TASK FAILED — deleg_5, task 2/3]"))
        assertEquals(setOf("proc_0123456789ab"), notice("[IMPORTANT: Background process proc_0123456789ab exited normally (exit code 0).", "process_complete"))
        assertEquals(
            setOf("proc_0123456789ab", "deleg_5"),
            notice("[IMPORTANT: Background process proc_0123456789ab exited (exit code 1).\nStarted by subagent sa-1 of delegation deleg_5. Task: \"x\""),
        )
        // A heartbeat is progress, not a result; other notices report no work.
        assertEquals(emptySet<String>(), notice("[Background process proc_0123456789ab heartbeat #2 — still running after 5m]"))
        assertEquals(emptySet<String>(), notice("[Background process finished]", "process_complete"))
        // Only rows that open a background turn count.
        assertEquals(emptySet<String>(), msg("""{"role":"user","content":"[ASYNC DELEGATION COMPLETE — deleg_1]"}""").completedWork)
    }

    @Test
    fun aCompletionWhoseDispatchIsOutOfViewIsBackgroundUntilMoreHistoryIsFetched() {
        val full = listOf(
            msg("""{"id":1,"role":"user","content":"Make the Broadsheet changes","timestamp":1.0}"""),
            msg("""{"id":2,"role":"assistant","content":"","tool_calls":[{"id":"c"}],"timestamp":2.0}"""),
            msg("""{"id":3,"role":"tool","tool_name":"delegate_task","content":"{\"status\": \"dispatched\", \"delegation_id\": \"deleg_1a2b3c4d\"}","timestamp":2.0}"""),
            msg("""{"id":4,"role":"assistant","content":"On it.","timestamp":3.0}"""),
            msg("""{"id":5,"role":"user","display_kind":"async_delegation_complete","display_metadata":{"delegation_id":"deleg_1a2b3c4d"},"content":"Subagent Task Completed","timestamp":9.0}"""),
            msg("""{"id":6,"role":"assistant","content":"Fixed and live.","timestamp":10.0}"""),
        )
        val window = full.drop(4)
        assertTrue(SessionMapper.hasUntracedWork(window))
        assertEquals(listOf(true), SessionMapper.replies(window).map { it.background })
        assertFalse(SessionMapper.hasUntracedWork(full))
        val replies = SessionMapper.replies(full)
        assertEquals(listOf(false, false), replies.map { it.background })
        assertEquals(listOf("1", "1"), replies.map { it.originRow })
        // An id inside a longer id is not the same work.
        val other = full.toMutableList().apply {
            set(2, msg("""{"id":3,"role":"tool","tool_name":"delegate_task","content":"{\"delegation_id\": \"deleg_1a2b3c4d9\"}","timestamp":2.0}"""))
        }
        assertTrue(SessionMapper.hasUntracedWork(other))
    }
}
