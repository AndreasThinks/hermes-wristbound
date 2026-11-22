package dev.wristbound.companion.core

import dev.wristbound.companion.core.QuickLaunchSync.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickLaunchSyncTest {
    @Test
    fun anUpgradeKeepsTheWatchsOwnChoice() {
        // The phone never chose: the watch's saved choice is adopted, not reset to Home.
        val plan = QuickLaunchSync.onHello(phone = null, fromWatch = 3)
        assertEquals(QuickLaunchSync.Hello(adopt = 3, send = null, confirmed = 3), plan)
        assertEquals(Status.APPLIED, QuickLaunchSync.status(plan.adopt, plan.confirmed))
    }

    @Test
    fun anOldWatchThatReportsNothingLeavesThePhoneUnset() {
        assertEquals(QuickLaunchSync.Hello(adopt = null, send = null, confirmed = null), QuickLaunchSync.onHello(null, null))
        assertEquals(Status.WATCH_OWN, QuickLaunchSync.status(null, null))
    }

    @Test
    fun thePhonesChoiceIsSentWhenTheWatchDiffers() {
        assertEquals(QuickLaunchSync.Hello(adopt = null, send = 2, confirmed = null), QuickLaunchSync.onHello(phone = 2, fromWatch = 0))
    }

    @Test
    fun aChoiceTheWatchAlreadyHoldsIsConfirmedWithoutSending() {
        assertEquals(QuickLaunchSync.Hello(adopt = null, send = null, confirmed = 4), QuickLaunchSync.onHello(phone = 4, fromWatch = 4))
    }

    @Test
    fun aSavedChoiceIsPendingUntilTheWatchAcknowledgesIt() {
        assertEquals(Status.PENDING, QuickLaunchSync.status(phone = 1, watch = null))
        assertEquals(Status.PENDING, QuickLaunchSync.status(phone = 1, watch = 0))
        assertEquals(Status.APPLIED, QuickLaunchSync.status(phone = 1, watch = 1))
    }
}
