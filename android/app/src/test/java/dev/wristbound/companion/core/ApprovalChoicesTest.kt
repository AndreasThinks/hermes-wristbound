package dev.wristbound.companion.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalChoicesTest {
    private fun request(vararg choices: String) = ApprovalRequest("r1", "rm -rf build", "", choices.toList())

    @Test
    fun theWatchAndNotificationOfferOnlyAllowOnceAndDeny() {
        assertEquals(listOf("once", "deny"), request("once", "session", "always", "deny").wristChoices)
    }

    @Test
    fun denyIsAlwaysOffered() {
        assertEquals(listOf("deny"), request("session", "always").wristChoices)
        assertEquals(listOf("deny"), request().wristChoices)
    }
}
