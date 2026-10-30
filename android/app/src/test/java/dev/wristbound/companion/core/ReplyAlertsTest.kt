package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesHttp
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ReplyAlertsTest {
    /** What [LocalState] persists, in memory; survives a "restart" by being reused. */
    private class MemoryStore : AlertStore {
        val keys = mutableSetOf<String>()
        override fun markAlerted(key: String) = keys.add(key)
        override fun isAlerted(key: String) = key in keys
        override fun unmarkAlerted(key: String) { keys.remove(key) }
        override var pollCursors: PollCursors? = null
    }

    private fun msg(json: String) = HermesHttp.json.decodeFromString(HermesMessage.serializer(), json)

    /** The defaults. */
    private var policy = AlertPolicy(
        enabled = true, audio = true, watchTurns = true, chats = true, messagingText = false, background = false,
    )
    private val store = MemoryStore()
    private val alerts = mutableListOf<ReplyAlert>()
    /** Deliveries that fail before one succeeds; failed attempts are recorded in [attempts]. */
    private var deliveryFailures = 0
    private val attempts = mutableListOf<String>()
    private val log = mutableListOf<AlertRecord>()
    private fun dispatcher(s: AlertStore = store) = ReplyAlerts(s, { policy }, log = { log += it }, now = { 1_000L }) {
        attempts += it.messageId
        if (deliveryFailures > 0) { deliveryFailures--; false } else { alerts += it; true }
    }

    /** The server: session rows and each session's transcript, both editable between polls. */
    private val sessions = mutableMapOf<String, HermesSession>()
    private val transcripts = mutableMapOf<String, List<HermesMessage>>()
    private var fetchFailures = 0
    private var busy = emptySet<String>()

    private fun session(id: String, source: String, activity: Double, vararg rows: HermesMessage) {
        sessions[id] = HermesSession(id, source = source, title = id, last_active = activity)
        transcripts[id] = rows.toList()
    }

    private fun append(id: String, activity: Double, vararg rows: HermesMessage) {
        sessions[id] = sessions.getValue(id).copy(last_active = activity)
        transcripts[id] = transcripts.getValue(id) + rows
    }

    private suspend fun poll(d: ReplyAlerts = dispatcher()) = d.poll(sessions.values.toList(), { it in busy }) { id ->
        if (fetchFailures > 0) { fetchFailures--; throw IOException("history unavailable") }
        transcripts.getValue(id)
    }

    private val voiceNote = """{"role":"user","content":"[The user sent a voice message: /home/h/.hermes/cache/audio/audio_ab12.ogg (duration: 0:07)]"}"""
    private fun user(id: Int, ts: Double, text: String = "hi") = msg("""{"id":$id,"role":"user","content":"$text","timestamp":$ts}""")
    private fun answer(id: Int, ts: Double, text: String = "Done.") = msg("""{"id":$id,"role":"assistant","content":"$text","timestamp":$ts}""")
    private fun speech(id: Int, ts: Double) = msg(
        """{"id":$id,"role":"tool","tool_name":"text_to_speech","timestamp":$ts,"content":"{\"success\": true, \"media_tag\": \"MEDIA:/home/h/.hermes/cache/audio/tts_$id.ogg\"}"}""",
    )

    @Test
    fun ringbearerCaptureInTelegramAlertsOnceForItsAudioReply() = runTest {
        // Telegram visible, Telegram text alerts off, audio alerts on (the defaults).
        session("tg", "telegram", 10.0, user(1, 9.0), answer(2, 10.0))
        poll() // baseline
        append(
            "tg", 20.0,
            msg(voiceNote.replace("{", """{"id":3,"timestamp":15.0,""")),
            speech(4, 18.0),
            answer(5, 20.0, "Here is the weather."),
        )
        poll()
        assertEquals(1, alerts.size)
        val alert = alerts.single()
        assertEquals("5", alert.messageId)
        assertTrue(alert.hasAudio)
        assertFalse(alert.silent)
        // Polling again, or a later bump in activity with no new reply, never repeats it.
        poll()
        sessions["tg"] = sessions.getValue("tg").copy(last_active = 25.0)
        poll()
        assertEquals(1, alerts.size)
    }

    @Test
    fun telegramTextOnlyRepliesDoNotAlertWithTextAlertsOff() = runTest {
        session("tg", "telegram", 10.0)
        poll()
        append("tg", 20.0, msg(voiceNote.replace("{", """{"id":3,"timestamp":15.0,""")), answer(4, 20.0))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
    }

    @Test
    fun telegramAudioArrivingAfterTheTextAlertsOnceWhenItArrives() = runTest {
        session("tg", "telegram", 10.0)
        poll()
        append("tg", 20.0, user(3, 15.0), answer(4, 20.0))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        append("tg", 30.0, msg("""{"id":5,"role":"assistant","timestamp":30.0,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_5.ogg"}"""))
        poll()
        assertEquals(listOf("4"), alerts.map { it.messageId })
        assertTrue(alerts.single().hasAudio && !alerts.single().silent)
        poll()
        assertEquals(1, alerts.size)
    }

    @Test
    fun aReplyThatAlreadyAlertedIsOnlyRefreshedWhenItsAudioArrives() = runTest {
        session("cli", "cli", 10.0)
        poll()
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        poll()
        append("cli", 30.0, msg("""{"id":5,"role":"assistant","timestamp":30.0,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_5.ogg"}"""))
        poll()
        assertEquals(listOf(false to false, true to true), alerts.map { it.hasAudio to it.silent })
        assertEquals(listOf("4", "4"), alerts.map { it.messageId })
    }

    @Test
    fun messagingTextAndAudioOffNeverAlertEvenForAudio() = runTest {
        policy = policy.copy(messagingText = false, audio = false)
        session("tg", "telegram", 10.0)
        poll()
        append("tg", 20.0, user(3, 15.0), speech(4, 18.0), answer(5, 20.0))
        poll()
        append("tg", 30.0, msg("""{"id":6,"role":"assistant","timestamp":30.0,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_6.ogg"}"""))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        // Turning audio alerts on later does not replay replies already decided.
        policy = policy.copy(audio = true)
        sessions["tg"] = sessions.getValue("tg").copy(last_active = 31.0)
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
    }

    @Test
    fun aFailedFetchKeepsTheCheckpointSoTheNextPollAlerts() = runTest {
        session("cli", "cli", 10.0)
        poll()
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        fetchFailures = 1
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        assertEquals(10.0, store.pollCursors!!.sessions["cli"]!!, 0.0)
        // Same activity as the failed poll; it must still be processed.
        poll()
        assertEquals(listOf("4"), alerts.map { it.messageId })
        assertEquals(20.0, store.pollCursors!!.sessions["cli"]!!, 0.0)
        poll()
        assertEquals(1, alerts.size)
    }

    @Test
    fun aRunningTurnIsSkippedWithoutAdvancing() = runTest {
        session("cli", "cli", 10.0)
        poll()
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        busy = setOf("cli")
        assertEquals(setOf("cli"), poll())
        assertEquals(emptyList<ReplyAlert>(), alerts)
        busy = emptySet()
        poll()
        assertEquals(listOf("4"), alerts.map { it.messageId })
    }

    @Test
    fun restartCatchesUpFromPersistedCheckpoints() = runTest {
        session("cli", "cli", 10.0, user(1, 9.0), answer(2, 10.0))
        poll()
        // The process dies; replies arrive; a new process with the same store polls.
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        session("new", "cli", 25.0, user(5, 24.0), answer(6, 25.0))
        poll(dispatcher())
        assertEquals(listOf("4", "6"), alerts.map { it.messageId }.sorted())
        // Old history from before the baseline never alerts.
        assertFalse("2" in alerts.map { it.messageId })
    }

    @Test
    fun theFirstPollOnlyTakesABaseline() = runTest {
        session("cli", "cli", 10.0, user(1, 9.0), answer(2, 10.0))
        assertEquals(emptySet<String>(), poll())
        assertEquals(emptyList<ReplyAlert>(), alerts)
        assertEquals(10.0, store.pollCursors!!.since, 0.0)
    }

    @Test
    fun repliesOlderThanTheCheckpointDoNotAlert() = runTest {
        session("cli", "cli", 10.0, user(1, 5.0), answer(2, 6.0, "Older"))
        poll()
        // A different client alerted nothing for #2; it is still old history.
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        poll()
        assertEquals(listOf("4"), alerts.map { it.messageId })
    }

    @Test
    fun aReplyToAWatchTurnAlertsEvenInTelegram() = runTest {
        val tg = HermesSession("tg", source = "telegram", title = "tg")
        val d = dispatcher()
        // Messaging text alerts are off, but this answers what was sent from the watch.
        d.turnFinished(tg, listOf(user(1, 1.0), answer(2, 2.0)))
        assertEquals(listOf("2"), alerts.map { it.messageId })

        policy = policy.copy(watchTurns = false)
        d.turnFinished(HermesSession("cli", source = "cli"), listOf(user(6, 6.0), answer(7, 7.0)))
        assertEquals(1, alerts.size)
        // Audio follows its own switch.
        d.turnFinished(tg, listOf(user(3, 3.0), speech(4, 4.0), answer(5, 5.0)))
        assertEquals(listOf("2", "5"), alerts.map { it.messageId })
        policy = policy.copy(enabled = false, watchTurns = true)
        d.turnFinished(HermesSession("cli", source = "cli"), listOf(user(8, 8.0), speech(9, 8.5), answer(10, 9.0)))
        assertEquals(2, alerts.size)
    }

    @Test
    fun scheduledTextIsSilentButScheduledAudioAlerts() = runTest {
        session("cron", "cron", 10.0)
        poll()
        append("cron", 20.0, user(3, 15.0, "Daily digest"), answer(4, 20.0, "Nothing new."))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        append("cron", 30.0, user(5, 25.0, "Morning briefing"), speech(6, 28.0), answer(7, 30.0, "Good morning."))
        poll()
        assertEquals(listOf("7"), alerts.map { it.messageId })
        assertTrue(alerts.single().hasAudio)
    }

    @Test
    fun aBackgroundFollowUpInAChatIsSilent() = runTest {
        session("desk", "desktop", 10.0)
        poll()
        append(
            "desk", 20.0,
            msg("""{"id":3,"role":"user","display_kind":"process_complete","content":"[Background process finished]","timestamp":15.0}"""),
            answer(4, 20.0, "The build passed."),
        )
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        // A reply to something a person wrote in the same chat still alerts.
        append("desk", 30.0, user(5, 25.0), answer(6, 30.0))
        poll()
        assertEquals(listOf("6"), alerts.map { it.messageId })
        // With background alerts on, follow-ups alert too.
        policy = policy.copy(background = true)
        append(
            "desk", 40.0,
            msg("""{"id":7,"role":"user","display_kind":"async_delegation_complete","content":"[Delegated task done]","timestamp":35.0}"""),
            answer(8, 40.0, "Research is in."),
        )
        poll()
        assertEquals(listOf("6", "8"), alerts.map { it.messageId })
    }

    @Test
    fun chatRepliesFollowTheirSwitch() = runTest {
        policy = policy.copy(chats = false)
        session("cli", "cli", 10.0)
        poll()
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
    }

    @Test
    fun otherMessagingPlatformsFollowTheTelegramRules() = runTest {
        session("dc", "discord", 10.0)
        poll()
        append("dc", 20.0, user(3, 15.0), answer(4, 20.0))
        append("dc", 30.0, user(5, 25.0), speech(6, 28.0), answer(7, 30.0))
        poll()
        assertEquals(listOf("7"), alerts.map { it.messageId })
    }

    @Test
    fun aDirectCompletionAndALaterPollAlertOnce() = runTest {
        session("cli", "cli", 10.0)
        poll()
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        dispatcher().turnFinished(sessions.getValue("cli"), transcripts.getValue("cli"))
        // The socket dropped and came back; polling sees the same reply.
        poll()
        assertEquals(listOf("4"), alerts.map { it.messageId })
    }

    @Test
    fun aDirectCompletionDoesNotAlertEarlierTurns() = runTest {
        dispatcher().turnFinished(HermesSession("cli", source = "cli"), listOf(user(1, 1.0), answer(2, 2.0), user(3, 3.0)))
        assertEquals(emptyList<ReplyAlert>(), alerts)
    }

    @Test
    fun anUndeliveredAlertIsRetriedOnTheNextPollAndThenShownOnce() = runTest {
        session("tg", "telegram", 10.0)
        val d = dispatcher()
        poll(d)
        append("tg", 20.0, user(3, 15.0), speech(4, 18.0), answer(5, 20.0, "Audio reply"))
        deliveryFailures = 1
        poll(d)
        assertEquals(emptyList<ReplyAlert>(), alerts)
        // Not recorded as alerted, and the checkpoint did not move past it.
        assertFalse(store.isAlerted("5") || store.isAlerted("audio:5"))
        assertEquals(10.0, store.pollCursors!!.sessions["tg"] ?: store.pollCursors!!.since, 0.0)
        poll(d)
        assertEquals(listOf("5"), alerts.map { it.messageId })
        assertTrue(alerts.single().hasAudio)
        poll(d)
        sessions["tg"] = sessions.getValue("tg").copy(last_active = 21.0)
        poll(d)
        assertEquals(1, alerts.size)
        assertEquals(listOf("5", "5"), attempts)
    }

    @Test
    fun anUndeliveredAlertSurvivesARestart() = runTest {
        session("cli", "cli", 10.0)
        poll()
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        deliveryFailures = 1
        poll()
        // A new process with the same store picks it up.
        poll(dispatcher())
        assertEquals(listOf("4"), alerts.map { it.messageId })
    }

    @Test
    fun lateAudioThatFailsToDeliverIsRetriedWithoutRepeatingTheText() = runTest {
        session("tg", "telegram", 10.0)
        val d = dispatcher()
        poll(d)
        append("tg", 20.0, user(3, 15.0), answer(4, 20.0))
        poll(d) // Telegram text alerts off: decided, nothing shown
        append("tg", 30.0, msg("""{"id":5,"role":"assistant","timestamp":30.0,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_5.ogg"}"""))
        deliveryFailures = 1
        poll(d)
        assertEquals(emptyList<ReplyAlert>(), alerts)
        poll(d)
        assertEquals(listOf("4"), alerts.map { it.messageId })
        assertTrue(alerts.single().hasAudio && !alerts.single().silent)
    }

    @Test
    fun deliveryIsAbandonedAfterBoundedAttempts() = runTest {
        session("cli", "cli", 10.0)
        val d = dispatcher()
        poll(d)
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        deliveryFailures = 100
        repeat(ReplyAlerts.MAX_ATTEMPTS + 3) { poll(d) }
        assertEquals(ReplyAlerts.MAX_ATTEMPTS, attempts.size)
        assertEquals(20.0, store.pollCursors!!.sessions["cli"]!!, 0.0)
    }

    @Test
    fun aFailedDirectCompletionIsPickedUpByPolling() = runTest {
        session("cli", "cli", 10.0)
        val d = dispatcher()
        poll(d)
        append("cli", 20.0, user(3, 15.0), answer(4, 20.0))
        deliveryFailures = 1
        d.turnFinished(sessions.getValue("cli"), transcripts.getValue("cli"))
        assertEquals(emptyList<ReplyAlert>(), alerts)
        poll(d)
        assertEquals(listOf("4"), alerts.map { it.messageId })
    }

    @Test
    fun lateAutomaticAudioAfterBaselineIsDiscoveredAndSurvivesRestart() = runTest {
        session("tg", "telegram", 10.0, user(1, 1.0), answer(2, 2.0))
        poll() // text predates first monitoring baseline and was never marked
        transcripts["tg"] = listOf(user(1, 1.0), msg("""{"id":2,"role":"assistant","timestamp":2,
          "content":"Answer","audio_attachments":[{"id":"abc.ogg","mime_type":"audio/ogg","created_at":20}]}"""))
        sessions["tg"] = sessions.getValue("tg").copy(last_active = 20.0)
        deliveryFailures = 1
        poll()
        assertTrue(alerts.isEmpty())
        poll(dispatcher(store))
        assertEquals(1, alerts.size)
        assertTrue(alerts.single().hasAudio)
        assertFalse(alerts.single().silent)
        poll(dispatcher(store))
        assertEquals(1, alerts.size)
    }

    @Test
    fun policyChangeDoesNotDoubleAlertAlreadyDeliveredText() = runTest {
        session("chat", "cli", 10.0)
        poll()
        append("chat", 20.0, user(1, 19.0), answer(2, 20.0))
        poll()
        policy = policy.copy(chats = false)
        append("chat", 30.0, answer(3, 30.0, "MEDIA:/cache/tts_late.ogg"))
        poll()
        assertEquals(listOf(false, true), alerts.map { it.silent })
    }

    @Test
    fun logRecordsWhyEachReplyBuzzedOrStayedSilent() = runTest {
        session("tg", "telegram", 10.0)
        session("api", "api_server", 10.0)
        poll()
        append("tg", 20.0, user(3, 15.0), answer(4, 20.0, "Booked for 8."))
        append("api", 20.0, user(5, 15.0), answer(6, 20.0, "Pushed the fix."))
        poll()
        val byTitle = log.associateBy { it.title }
        assertEquals("SILENT · MESSAGING OFF", byTitle.getValue("tg").label)
        assertEquals("Booked for 8.", byTitle.getValue("tg").preview)
        assertEquals("BUZZED · CHAT REPLY", byTitle.getValue("api").label)
        assertEquals(1_000L, byTitle.getValue("api").at)

        // The audio turns up later: a new decision, and this time it buzzes.
        append("tg", 30.0, msg("""{"id":7,"role":"assistant","timestamp":30.0,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_7.ogg"}"""))
        poll()
        assertEquals(3, log.size)
        assertEquals("BUZZED · VOICE", log.last().label)
        // Polling again records nothing new.
        poll()
        assertEquals(3, log.size)
    }

    @Test
    fun logRecordsAnAlertOnlyOnceItIsDeliveredOrGivenUp() = runTest {
        session("api", "api_server", 10.0)
        poll()
        append("api", 20.0, user(3, 15.0), answer(4, 20.0))
        deliveryFailures = ReplyAlerts.MAX_ATTEMPTS
        val d = dispatcher()
        repeat(ReplyAlerts.MAX_ATTEMPTS - 1) { poll(d) }
        assertEquals(emptyList<AlertRecord>(), log)
        poll(d)
        assertEquals(listOf(AlertRecord.Outcome.FAILED), log.map { it.outcome })
    }

    // -- finished work: delegations and background processes ------------------------------------

    private fun row(id: Int, ts: Double, role: String, content: String, toolName: String? = null, kind: String? = null, delegation: String? = null) =
        HermesMessage(
            id = JsonPrimitive(id), role = role, content = JsonPrimitive(content), timestamp = ts, tool_name = toolName,
            display_kind = kind, display_metadata = delegation?.let { d -> buildJsonObject { put("delegation_id", d) } },
        )

    /** The assistant's `delegate_task` call and its result, as Hermes stores them. */
    private fun delegate(id: Int, ts: Double, delegation: String) = listOf(
        HermesMessage(id = JsonPrimitive(id), role = "assistant", content = JsonPrimitive(""), timestamp = ts,
            tool_calls = JsonArray(listOf(buildJsonObject { put("id", "call_$id") }))),
        row(id + 1, ts, "tool", """{"status": "dispatched", "delegation_id": "$delegation"}""", toolName = "delegate_task"),
    )

    /** A background `terminal` call's result. */
    private fun startProcess(id: Int, ts: Double, proc: String) =
        row(id, ts, "tool", """{"output": "Background process started", "session_id": "$proc"}""", toolName = "terminal")

    /** The row Hermes writes when a delegation finishes, on the desktop/dashboard path. */
    private fun delegationDone(id: Int, ts: Double, delegation: String, kind: String = "async_delegation_complete") = row(
        id, ts, "user",
        "[ASYNC DELEGATION COMPLETE — $delegation]\nA background subagent you dispatched earlier has finished.\n" +
            "Status: completed\n--- RESULT ---\nDone.\n\n[INTERNAL NOTIFICATION — generated by the Hermes gateway, not written by a human user.]",
        kind = kind, delegation = delegation.takeIf { kind == "async_delegation_complete" },
    )

    private fun processDone(id: Int, ts: Double, proc: String) = row(
        id, ts, "user", "[IMPORTANT: Background process $proc exited normally (exit code 0).\nCommand: npm run build\nOutput:\nok]",
        kind = "process_complete",
    )

    /**
     * Regression: Hermes session 20261030_081243_040854. The user asked for Broadsheet changes;
     * Avery acknowledged, delegated, verified the result after the completion row and replied
     * "Fixed and live." The final answer used to be logged SILENT · BACKGROUND OFF.
     */
    @Test
    fun broadsheetCompletionAlertsLikeTheChatThatAskedForIt() = runTest {
        session("broadsheet", "desktop", 10.0, user(1, 9.0, "Earlier"), answer(2, 10.0))
        poll() // baseline
        append(
            "broadsheet", 20.0,
            user(3, 15.0, "Please make the Broadsheet changes"),
            *delegate(4, 16.0, "deleg_1a2b3c4d").toTypedArray(),
            answer(6, 20.0, "On it: I've handed this to a subagent and will check its work."),
        )
        poll()
        append(
            "broadsheet", 40.0,
            delegationDone(7, 30.0, "deleg_1a2b3c4d"),
            HermesMessage(id = JsonPrimitive(8), role = "assistant", content = JsonPrimitive(""), timestamp = 31.0,
                tool_calls = JsonArray(listOf(buildJsonObject { put("id", "call_8") }))),
            row(9, 32.0, "tool", "HTTP 200", toolName = "terminal"),
            answer(10, 40.0, "Fixed and live."),
        )
        poll()
        assertEquals(listOf("6", "10"), alerts.map { it.messageId })
        assertEquals(listOf("BUZZED · CHAT REPLY", "BUZZED · CHAT REPLY"), log.map { it.label })
        assertEquals("Fixed and live.", log.last().preview)
    }

    @Test
    fun aRequestedCompletionIsSilencedByTheChatSwitchNotTheBackgroundOne() = runTest {
        policy = policy.copy(chats = false, background = true)
        session("desk", "desktop", 10.0)
        poll()
        append("desk", 40.0, user(3, 15.0, "Build it"), startProcess(4, 16.0, "proc_0123456789ab"), answer(5, 17.0, "Building."),
            processDone(6, 30.0, "proc_0123456789ab"), answer(7, 40.0, "Build passed."))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        assertEquals(listOf("SILENT · CHATS OFF", "SILENT · CHATS OFF"), log.map { it.label })
    }

    @Test
    fun requestedProcessAndChainedDelegationCompletionsAlert() = runTest {
        session("desk", "desktop", 10.0)
        poll()
        append(
            "desk", 60.0,
            user(3, 15.0, "Run the build, then have someone write release notes"),
            startProcess(4, 16.0, "proc_0123456789ab"), answer(5, 17.0, "Building."),
            processDone(6, 30.0, "proc_0123456789ab"),
            // The completion turn starts more work, which still traces back to the person.
            *delegate(7, 31.0, "deleg_99887766").toTypedArray(), answer(9, 32.0, "Build passed; notes are being written."),
            delegationDone(10, 50.0, "deleg_99887766", kind = "internal_notification"),
            answer(11, 60.0, "Release notes are ready."),
        )
        poll()
        assertEquals(listOf("5", "9", "11"), alerts.map { it.messageId })
    }

    @Test
    fun noticesThatReportNoWorkStayBackgroundButFinishedWorkIsAChatReply() = runTest {
        session("desk", "desktop", 10.0)
        poll()
        append(
            "desk", 60.0,
            user(3, 15.0), answer(4, 16.0),
            // Hermes woke itself with no work to report…
            row(5, 20.0, "user", "[IMPORTANT: Gateway restarted; checking in.]", kind = "internal_notification"),
            answer(6, 21.0, "Back online."),
            // …started work in that turn on its own; its result is still a reply in this chat, as on the desktop…
            *delegate(7, 22.0, "deleg_aaaabbbb").toTypedArray(), answer(9, 23.0, "Tidying up."),
            delegationDone(10, 30.0, "deleg_aaaabbbb"), answer(11, 31.0, "Tidied."),
            // …and a heartbeat tick, which reports progress, not a result.
            row(12, 40.0, "user", "[Background process proc_0123456789ab heartbeat #2 — still running after 5m]", kind = "internal_notification"),
            answer(13, 41.0, "Still going."),
        )
        poll()
        assertEquals(listOf("4", "11"), alerts.map { it.messageId })
        assertEquals(
            listOf("BUZZED · CHAT REPLY", "SILENT · BACKGROUND OFF", "SILENT · BACKGROUND OFF", "BUZZED · CHAT REPLY", "SILENT · BACKGROUND OFF"),
            log.map { it.label },
        )
    }

    @Test
    fun finishedWorkWhoseRequestIsOutOfViewStillAlertsAsAChatReply() = runTest {
        // Quiet: the request may have come from the watch, but nothing in view says so; the chat switch decides.
        session("desk", "desktop", 10.0)
        poll()
        append("desk", 30.0, delegationDone(7, 25.0, "deleg_0ff5c4e3"), answer(8, 30.0, "Fixed and live."))
        poll()
        assertEquals(listOf("8"), alerts.map { it.messageId })
        assertEquals("BUZZED · CHAT REPLY", log.single().label)
    }

    @Test
    fun completionsInScheduledSessionsStayBackgroundButTheirAudioAlerts() = runTest {
        session("cron", "cron", 10.0)
        poll()
        append("cron", 30.0, user(3, 15.0, "Nightly report"), *delegate(4, 16.0, "deleg_c0c0c0c0").toTypedArray(),
            answer(6, 17.0, "Dispatched."), delegationDone(7, 25.0, "deleg_c0c0c0c0"), answer(8, 30.0, "Report ready."))
        poll()
        assertEquals(emptyList<ReplyAlert>(), alerts)
        assertEquals(listOf("SILENT · BACKGROUND OFF", "SILENT · BACKGROUND OFF"), log.map { it.label })
        append("cron", 40.0, delegationDone(9, 35.0, "deleg_c0c0c0c0"), speech(10, 38.0), answer(11, 40.0, "Here it is."))
        poll()
        assertEquals(listOf("11"), alerts.map { it.messageId })
        assertEquals("BUZZED · VOICE", log.last().label)
    }

    @Test
    fun workAskedForFromTheWatchAlertsAsAWatchReply() = runTest {
        // Quiet: watch replies on, chat replies off.
        policy = policy.copy(chats = false)
        session("desk", "desktop", 10.0)
        poll()
        append("desk", 20.0, user(3, 15.0, "Fix the Broadsheet"), *delegate(4, 16.0, "deleg_0badc0de").toTypedArray(), answer(6, 20.0, "On it."))
        dispatcher().turnFinished(sessions.getValue("desk"), transcripts.getValue("desk"))
        assertEquals(listOf("6"), alerts.map { it.messageId })
        append("desk", 40.0, delegationDone(7, 30.0, "deleg_0badc0de"), answer(8, 40.0, "Fixed and live."))
        poll()
        assertEquals(listOf("6", "8"), alerts.map { it.messageId })
        assertEquals(listOf("BUZZED · WATCH REPLY", "BUZZED · WATCH REPLY"), log.map { it.label })
        // Something typed at the desktop in the same chat still follows the chat switch.
        append("desk", 60.0, user(9, 50.0, "And the footer"), *delegate(10, 51.0, "deleg_f00f00f0").toTypedArray(),
            answer(12, 52.0, "Sure."), delegationDone(13, 55.0, "deleg_f00f00f0"), answer(14, 60.0, "Footer done."))
        poll()
        assertEquals(listOf("6", "8"), alerts.map { it.messageId })
        assertEquals(listOf("SILENT · CHATS OFF", "SILENT · CHATS OFF"), log.takeLast(2).map { it.label })
    }

    @Test
    fun workAskedForFromTheWatchInATelegramChatAlertsAsAWatchReply() = runTest {
        // The defaults: messaging text off, watch replies on.
        session("tg", "telegram", 10.0, user(1, 9.0, "Started on Telegram"), answer(2, 10.0))
        poll()
        append("tg", 20.0, user(3, 15.0, "Now fix the Broadsheet"), *delegate(4, 16.0, "deleg_7e1e9a40").toTypedArray(), answer(6, 20.0, "On it."))
        dispatcher().turnFinished(sessions.getValue("tg"), transcripts.getValue("tg"))
        append("tg", 40.0, delegationDone(7, 30.0, "deleg_7e1e9a40", kind = "internal_notification"), answer(8, 40.0, "Fixed and live."))
        poll()
        assertEquals(listOf("6", "8"), alerts.map { it.messageId })
        assertEquals(listOf("BUZZED · WATCH REPLY", "BUZZED · WATCH REPLY"), log.map { it.label })
    }

    @Test
    fun requestedWorkInAMessagingChatFollowsTheMessagingRules() = runTest {
        session("tg", "telegram", 10.0)
        poll()
        append("tg", 30.0, user(3, 15.0, "Fix it"), *delegate(4, 16.0, "deleg_7e7e7e7e").toTypedArray(), answer(6, 17.0, "On it."),
            delegationDone(7, 25.0, "deleg_7e7e7e7e", kind = "internal_notification"), answer(8, 30.0, "Fixed."))
        poll()
        // Telegram notifies for itself; the text stays quiet, as the chat's own replies do.
        assertEquals(emptyList<ReplyAlert>(), alerts)
        assertEquals(listOf("SILENT · MESSAGING OFF", "SILENT · MESSAGING OFF"), log.map { it.label })
        policy = policy.copy(messagingText = true)
        append("tg", 40.0, delegationDone(9, 35.0, "deleg_7e7e7e7e", kind = "internal_notification"), answer(10, 40.0, "Also fixed."))
        poll()
        assertEquals(listOf("10"), alerts.map { it.messageId })
        assertEquals("BUZZED · MESSAGING", log.last().label)
    }

    @Test
    fun aRequestedCompletionAlertsOnceAndItsLateAudioOnlyRefreshes() = runTest {
        session("desk", "desktop", 10.0)
        poll()
        append("desk", 30.0, user(3, 15.0), *delegate(4, 16.0, "deleg_d00dd00d").toTypedArray(), answer(6, 17.0, "On it."),
            delegationDone(7, 25.0, "deleg_d00dd00d"), answer(8, 30.0, "Done."))
        poll()
        poll()
        assertEquals(listOf("6", "8"), alerts.map { it.messageId })
        append("desk", 35.0, msg("""{"id":9,"role":"assistant","timestamp":35.0,"content":"MEDIA:/home/h/.hermes/cache/audio/tts_9.ogg"}"""))
        poll()
        assertEquals(listOf("6", "8", "8"), alerts.map { it.messageId })
        assertTrue(alerts.last().silent && alerts.last().hasAudio)
        assertEquals(2, log.size)
    }
}
