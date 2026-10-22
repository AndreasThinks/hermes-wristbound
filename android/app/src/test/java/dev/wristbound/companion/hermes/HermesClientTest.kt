package dev.wristbound.companion.hermes

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HermesClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HermesClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HermesClient(server.url("/").toString(), "secret-key-0123456789")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun listsSessionsWithBearerAuth() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[{"id":"s1","source":"telegram","title":"Groceries","last_active":20.5,"pinned":false},
                   {"id":"s2","source":"api_server","title":null,"preview":"hello\nworld"}],"limit":50,"offset":0,"has_more":false}""",
            ),
        )
        val sessions = client.listSessions(50, null)
        val req = server.takeRequest()
        assertEquals("Bearer secret-key-0123456789", req.getHeader("Authorization"))
        assertTrue(req.path!!.startsWith("/api/sessions?limit=50"))
        assertEquals(listOf("s1", "s2"), sessions.map { it.id })
        assertTrue(sessions[0].isTelegram)
        assertEquals("hello", sessions[1].displayTitle)
    }

    @Test
    fun parsesMessagesWithIntegerIdsAndPartContent() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","session_id":"s1","data":[
                  {"id":7,"role":"assistant","content":[{"type":"text","text":"Done."}],"timestamp":2.0},
                  {"id":6,"role":"user","content":"Do it","timestamp":1.0}]}""",
            ),
        )
        val messages = client.recentMessages("s1", 10)
        assertEquals(listOf("6", "7"), messages.map { it.stableId })
        assertEquals("Done.", messages[1].text)
        assertTrue(messages[1].isFinalAssistantText)
        assertTrue(server.takeRequest().path!!.contains("order=latest"))
    }

    @Test
    fun streamsTurnEvents() = runBlocking {
        val sse = buildString {
            append("event: run.started\ndata: {\"run_id\":\"run_9\",\"session_id\":\"s1\"}\n\n")
            append(": keepalive\n\n")
            append("event: tool.started\ndata: {\"tool_name\":\"terminal\"}\n\n")
            append("event: assistant.completed\ndata: {\"content\":\"All good\"}\n\n")
            append("event: run.completed\ndata: {\"completed\":true}\n\n")
            append("event: done\ndata: {}\n\n")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val events = mutableListOf<TurnEvent>()
        client.streamTurn("s 1", "hi", "key-1") { events += it }
        val req = server.takeRequest()
        assertEquals("/api/sessions/s%201/chat/stream", req.path)
        assertEquals("key-1", req.getHeader("Idempotency-Key"))
        assertEquals("""{"message":"hi"}""", req.body.readUtf8())
        assertEquals(
            listOf(
                TurnEvent.Started("run_9"),
                TurnEvent.Tool("terminal", "started"),
                TurnEvent.AssistantCompleted("All good"),
                TurnEvent.Finished("completed", null),
            ),
            events,
        )
    }

    @Test
    fun mapsForeignSessionRefusal() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"message":"owned by telegram","type":"invalid_request_error","code":"permission_denied"}}"""),
        )
        try {
            client.streamTurn("s1", "hi", "k") {}
            fail("expected refusal")
        } catch (e: HermesHttpException) {
            assertTrue(e.isForeignSession)
        }
    }
}
