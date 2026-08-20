package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialAccessRefreshTest {
    @Test
    fun localProWaitsUntilSignedRenewalPoint() {
        assertFalse(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 20_000L,
                retryNotBeforeEpochMs = null,
                nowEpochMs = 19_999L,
            )
        )
        assertTrue(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 20_000L,
                retryNotBeforeEpochMs = null,
                nowEpochMs = 20_000L,
            )
        )
    }

    @Test
    fun transientFailureUsesOneDayRetryCooldownButNeverDelaysMissingAccess() {
        val now = 20_000L
        val retryAt = CommercialAccessRefreshPolicy.nextRetryNotBefore(now)

        assertFalse(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = 10_000L,
                retryNotBeforeEpochMs = retryAt,
                nowEpochMs = retryAt - 1,
            )
        )
        assertTrue(
            CommercialAccessRefreshPolicy.shouldRequestRemote(
                localRefreshAfterEpochMs = null,
                retryNotBeforeEpochMs = retryAt,
                nowEpochMs = now,
            )
        )
        assertEquals(Long.MAX_VALUE, CommercialAccessRefreshPolicy.nextRetryNotBefore(Long.MAX_VALUE))
    }
}
