package dev.wristbound.companion.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** How the phone proves itself to the Hermes dashboard. */
@Serializable
sealed class Credentials {
    /**
     * A dashboard on a loopback bind (no sign-in gate) accepts its session token, which the
     * desktop app sets through `HERMES_DASHBOARD_SESSION_TOKEN`.
     */
    @Serializable @SerialName("session_token")
    data class SessionToken(val token: String) : Credentials()

    /** Tokens from the dashboard's native-app sign-in (RFC 8252), the flow the desktop and Hermes-Relay use. */
    @Serializable @SerialName("native")
    data class Native(val tokens: NativeTokens) : Credentials()
}

interface CredentialStore {
    fun load(): Credentials?
    fun save(credentials: Credentials?)
    /** The dashboard ended the sign-in (a dead refresh token), as opposed to the user signing out. */
    fun expire() = save(null)
}

/** The user rejected or abandoned sign-in, or the dashboard refused the credentials. */
class SignInException(message: String) : IOException(message)

/** An in-progress browser sign-in. The verifier never leaves this object. */
class BrowserSignIn internal constructor(val url: String, internal val verifier: String, internal val state: String)

/**
 * Sign-in and credential handling for the Hermes dashboard (`hermes dashboard`, port 9119 by
 * default), which is what the desktop app and Hermes-Relay talk to.
 *
 * Gated dashboards (any non-loopback bind, including a tailnet `dashboard.public_url`) use the
 * native-app flow: PKCE at `/auth/native/authorize`, a one-time code, `/auth/native/token` for
 * bearer tokens and `/auth/native/refresh` to rotate them. Password providers are completed
 * here without a browser; OAuth providers go through the system browser and a loopback
 * redirect ([LoopbackReceiver]). Loopback dashboards take the session token instead.
 */
class DashboardAuth(
    baseUrl: String,
    private val store: CredentialStore,
    http: OkHttpClient = HermesHttp.base,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid Hermes URL: $baseUrl")

    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val random = SecureRandom()
    private val json = HermesHttp.json

    val credentials: Credentials? get() = store.load()

    suspend fun status(): DashboardStatus = withContext(Dispatchers.IO) {
        val body = http.newCall(Request.Builder().url(url("api/status")).build()).execute().use(HermesHttp::body)
        HermesHttp.decode(DashboardStatus.serializer(), body, "a status")
    }

    suspend fun providers(): List<AuthProvider> = withContext(Dispatchers.IO) {
        val body = http.newCall(Request.Builder().url(url("api/auth/providers")).build()).execute().use(HermesHttp::body)
        HermesHttp.decode(AuthProviderList.serializer(), body, "a provider list").providers
    }

    fun useSessionToken(token: String) = store.save(Credentials.SessionToken(token.trim()))

    fun signOut() = store.save(null)

    /**
     * Sign in with a username and password provider (`dashboard.basic_auth`). Runs the same
     * native flow as the desktop's browser sign-in, minus the browser: authorize, post the
     * password with the flow's cookie, and redeem the code the dashboard hands back.
     */
    suspend fun signInWithPassword(provider: String, username: String, password: String): NativeTokens =
        withContext(Dispatchers.IO) {
            val pending = beginBrowserSignIn(HEADLESS_REDIRECT, provider)
            val jar = MemoryCookieJar()
            val client = http.newBuilder().cookieJar(jar).build()
            client.newCall(Request.Builder().url(pending.url).build()).execute().use { response ->
                if (response.code !in 300..399) throw HermesHttp.error(response)
            }
            val payload = buildJsonObject {
                put("provider", provider)
                put("username", username)
                put("password", password)
                put("next", "")
            }
            val login = Request.Builder().url(url("auth/password-login"))
                .post(payload.toString().toRequestBody(HermesHttp.JSON_MEDIA)).build()
            val next = client.newCall(login).execute().use { response ->
                if (response.code == 401) throw SignInException("Wrong username or password")
                if (response.code == 429) throw SignInException("Too many attempts; wait a minute")
                val obj = json.parseToJsonElement(HermesHttp.body(response)) as? JsonObject
                (obj?.get("next") as? JsonPrimitive)?.contentOrNull
            } ?: throw SignInException("Dashboard did not return a sign-in code")
            val redirect = next.toHttpUrlOrNull() ?: throw SignInException("Unexpected sign-in redirect")
            completeBrowserSignIn(pending, redirect.queryParameter("code"), redirect.queryParameter("state"))
        }

    /** Start a browser sign-in that returns to [redirectUri], a `http://127.0.0.1:<port>/...` listener. */
    fun beginBrowserSignIn(redirectUri: String, provider: String? = null): BrowserSignIn {
        val verifier = base64Url(randomBytes(32))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = base64Url(randomBytes(24))
        val authorize = url("auth/native/authorize").newBuilder()
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("state", state)
            .apply { provider?.takeIf { it.isNotBlank() }?.let { addQueryParameter("provider", it) } }
            .build()
        return BrowserSignIn(authorize.toString(), verifier, state)
    }

    /** Redeem the code from the loopback redirect. A mismatched state means the redirect was not ours. */
    suspend fun completeBrowserSignIn(pending: BrowserSignIn, code: String?, state: String?): NativeTokens =
        withContext(Dispatchers.IO) {
            if (code.isNullOrBlank()) throw SignInException("Sign-in was cancelled")
            if (state != pending.state) throw SignInException("Sign-in response did not match; try again")
            val payload = buildJsonObject {
                put("code", code)
                put("code_verifier", pending.verifier)
            }
            val request = Request.Builder().url(url("auth/native/token"))
                .post(payload.toString().toRequestBody(HermesHttp.JSON_MEDIA)).build()
            val tokens = http.newCall(request).execute().use { response ->
                if (response.code == 400) throw SignInException("Sign-in expired; try again")
                HermesHttp.decode(NativeTokens.serializer(), HermesHttp.body(response), "sign-in tokens")
            }
            store.save(Credentials.Native(tokens))
            tokens
        }

    /**
     * Rotate the refresh token. Hermes runs reuse detection, so two concurrent refreshes with the
     * same token would revoke the session: callers are serialised, and a caller whose token was
     * already rotated by another thread just picks up the new one.
     */
    @Synchronized
    fun refreshBlocking(staleAccessToken: String?): Credentials? {
        val current = store.load() as? Credentials.Native ?: return store.load()
        if (staleAccessToken != null && current.tokens.access_token != staleAccessToken) return current
        if (current.tokens.refresh_token.isBlank()) return null
        val payload = buildJsonObject {
            put("refresh_token", current.tokens.refresh_token)
            put("provider", current.tokens.provider)
        }
        val request = Request.Builder().url(url("auth/native/refresh"))
            .post(payload.toString().toRequestBody(HermesHttp.JSON_MEDIA)).build()
        return http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> {
                    val tokens = HermesHttp.decode(NativeTokens.serializer(), response.body?.string().orEmpty(), "sign-in tokens")
                    Credentials.Native(tokens).also { store.save(it) }
                }
                // The refresh token is dead: sign-in is needed again. 5xx keeps the tokens.
                response.code == 401 -> { store.expire(); null }
                else -> throw HermesHttp.error(response)
            }
        }
    }

    /** Current credentials, refreshed first when the access token is about to expire. */
    fun freshCredentials(): Credentials? {
        val current = store.load()
        if (current is Credentials.Native && current.tokens.expires_at > 0 &&
            current.tokens.expires_at - nowSeconds() < REFRESH_MARGIN_SECONDS
        ) {
            return refreshBlocking(current.tokens.access_token)
        }
        return current
    }

    /**
     * Adds the dashboard credential to every request, and on a 401 refreshes once and retries.
     * Requests that are part of sign-in itself go through [http] directly and skip this.
     */
    val interceptor: Interceptor = Interceptor { chain ->
        val credentials = freshCredentials()
        val response = chain.proceed(chain.request().withCredentials(credentials))
        if (response.code != 401 || credentials !is Credentials.Native) return@Interceptor response
        response.close()
        val refreshed = refreshBlocking(credentials.tokens.access_token)
        chain.proceed(chain.request().withCredentials(refreshed))
    }

    /**
     * The query parameter that authenticates a `/api/ws` upgrade. Signed-in sessions mint a
     * single-use ticket (30 s TTL), as the desktop does; a loopback dashboard takes its session
     * token directly.
     */
    suspend fun webSocketCredential(authed: OkHttpClient): Pair<String, String> = withContext(Dispatchers.IO) {
        when (val c = freshCredentials()) {
            is Credentials.SessionToken -> "token" to c.token
            is Credentials.Native -> {
                val request = Request.Builder().url(url("api/auth/ws-ticket"))
                    .post(ByteArray(0).toRequestBody(null)).build()
                val body = authed.newCall(request).execute().use(HermesHttp::body)
                val ticket = ((json.parseToJsonElement(body) as? JsonObject)?.get("ticket") as? JsonPrimitive)?.contentOrNull
                    ?: throw IOException("Dashboard returned no WebSocket ticket")
                "ticket" to ticket
            }
            null -> throw HermesHttpException(401, "signed_out", "Sign in to Hermes")
        }
    }

    private fun Request.withCredentials(credentials: Credentials?): Request = when (credentials) {
        is Credentials.SessionToken -> newBuilder().header("X-Hermes-Session-Token", credentials.token).build()
        is Credentials.Native -> newBuilder().header("Authorization", "Bearer ${credentials.tokens.access_token}").build()
        null -> this
    }

    private fun url(path: String): HttpUrl = base.newBuilder().addEncodedPathSegments(path).build()

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies.removeAll { old -> cookies.any { it.name == old.name } }
            this.cookies += cookies
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    companion object {
        private const val REFRESH_MARGIN_SECONDS = 60.0
        /** Never contacted: the password flow reads the code out of the redirect URL it is handed. */
        private const val HEADLESS_REDIRECT = "http://127.0.0.1/wristbound"

        /** RFC 7636 base64url without padding. Plain JVM so it works in unit tests too. */
        fun base64Url(bytes: ByteArray): String =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
