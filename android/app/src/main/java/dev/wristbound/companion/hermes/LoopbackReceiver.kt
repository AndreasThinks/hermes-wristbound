package dev.wristbound.companion.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * The RFC 8252 loopback redirect target for browser sign-in: a one-shot listener on
 * 127.0.0.1 that waits for `GET /callback?code=...&state=...`, answers with a short page and
 * hands the parameters back. Only the phone itself can reach it.
 */
class LoopbackReceiver : Closeable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    val redirectUri: String = "http://127.0.0.1:${server.localPort}$PATH"

    data class Result(val code: String?, val state: String?, val error: String?)

    /** Waits up to [timeoutMs] for the browser. Requests for other paths (a favicon) are answered and skipped. */
    suspend fun await(timeoutMs: Int = 5 * 60_000): Result = withContext(Dispatchers.IO) {
        server.soTimeout = timeoutMs
        try {
            var result: Result? = null
            while (result == null) {
                server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                    val target = line.split(' ').getOrNull(1).orEmpty()
                    val url = "http://127.0.0.1$target".toHttpUrlOrNull()
                    val ours = url != null && url.encodedPath == PATH
                    val page = if (ours) DONE_PAGE else ""
                    val status = if (ours) "200 OK" else "404 Not Found"
                    socket.getOutputStream().write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page").toByteArray(),
                    )
                    if (ours) result = Result(url.queryParameter("code"), url.queryParameter("state"), url.queryParameter("error"))
                }
            }
            result
        } catch (e: SocketTimeoutException) {
            throw SignInException("Sign-in timed out")
        } finally {
            close()
        }
    }

    override fun close() = runCatching { server.close() }.let { }

    private companion object {
        const val PATH = "/callback"
        const val DONE_PAGE = "<!doctype html><meta name=viewport content=\"width=device-width\">" +
            "<title>Wristbound</title><p style=\"font:16px sans-serif;margin:2em\">Signed in. You can return to Wristbound.</p>"
    }
}
