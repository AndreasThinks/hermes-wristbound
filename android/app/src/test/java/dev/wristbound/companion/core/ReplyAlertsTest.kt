package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesHttp
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import kotlinx.coroutines.test.runTest
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
    private fun dispatcher(s: AlertStore = store) = ReplyAlerts(s, { policy }) {
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
}
