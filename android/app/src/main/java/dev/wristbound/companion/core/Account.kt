package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.Credentials
import dev.wristbound.companion.hermes.Endpoint

/** The outcome of the latest request to Hermes, kept apart from whether credentials are saved. */
enum class BackendLink { UNKNOWN, SIGNED_OUT, CHECKING, CONNECTED, OFFLINE, SIGN_IN_REQUIRED, ERROR }

data class BackendState(val link: BackendLink, val detail: String = "")

/**
 * What the setup screen shows about the account. Pure, so every state (signed in, session
 * token, expired, offline, refreshed, recreated) is decided the same way and can be tested.
 */
data class AccountView(
    /** Show the account card; otherwise show the sign-in form. */
    val signedIn: Boolean,
    val heading: String,
    /** Account and endpoint, never a secret. */
    val detail: String,
    val connection: String,
)

object Account {
    fun view(credentials: Credentials?, endpoint: String, expired: Boolean, backend: BackendState): AccountView {
        val where = if (endpoint.isBlank()) "" else Endpoint.display(endpoint)
        if (credentials == null) {
            val heading = when {
                expired -> "Sign-in expired"
                endpoint.isBlank() -> "Not set up"
                else -> "Not signed in"
            }
            val connection = if (expired) "Hermes ended the sign-in. Sign in again." else "Sign in to connect."
            return AccountView(false, heading, where, connection)
        }
        val (heading, who) = when (credentials) {
            is Credentials.Native -> "Signed in" to listOf(credentials.tokens.user_id, credentials.tokens.provider)
                .filter { it.isNotBlank() }.joinToString(" via ")
            is Credentials.SessionToken -> "Using a session token" to ""
        }
        val detail = listOf(who, where).filter { it.isNotBlank() }.joinToString("\n")
        val connection = when (backend.link) {
            BackendLink.CONNECTED -> "Connected"
            BackendLink.CHECKING -> "Checking..."
            BackendLink.OFFLINE -> "Offline: cannot reach Hermes. Is Tailscale connected?"
            BackendLink.SIGN_IN_REQUIRED -> "Hermes rejected the sign-in. Sign out and sign in again."
            BackendLink.ERROR -> backend.detail.ifBlank { "Hermes reported an error" }
            BackendLink.UNKNOWN, BackendLink.SIGNED_OUT -> "Not checked yet"
        }
        return AccountView(true, heading, detail, connection)
    }
}
