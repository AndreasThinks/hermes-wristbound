package dev.wristbound.companion.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {
    private fun reason(input: String) = (Endpoint.validate(input) as Endpoint.Check.Invalid).reason

    @Test
    fun validAddressesAreNormalised() {
        assertEquals(Endpoint.Check.Valid("https://hermes.tail1234.ts.net:10443"), Endpoint.validate("  https://hermes.tail1234.ts.net:10443/ "))
        assertEquals(Endpoint.Check.Valid("http://100.64.1.2:9119"), Endpoint.validate("http://100.64.1.2:9119"))
        assertEquals(Endpoint.Check.Valid("https://example.com/hermes"), Endpoint.validate("https://example.com/hermes/"))
        assertEquals(Endpoint.Check.Valid("http://[fd7a:115c::1]:9119"), Endpoint.validate("http://[fd7a:115c::1]:9119"))
    }

    @Test
    fun anOutOfRangePortSaysSo() {
        // The Pixel report: 9119 edited into 91198.
        val r = reason("http://100.64.1.2:91198")
        assertTrue(r, r.contains("91198") && r.contains("out of range"))
        assertTrue(reason("http://host:0").contains("out of range"))
        assertTrue(reason("http://host:abc").contains("not a number"))
        assertTrue(reason("http://host:").contains("not a number"))
    }

    @Test
    fun otherMistakesAreExplained() {
        assertTrue(reason("").contains("Enter"))
        assertTrue(reason("hermes.ts.net:10443").contains("https://"))
        assertTrue(reason("ftp://hermes").contains("ftp://"))
        assertTrue(reason("https://user:pw@hermes").contains("username"))
        assertTrue(reason("https://:9119").contains("host"))
        assertTrue(reason("https://hermes/?x=1").contains("?"))
    }

    @Test
    fun sameComparesTheWholeDashboardAddress() {
        assertTrue(Endpoint.same("https://Hermes.ts.net:10443/", "https://hermes.ts.net:10443"))
        assertTrue(Endpoint.same("https://hermes.ts.net", "https://hermes.ts.net:443"))
        assertFalse(Endpoint.same("https://hermes.ts.net:10443", "https://hermes.ts.net:9119"))
        assertFalse(Endpoint.same("http://hermes.ts.net", "https://hermes.ts.net"))
        assertFalse(Endpoint.same("https://hermes.ts.net", "https://evil.example"))
        assertFalse(Endpoint.same("https://hermes.ts.net", "http://hermes:91198"))
        assertFalse(Endpoint.same(null, "https://hermes.ts.net"))
    }

    private class Raw : BoundCredentialStore {
        var bound: BoundCredentials? = null
        override fun loadBound() = bound
        override fun saveBound(bound: BoundCredentials?) { this.bound = bound }
        override var signInExpired = false
    }

    private val token = Credentials.SessionToken("secret")
    private val native = Credentials.Native(NativeTokens("at", "rt", 0.0, "basic", "andreas"))

    @Test
    fun credentialsAreOnlyHandedOutForTheDashboardThatIssuedThem() {
        val raw = Raw()
        EndpointCredentials("https://hermes.ts.net:10443", raw).save(native)
        assertEquals(native, EndpointCredentials("https://hermes.ts.net:10443/", raw).load())
        // An edited or different address never gets them.
        assertNull(EndpointCredentials("http://hermes.ts.net:91198", raw).load())
        assertNull(EndpointCredentials("https://evil.example", raw).load())
    }

    @Test
    fun signingOutOfAnotherAddressKeepsTheWorkingSignIn() {
        val raw = Raw()
        EndpointCredentials("https://a.ts.net", raw).save(token)
        EndpointCredentials("https://b.ts.net", raw).save(null)
        assertEquals(token, EndpointCredentials("https://a.ts.net", raw).load())
        EndpointCredentials("https://a.ts.net", raw).save(null)
        assertNull(raw.bound)
    }

    @Test
    fun expiryIsRememberedUntilTheNextSignIn() {
        val raw = Raw()
        val store = EndpointCredentials("https://a.ts.net", raw)
        store.save(native)
        store.expire()
        assertNull(store.load())
        assertTrue(raw.signInExpired)
        store.save(native)
        assertFalse(raw.signInExpired)
    }
}
