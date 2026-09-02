package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialRuntimeAccessGuardTest {
    @Test
    fun trialLeaseAndFinalBoundaryAreScheduledSeparately() {
        var now = 10_000L
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = { error("lease renewal must use the online callback") },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = scheduled::remove,
            onDenied = {},
            onTrialLeaseDue = {}
        )

        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.TRIAL,
                expiresAtEpochMs = 15_000L,
                trialEndsAtEpochMs = 30_000L
            )
        )

        assertEquals(setOf(5_000L, 20_000L), scheduled.values.toSet())
        assertTrue(guard.hasCurrentAccess())
    }

    @Test
    fun trialLeaseBoundaryTriggersOneOnlineCheckAndKeepsAccess() {
        var now = 10_000L
        var leaseChecks = 0
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = { error("lease renewal must use the online callback") },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = scheduled::remove,
            onDenied = {},
            onTrialLeaseDue = { leaseChecks += 1 }
        )
        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.TRIAL,
                expiresAtEpochMs = 15_000L,
                trialEndsAtEpochMs = 30_000L
            )
        )

        val leaseRunnable = scheduled.entries.single { it.value == 5_000L }.key
        now = 15_000L
        leaseRunnable.run()
        leaseRunnable.run()

        assertEquals(1, leaseChecks)
        assertTrue(guard.hasCurrentAccess())
        assertEquals(setOf(20_000L), scheduled.values.toSet())
    }

    @Test
    fun permanentProHasNoLocalExpiryOrLeaseTimer() {
        val scheduled = linkedMapOf<Runnable, Long>()
        var leaseChecks = 0
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { 10_000L },
            evaluateAccess = { CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED) },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = scheduled::remove,
            onDenied = {},
            onTrialLeaseDue = { leaseChecks += 1 }
        )

        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.PRO,
                expiresAtEpochMs = null
            )
        )

        assertTrue(scheduled.isEmpty())
        assertTrue(guard.hasCurrentAccess())
        assertEquals(0, leaseChecks)
    }

    @Test
    fun boundaryRevalidatesAuthoritativeGateAndReschedulesPermission() {
        var now = 10_000L
        var nextDecision: CommercialAccessDecision =
            CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 25_000L)
        val evaluatedAt = mutableListOf<Long>()
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = { at -> evaluatedAt += at; nextDecision },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = scheduled::remove,
            onDenied = {},
            onTrialLeaseDue = {}
        )
        guard.authorize(CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L))
        val staleExpiry = scheduled.entries.single().key

        now = 12_000L
        guard.revalidate()
        now = 15_000L
        staleExpiry.run()

        assertEquals(listOf(12_000L), evaluatedAt)
        assertTrue(guard.hasCurrentAccess())
    }

    @Test
    fun denialClearsPermissionBeforeCallback() {
        var clearedBeforeDenial = false
        var denialCount = 0
        lateinit var guard: CommercialRuntimeAccessGuard
        guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { 10_000L },
            evaluateAccess = {
                CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED)
            },
            scheduleExpiry = { _, _ -> },
            cancelExpiry = {},
            onDenied = {
                clearedBeforeDenial = !guard.hasCurrentAccess()
                denialCount += 1
            }
        )
        guard.authorize(CommercialAccessDecision.Allowed(CommercialTier.PRO, null))

        guard.revalidate()
        guard.revalidate()

        assertTrue(clearedBeforeDenial)
        assertEquals(1, denialCount)
        assertFalse(guard.hasCurrentAccess())
    }
}
