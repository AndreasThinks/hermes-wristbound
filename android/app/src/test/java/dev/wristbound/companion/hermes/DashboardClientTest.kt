package dev.wristbound.companion.hermes

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DashboardClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoryStore(var value: Credentials? = null) : CredentialStore {
        override fun load() = value
        override fun save(credentials: Credentials?) { value = credentials }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun native(access: String, refresh: String = "rt-1", expiresAt: Double = 0.0) =
        Credentials.Native(NativeTokens(access, refresh, expiresAt, "basic", "u1"))

    private fun client(store: MemoryStore) = DashboardClient(DashboardAuth(server.url("/").toString(), store))

    @Test
    fun listsSessionsIncludingTelegramWithBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"sessions":[{"id":"20261022_tg","source":"telegram","title":"Groceries","last_active":20.5,"is_active":true},
               {"id":"s2","source":"webui","title":null,"preview":"hello\nworld"}],"total":2,"limit":50,"offset":0,"storage":{}}""",
        ))
        val sessions = client(MemoryStore(native("at-1"))).listSessions(50)
        val req = server.takeRequest()
        assertEquals("Bearer at-1", req.getHeader("Authorization"))
        assertEquals("/api/sessions?limit=50&order=recent", req.path)
        assertTrue(sessions[0].isTelegram)
        assertEquals("hello", sessions[1].displayTitle)
    }

    @Test
    fun loopbackDashboardTakesTheSessionToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"session_id":"s1","messages":[
            {"id":7,"role":"assistant","content":"Done.","timestamp":2.0},
            {"id":6,"role":"user","content":"Do it","timestamp":1.0}],"pagination":{}}"""))
        val messages = client(MemoryStore(Credentials.SessionToken("tok"))).recentMessages("s 1", 10)
        val req = server.takeRequest()
        assertEquals("tok", req.getHeader("X-Hermes-Session-Token"))
        assertNull(req.getHeader("Authorization"))
        assertEquals("/api/sessions/s%201/messages?limit=10&order=latest&inline_images=false", req.path)
        assertEquals(listOf("6", "7"), messages.map { it.stableId })
    }

    @Test
    fun expiredTokenIsRefreshedOnceAndTheRotatedTokenSaved() = runBlocking {
        val store = MemoryStore(native("old-at", "old-rt"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/native/refresh" -> MockResponse().setBody(
                    """{"access_token":"new-at","refresh_token":"new-rt","token_type":"Bearer","expires_at":0,"provider":"basic","user_id":"u1"}""",
                )
                request.getHeader("Authorization") == "Bearer new-at" -> MockResponse().setBody("""{"sessions":[]}""")
                else -> MockResponse().setResponseCode(401).setBody("""{"detail":"Unauthorized"}""")
            }
        }
        client(store).listSessions(5)
        assertEquals(listOf("/api/sessions?limit=5&order=recent", "/auth/native/refresh", "/api/sessions?limit=5&order=recent"),
            List(3) { server.takeRequest().path })
        assertEquals(native("new-at", "new-rt"), store.value)
    }

    @Test
    fun deadRefreshTokenSignsOut() = runBlocking {
        val store = MemoryStore(native("old-at", "old-rt"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
                .setBody("""{"error":"session_expired","detail":"Refresh token expired"}""")
        }
        val error = runCatching { client(store).listSessions(5) }.exceptionOrNull() as HermesHttpException
        assertTrue(error.isAuth)
        assertNull(store.value)
    }

    @Test
    fun passwordSignInRunsTheNativeFlowWithoutABrowser() = runBlocking {
        val store = MemoryStore()
        var verifierChallenge = ""
        var state = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/auth/native/authorize" -> {
                        verifierChallenge = url.queryParameter("code_challenge")!!
                        state = url.queryParameter("state")!!
                        assertEquals("basic", url.queryParameter("provider"))
                        assertEquals("S256", url.queryParameter("code_challenge_method"))
                        MockResponse().setResponseCode(302).setHeader("Location", "/login")
                            .setHeader("Set-Cookie", "hermes_pkce=broker123; Path=/; HttpOnly")
                    }
                    "/auth/password-login" -> {
                        assertTrue(request.getHeader("Cookie")!!.contains("hermes_pkce=broker123"))
                        val body = HermesHttp.json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        assertEquals("andreas", body["username"]!!.jsonPrimitive.content)
                        MockResponse().setBody("""{"ok":true,"next":"http://127.0.0.1/wristbound?code=gw-code&state=$state"}""")
                    }
                    "/auth/native/token" -> {
                        val body = HermesHttp.json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        assertEquals("gw-code", body["code"]!!.jsonPrimitive.content)
                        val verifier = body["code_verifier"]!!.jsonPrimitive.content
                        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
                        assertEquals(verifierChallenge, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
                        MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_at":99.0,"provider":"basic","user_id":"u1"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val auth = DashboardAuth(server.url("/").toString(), store)
        auth.signInWithPassword("basic", "andreas", "pw")
        assertEquals(Credentials.Native(NativeTokens("at", "rt", 99.0, "basic", "u1")), store.value)
    }

    @Test
    fun wrongPasswordIsASignInError() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/auth/native/authorize" -> MockResponse().setResponseCode(302).setHeader("Location", "/login")
                else -> MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid credentials"}""")
            }
        }
        val store = MemoryStore()
        val error = runCatching { DashboardAuth(server.url("/").toString(), store).signInWithPassword("basic", "a", "b") }.exceptionOrNull()
        assertEquals("Wrong username or password", (error as SignInException).message)
        assertNull(store.value)
    }

    @Test
    fun gatewaySocketUsesATicketAndSpeaksJsonRpc() = runBlocking {
        val serverSide = FakeGatewayServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/api/auth/ws-ticket" -> {
                    assertEquals("Bearer at-1", request.getHeader("Authorization"))
                    MockResponse().setBody("""{"ticket":"tkt-1","ttl_seconds":30}""")
                }
                "/api/ws" -> {
                    assertEquals("tkt-1", request.requestUrl!!.queryParameter("ticket"))
                    MockResponse().withWebSocketUpgrade(serverSide)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val gateway = client(MemoryStore(native("at-1"))).openGateway()
        val result = gateway.call("session.resume", params("session_id" to "20261022_tg"))
        assertEquals("rt1", result["session_id"]!!.jsonPrimitive.content)
        val event = withTimeout(5_000) { gateway.events.receive() }
        assertEquals(GatewayEvent("message.complete", "rt1", HermesHttp.json.parseToJsonElement("""{"text":"hi"}""") as JsonObject), event)
        val error = runCatching { gateway.call("prompt.submit", params("session_id" to "nope")) }.exceptionOrNull()
        assertEquals(4001, (error as GatewayRpcException).code)
        gateway.close()
        // Let the close handshake finish so the mock server can shut down cleanly.
        assertTrue(serverSide.closed.await(5, TimeUnit.SECONDS))
    }

    /** Server side of /api/ws: ready frame, then answers by method. */
    private class FakeGatewayServer : WebSocketListener() {
        val closed = CountDownLatch(1)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed.countDown()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val req = HermesHttp.json.parseToJsonElement(text) as JsonObject
            val id = req["id"]!!.jsonPrimitive.content
            when (req["method"]!!.jsonPrimitive.content) {
                "session.resume" -> {
                    // Two frames in one message, newline-delimited, as the gateway may batch them.
                    webSocket.send(
                        """{"jsonrpc":"2.0","id":$id,"result":{"session_id":"rt1","running":false}}""" + "\n" +
                            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"rt1","payload":{"text":"hi"}}}""",
                    )
                }
                else -> webSocket.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":4001,"message":"session not found"}}""")
            }
        }
    }

    @Test
    fun hermesAudioIsStreamedFromItsServerPath() = runBlocking {
        server.enqueue(MockResponse().setBody("OggS-bytes"))
        val clip = client(MemoryStore(native("at-1"))).fetchAudio("/home/h/.hermes/cache/audio/tts 1.ogg", tmp.root)
        val req = server.takeRequest()
        assertEquals("/api/files/stream?path=%2Fhome%2Fh%2F.hermes%2Fcache%2Faudio%2Ftts%201.ogg", req.path)
        assertEquals("audio/ogg", clip.mimeType)
        assertEquals("OggS-bytes", clip.file.readText())
        // Cached: a second play does not download again.
        client(MemoryStore(native("at-1"))).fetchAudio("/home/h/.hermes/cache/audio/tts 1.ogg", tmp.root)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun hermesVoiceSpeaksTheReplyText() = runBlocking {
        val mp3 = Base64.getEncoder().encodeToString("ID3-audio".toByteArray())
        server.enqueue(MockResponse().setBody("""{"ok":true,"data_url":"data:audio/mpeg;base64,$mp3","mime_type":"audio/mpeg","provider":"edge"}"""))
        val clip = client(MemoryStore(native("at-1"))).speak("Disk is 92% full.", "msg-4", tmp.root)
        val req = server.takeRequest()
        assertEquals("/api/audio/speak", req.path)
        assertEquals("""{"text":"Disk is 92% full."}""", req.body.readUtf8())
        assertEquals("ID3-audio", clip.file.readText())
        assertTrue(clip.file.name.endsWith(".mp3"))
    }
}
