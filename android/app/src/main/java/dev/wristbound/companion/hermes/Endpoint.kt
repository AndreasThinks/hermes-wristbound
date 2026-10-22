package dev.wristbound.companion.hermes

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The Hermes dashboard address the user typed, checked before anything is saved or sent to it.
 * See [validate].
 */
object Endpoint {
    sealed class Check {
        /** [url] is normalised: trimmed, no trailing slash. */
        data class Valid(val url: String) : Check()
        data class Invalid(val reason: String) : Check()
    }

    private val SHAPE = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(.*)$""")

    /**
     * Check a typed dashboard URL and say exactly what is wrong with it. OkHttp alone would
     * reject `:91198` without saying why, so the scheme, host and port are checked first.
     */
    fun validate(input: String): Check {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return Check.Invalid("Enter the dashboard URL.")
        val match = SHAPE.matchEntire(text)
            ?: return Check.Invalid("Start the URL with https:// (or http:// on a trusted network).")
        val (scheme, authority, rest) = match.destructured
        if (!scheme.equals("https", true) && !scheme.equals("http", true)) {
            return Check.Invalid("Use an https:// or http:// URL, not $scheme://.")
        }
        if ('@' in authority) return Check.Invalid("Leave the username and password out of the URL.")
        val hostPort = Regex("""^(\[[^\]]*\]|[^:]*)(?::(.*))?$""").matchEntire(authority)
        val host = hostPort?.groupValues?.get(1).orEmpty()
        if (host.isBlank()) return Check.Invalid("The URL has no host name.")
        val portText = hostPort?.groups?.get(2)?.value
        if (portText != null) {
            val port = portText.toIntOrNull()
            if (portText.isEmpty() || port == null || !portText.all(Char::isDigit)) {
                return Check.Invalid("The port \"$portText\" is not a number.")
            }
            if (port !in 1..65535) {
                return Check.Invalid("Port $port is out of range (1 to 65535). The dashboard is usually on 9119, or 10443 behind tailscale serve.")
            }
        }
        if ('?' in rest || '#' in rest) return Check.Invalid("Remove the ? or # part of the URL.")
        val url = text.toHttpUrlOrNull() ?: return Check.Invalid("That is not a valid URL.")
        return Check.Valid(url.toString().trimEnd('/'))
    }

    /** Whether [a] and [b] name the same dashboard (scheme, host, port and path). */
    fun same(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = a.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        val y = b.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        return x.scheme == y.scheme && x.host == y.host && x.port == y.port &&
            x.encodedPath.trimEnd('/') == y.encodedPath.trimEnd('/')
    }

    /** The address to show the user: no credentials, query or fragment. */
    fun display(url: String): String = url.toHttpUrlOrNull()
        ?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()
        ?.toString()?.trimEnd('/') ?: url
}

/** Saved credentials and the dashboard that issued them. */
data class BoundCredentials(val endpoint: String, val credentials: Credentials)

/** Raw storage for [EndpointCredentials]; on the phone, the encrypted settings. */
interface BoundCredentialStore {
    fun loadBound(): BoundCredentials?
    fun saveBound(bound: BoundCredentials?)
    /** Remember that sign-in lapsed on its own (not an explicit sign-out), to say so. */
    var signInExpired: Boolean
}

/**
 * The credentials for one dashboard. Credentials are stored with the address that issued them
 * and are only ever handed out for that address, so editing the URL can never send a token or
 * password to a different host.
 */
class EndpointCredentials(private val endpoint: String, private val raw: BoundCredentialStore) : CredentialStore {
    override fun load(): Credentials? = raw.loadBound()?.takeIf { Endpoint.same(it.endpoint, endpoint) }?.credentials

    override fun save(credentials: Credentials?) {
        if (credentials == null) {
            // Signing out of one dashboard never forgets another's sign-in.
            if (raw.loadBound()?.let { Endpoint.same(it.endpoint, endpoint) } == true) raw.saveBound(null)
            return
        }
        raw.saveBound(BoundCredentials(endpoint, credentials))
        raw.signInExpired = false
    }

    override fun expire() {
        if (load() == null) return
        save(null)
        raw.signInExpired = true
    }
}
