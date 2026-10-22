package dev.wristbound.companion.core

import dev.wristbound.companion.hermes.Credentials
import dev.wristbound.companion.hermes.NativeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {
    private val url = "https://hermes.ts.net:10443"
    private fun native(access: String = "at-1") = Credentials.Native(NativeTokens(access, "rt", 0.0, "basic", "andreas"))
    private val unknown = BackendState(BackendLink.UNKNOWN)

    @Test
    fun passwordAndBrowserSignInShowTheSameCard() {
        // Both flows end in native tokens; the card never shows them.
        val view = Account.view(native(), url, expired = false, unknown)
        assertTrue(view.signedIn)
        assertEquals("Signed in", view.heading)
        assertEquals("andreas via basic\nhttps://hermes.ts.net:10443", view.detail)
        assertFalse(view.detail.contains("at-1") || view.detail.contains("rt"))
    }

    @Test
    fun aSessionTokenIsAConfiguredStateWithoutTheToken() {
        val view = Account.view(Credentials.SessionToken("secret-token"), url, expired = false, unknown)
        assertTrue(view.signedIn)
        assertEquals("Using a session token", view.heading)
        assertFalse(view.detail.contains("secret"))
    }

    @Test
    fun savedCredentialsAreNotAConnection() {
        assertEquals("Not checked yet", Account.view(native(), url, false, unknown).connection)
        assertEquals("Connected", Account.view(native(), url, false, BackendState(BackendLink.CONNECTED)).connection)
    }

    @Test
    fun anOutageKeepsTheSignInAndSaysOffline() {
        val view = Account.view(native(), url, false, BackendState(BackendLink.OFFLINE))
        assertTrue(view.signedIn)
        assertTrue(view.connection.startsWith("Offline"))
    }

    @Test
    fun aTokenRefreshKeepsTheCardStable() {
        val before = Account.view(native("at-1"), url, false, BackendState(BackendLink.CONNECTED))
        val after = Account.view(native("at-2"), url, false, BackendState(BackendLink.CONNECTED))
        assertEquals(before, after)
    }

    @Test
    fun expiryAndSignOutBringBackTheForm() {
        val expired = Account.view(null, url, expired = true, BackendState(BackendLink.SIGN_IN_REQUIRED))
        assertFalse(expired.signedIn)
        assertEquals("Sign-in expired", expired.heading)
        val signedOut = Account.view(null, url, expired = false, BackendState(BackendLink.SIGNED_OUT))
        assertFalse(signedOut.signedIn)
        assertEquals("Not signed in", signedOut.heading)
        assertEquals("Not set up", Account.view(null, "", false, unknown).heading)
    }

    @Test
    fun recreationRendersTheSameFromSavedState() {
        val a = Account.view(native(), url, false, BackendState(BackendLink.CONNECTED))
        val b = Account.view(native(), url, false, BackendState(BackendLink.CONNECTED))
        assertEquals(a, b)
    }
}
