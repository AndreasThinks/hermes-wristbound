package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.Gateway
import dev.wristbound.companion.hermes.GatewayClosedException
import dev.wristbound.companion.hermes.GatewayEvent
import dev.wristbound.companion.hermes.GatewayRpcException
import dev.wristbound.companion.hermes.HermesApi
import dev.wristbound.companion.hermes.HermesHttp
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException

class TurnRunnerTest {
    /** A scripted gateway: [answer] returns each call's result, or throws. */
    private class FakeGateway(
        val answer: suspend FakeGateway.(method: String, params: JsonObject, onSent: () -> Unit) -> JsonObject,
    ) : Gateway {
        val channel = Channel<GatewayEvent>(Channel.UNLIMITED)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override val events get() = channel

        override suspend fun call(method: String, params: JsonObject, timeoutMs: Long, onSent: () -> Unit): JsonObject {
            calls += method to params
            return answer(method, params, onSent)
        }

        fun emit(type: String, payload: JsonObject = JsonObject(emptyMap()), sid: String = "rt1") {
            channel.trySend(GatewayEvent(type, sid, payload))
        }

        fun drop() = channel.close(GatewayClosedException("socket reset"))

        override fun close() {
            channel.close()
        }
    }

    private class FakeApi(
        val gateways: MutableList<FakeGateway>,
        var transcript: List<HermesMessage> = emptyList(),
        val openFailure: Exception? = null,
        var historyFailure: Exception? = null,
    ) : HermesApi {
        val opened = mutableListOf<FakeGateway>()
        override suspend fun listSessions(limit: Int) = emptyList<HermesSession>()
        override suspend fun recentMessages(sessionId: String, limit: Int): List<HermesMessage> {
            historyFailure?.let { throw it }
            return transcript.takeLast(limit)
        }
        override suspend fun openGateway(): Gateway {
            openFailure?.let { throw it }
            return gateways.removeAt(0).also { opened += it }
        }
        val submissions get() = opened.sumOf { g -> g.calls.count { it.first == "prompt.submit" } }
    }

    private fun obj(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    private fun msg(json: String) = HermesHttp.json.decodeFromString(HermesMessage.serializer(), json)

    private val ok = obj("status" to "streaming")

    /** Answers resume/create/submit, then plays [after] once the prompt is in. */
    private fun scripted(after: FakeGateway.() -> Unit) = FakeGateway { method, _, onSent ->
        when (method) {
            "session.resume" -> obj("session_id" to "rt1", "running" to false)
            "session.create" -> obj("session_id" to "rt1", "stored_session_id" to "20261022_new")
            "prompt.submit" -> { onSent(); after(); ok }
            else -> JsonObject(emptyMap())
        }
    }

    private suspend fun run(api: FakeApi, session: String? = "tg_123", onCreated: suspend (String) -> Unit = {}) =
        TurnRunner(api, recoverIntervalMs = 10, recoverTimeoutMs = 200).run(session, "hi", onProgress = { _, _ -> }, onCreated = onCreated)

    @Test
    fun continuesAnExistingSessionThroughTheGateway() = runTest {
        val tools = mutableListOf<String>()
        val gateway = scripted {
            emit("message.start")
            emit("tool.start", obj("tool_id" to "t1", "name" to "terminal"))
            emit("message.delta", obj("text" to "All"))
            emit("message.complete", obj("text" to "All good", "status" to "complete"))
        }
        val api = FakeApi(mutableListOf(gateway))
        val outcome = TurnRunner(api).run("tg_123", "hi", onProgress = { _, _ -> }, onTool = { tools += it })
        assertEquals(TurnOutcome.Completed("All good"), outcome)
        assertEquals(listOf("terminal"), tools)
        val (resume, submit) = gateway.calls
        assertEquals("session.resume", resume.first)
        assertEquals("tg_123", resume.second["session_id"]!!.jsonPrimitive.content)
        assertEquals("prompt.submit", submit.first)
        // The runtime id from session.resume, not the stored id, and never a redirect of a running turn.
        assertEquals("rt1", submit.second["session_id"]!!.jsonPrimitive.content)
        assertEquals("hi", submit.second["text"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), submit.second["queued"])
    }

    @Test
    fun eventsForOtherSessionsAreIgnored() = runTest {
        val api = FakeApi(mutableListOf(scripted {
            emit("message.start", sid = "other")
            emit("message.complete", obj("text" to "Not ours"), sid = "other")
            emit("message.start")
            emit("message.complete", obj("text" to "Ours"))
        }))
        assertEquals(TurnOutcome.Completed("Ours"), run(api))
    }

    @Test
    fun newSessionReportsItsIdBeforeSending() = runTest {
        var created: String? = null
        val gateway = scripted {
            emit("message.start")
            emit("message.complete", obj("text" to "Hello"))
        }
        val api = FakeApi(mutableListOf(gateway))
        assertEquals(TurnOutcome.Completed("Hello"), run(api, session = null) { created = it })
        assertEquals("20261022_new", created)
        assertEquals(listOf("session.create", "prompt.submit"), gateway.calls.map { it.first })
    }

    @Test
    fun queuedPromptWaitsForItsOwnTurn() = runTest {
        val gateway = FakeGateway { method, _, onSent ->
            when (method) {
                "session.resume" -> obj("session_id" to "rt1")
                else -> {
                    onSent()
                    emit("message.complete", obj("text" to "The turn we queued behind"))
                    emit("message.start")
                    emit("message.complete", obj("text" to "Ours"))
                    obj("status" to "queued")
                }
            }
        }
        assertEquals(TurnOutcome.Completed("Ours"), run(FakeApi(mutableListOf(gateway))))
    }

    @Test
    fun failedAndInterruptedTurnsAreNotSuccess() = runTest {
        val failed = FakeApi(mutableListOf(scripted {
            emit("message.start")
            emit("message.complete", obj("status" to "error", "error" to "provider 529"))
        }))
        assertEquals(TurnOutcome.Failed("provider 529"), run(failed))
        val interrupted = FakeApi(mutableListOf(scripted {
            emit("message.start")
            emit("message.complete", obj("status" to "interrupted"))
        }))
        assertEquals(TurnOutcome.Cancelled, run(interrupted))
    }

    @Test
    fun refusedResumeIsReadOnlyAndNothingIsSent() = runTest {
        val gateway = FakeGateway { _, _, _ -> throw GatewayRpcException(4132, "Kanban worker transcript") }
        val api = FakeApi(mutableListOf(gateway))
        assertEquals(TurnOutcome.ReadOnly("Kanban worker transcript"), run(api))
        assertEquals(0, api.submissions)
    }

    @Test
    fun rejectedSubmitIsAFailureNotUncertain() = runTest {
        val gateway = FakeGateway { method, _, _ ->
            if (method == "session.resume") obj("session_id" to "rt1") else throw GatewayRpcException(4009, "session busy")
        }
        assertEquals(TurnOutcome.Failed("Hermes is busy here"), run(FakeApi(mutableListOf(gateway))))
    }

    @Test
    fun unreachableOrSignedOutBeforeAnythingIsSent() = runTest {
        assertEquals(TurnOutcome.Failed("Hermes unreachable"), run(FakeApi(mutableListOf(), openFailure = ConnectException("refused"))))
        assertEquals(TurnOutcome.AuthFailed, run(FakeApi(mutableListOf(), openFailure = HermesHttpException(401, null, "no"))))
    }

    @Test
    fun dropBeforeTheSubmitLeavesIsASafeFailure() = runTest {
        val gateway = FakeGateway { method, _, _ ->
            if (method == "session.resume") obj("session_id" to "rt1") else throw GatewayClosedException("closed")
        }
        val api = FakeApi(mutableListOf(gateway))
        assertEquals(TurnOutcome.Failed("Connection lost; not sent"), run(api))
    }

    private val history = listOf(
        msg("""{"id":1,"role":"user","content":"hi"}"""),
        msg("""{"id":2,"role":"assistant","content":"OLD answer"}"""),
    )

    /** A session holding [history]; once the prompt is in, the socket drops and [then] is written. */
    private fun dropAfterSubmit(then: List<HermesMessage>, vararg more: FakeGateway): FakeApi {
        val api = FakeApi(mutableListOf(), transcript = history)
        api.gateways += scripted { emit("message.start"); api.transcript = history + then; drop() }
        api.gateways += more
        return api
    }

    private val idle get() = FakeGateway { _, _, _ -> obj("session_id" to "rt2", "running" to false) }

    @Test
    fun dropAfterSubmitReadsTheAnswerFromTheTranscriptWithoutResending() = runTest {
        val api = dropAfterSubmit(listOf(
            msg("""{"id":10,"role":"user","content":"hi"}"""),
            msg("""{"id":11,"role":"assistant","content":"Late answer"}"""),
        ))
        assertEquals(TurnOutcome.Completed("Late answer"), run(api))
        assertEquals(1, api.submissions)
    }

    @Test
    fun anOlderIdenticalPromptAndItsAnswerAreNotThisTurn() = runTest {
        // The reviewer's case: [user("hi"), assistant("OLD answer")] already there, nothing new.
        val api = dropAfterSubmit(emptyList(), idle)
        assertEquals(TurnOutcome.Uncertain("Not sure Hermes got it; not resent"), run(api))
        assertEquals(1, api.submissions)
    }

    @Test
    fun theAnswerIsBoundToThisPromptNotALaterTurn() = runTest {
        val api = dropAfterSubmit(listOf(
            msg("""{"id":3,"role":"user","content":"hi"}"""),
            msg("""{"id":4,"role":"assistant","content":"Ours"}"""),
            msg("""{"id":5,"role":"user","content":"something else, from Telegram"}"""),
            msg("""{"id":6,"role":"assistant","content":"Theirs"}"""),
        ))
        assertEquals(TurnOutcome.Completed("Ours"), run(api))
    }

    @Test
    fun aPromptWithNoAnswerYetIsNotAnsweredByALaterTurn() = runTest {
        val api = dropAfterSubmit(listOf(
            msg("""{"id":3,"role":"user","content":"hi"}"""),
            msg("""{"id":4,"role":"user","content":"next"}"""),
            msg("""{"id":5,"role":"assistant","content":"Answer to next"}"""),
        ), idle)
        assertEquals(TurnOutcome.Uncertain("Hermes got it but did not answer"), run(api))
    }

    @Test
    fun theAckedRowIdIdentifiesThePrompt() = runTest {
        val api = FakeApi(mutableListOf(), transcript = history)
        api.gateways += FakeGateway { method, _, onSent ->
            when (method) {
                "session.resume" -> obj("session_id" to "rt1")
                else -> {
                    onSent()
                    // Hermes rewrote the stored text (e.g. a prefix), so only the id can match it.
                    api.transcript = history + listOf(
                        msg("""{"id":7,"role":"user","content":"[note] hi"}"""),
                        msg("""{"id":8,"role":"assistant","content":"By id"}"""),
                    )
                    emit("message.start")
                    drop()
                    obj("status" to "streaming", "user_row_id" to "7")
                }
            }
        }
        assertEquals(TurnOutcome.Completed("By id"), run(api))
    }

    @Test
    fun withoutABoundaryTheTranscriptIsNotTrusted() = runTest {
        val api = dropAfterSubmit(listOf(
            msg("""{"id":3,"role":"user","content":"hi"}"""),
            msg("""{"id":4,"role":"assistant","content":"Looks like ours"}"""),
        ), idle)
        // History could not be read before the prompt went out.
        api.historyFailure = java.io.IOException("history unavailable")
        val gateway = api.gateways[0]
        api.gateways[0] = FakeGateway { method, params, onSent ->
            if (method == "prompt.submit") api.historyFailure = null
            gateway.answer(this, method, params, onSent)
        }
        assertEquals(TurnOutcome.Uncertain("Not sure Hermes got it; not resent"), run(api))
    }

    @Test
    fun dropAfterSubmitReattachesToTheRunningTurn() = runTest {
        val rejoin = FakeGateway { _, _, _ ->
            emit("message.complete", obj("text" to "Finished while we were away"))
            obj("session_id" to "rt1", "running" to true)
        }
        val api = FakeApi(mutableListOf(scripted { emit("message.start"); drop() }, rejoin))
        assertEquals(TurnOutcome.Completed("Finished while we were away"), run(api))
        assertEquals(listOf("session.resume"), rejoin.calls.map { it.first })
        assertEquals(1, api.submissions)
    }

    @Test
    fun dropAfterSubmitWithNoTraceIsUncertainAndNotResent() = runTest {
        val api = FakeApi(mutableListOf(scripted { drop() }, idle))
        assertEquals(TurnOutcome.Uncertain("Not sure Hermes got it; not resent"), run(api))
        assertEquals(1, api.submissions)
    }

    @Test
    fun hermesStayingOfflineAfterSubmitIsUncertain() = runTest {
        val api = FakeApi(mutableListOf(scripted { drop() }))
        // Every reconnect fails: no gateways left to open.
        val offline = object : HermesApi by api {
            var first = true
            override suspend fun openGateway(): Gateway =
                if (first) { first = false; api.openGateway() } else throw ConnectException("offline")
        }
        val outcome = TurnRunner(offline, recoverIntervalMs = 10, recoverTimeoutMs = 50).run("tg_123", "hi", onProgress = { _, _ -> })
        assertEquals(TurnOutcome.Uncertain("Lost Hermes mid-turn; not resent"), outcome)
        assertEquals(1, api.submissions)
    }
}
