package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.HermesApi
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.HermesMessage
import dev.wristbound.companion.hermes.HermesSession
import dev.wristbound.companion.hermes.RunStatus
import dev.wristbound.companion.hermes.TurnEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class TurnRunnerTest {
    private class FakeApi(
        val stream: suspend (suspend (TurnEvent) -> Unit) -> Unit,
        val statuses: MutableList<RunStatus> = mutableListOf(),
    ) : HermesApi {
        var submissions = 0
        var polls = 0
        override suspend fun listSessions(limit: Int, source: String?) = emptyList<HermesSession>()
        override suspend fun recentMessages(sessionId: String, limit: Int) = emptyList<HermesMessage>()
        override suspend fun createSession(title: String?) = HermesSession("new")
        override suspend fun runStatus(runId: String): RunStatus {
            polls++
            return if (statuses.size > 1) statuses.removeAt(0) else statuses.first()
        }
        override suspend fun streamTurn(sessionId: String, message: String, idempotencyKey: String, onEvent: suspend (TurnEvent) -> Unit) {
            submissions++
            stream(onEvent)
        }
    }

    private suspend fun run(api: FakeApi) = TurnRunner(api, pollIntervalMs = 10, pollTimeoutMs = 1_000)
        .run("s1", "hi", "k", onProgress = { _, _ -> })

    @Test
    fun completedTurn() = runTest {
        val api = FakeApi({ emit ->
            emit(TurnEvent.Started("run_1"))
            emit(TurnEvent.AssistantCompleted("Done"))
            emit(TurnEvent.Finished("completed", null))
        })
        assertEquals(TurnOutcome.Completed("Done"), run(api))
    }

    @Test
    fun failedTurnIsNotSuccess() = runTest {
        val api = FakeApi({ emit ->
            emit(TurnEvent.Started("run_1"))
            emit(TurnEvent.Finished("failed", "iteration_budget"))
        })
        assertEquals(TurnOutcome.Failed("iteration_budget"), run(api))
    }

    @Test
    fun foreignSessionIsReadOnly() = runTest {
        val api = FakeApi({ throw HermesHttpException(409, "permission_denied", "owned elsewhere") })
        assertEquals(TurnOutcome.ReadOnly, run(api))
    }

    @Test
    fun connectionRefusedBeforeStartIsSafeFailure() = runTest {
        val api = FakeApi({ throw ConnectException("refused") })
        assertEquals(TurnOutcome.Failed("Hermes unreachable"), run(api))
    }

    @Test
    fun droppedStreamWithRunIdIsObservedNotResent() = runTest {
        val api = FakeApi(
            { emit ->
                emit(TurnEvent.Started("run_1"))
                throw IOException("reset")
            },
            mutableListOf(RunStatus(status = "running"), RunStatus(status = "completed", output = "Late answer")),
        )
        assertEquals(TurnOutcome.Completed("Late answer"), run(api))
        assertEquals(1, api.submissions)
        assertEquals(2, api.polls)
    }

    @Test
    fun droppedStreamWithoutRunIdIsUncertain() = runTest {
        val api = FakeApi({ throw IOException("reset mid-request") })
        assertEquals(TurnOutcome.Uncertain("Connection lost; not resent"), run(api))
        assertEquals(1, api.submissions)
    }

    @Test
    fun unknownRunAfterRestartIsUncertain() = runTest {
        val api = FakeApi(
            { emit -> emit(TurnEvent.Started("run_1")); throw IOException("gone") },
            mutableListOf(RunStatus(status = "unknown")),
        )
        assertEquals(TurnOutcome.Uncertain("Hermes restarted mid-turn"), run(api))
    }
}
