package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialRuntimeAccessGuardTest {
    @Test
    fun expiryBoundaryClearsAccessBeforeNotifyingDenial() {
        var now = 10_000L
        var clearedBeforeDenial = false
        var denialCount = 0
        var guardHasAccess = false
        val scheduled = linkedMapOf<Runnable, Long>()
        lateinit var guard: CommercialRuntimeAccessGuard
        guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = {
                CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED)
            },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = { scheduled.remove(it) },
            onDenied = {
                clearedBeforeDenial = !guard.hasCurrentAccess()
                denialCount += 1
            },
        )
        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.TRIAL,
                expiresAtEpochMs = 15_000L,
            )
        )
        guardHasAccess = guard.hasCurrentAccess()
        val expiry = scheduled.entries.single().key
        now = 15_000L
        expiry.run()
        guardHasAccess = guard.hasCurrentAccess()

        assertTrue(clearedBeforeDenial)
        assertEquals(1, denialCount)
        assertFalse(guardHasAccess)
    }

    @Test
    fun refreshBoundaryNotifiesOnceAndKeepsCurrentAccess() {
        var now = 10_000L
        var refreshCount = 0
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = {
                CommercialAccessDecision.Allowed(
                    tier = CommercialTier.PRO,
                    expiresAtEpochMs = 30_000L,
                    refreshAfterEpochMs = 15_000L,
                )
            },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = { scheduled.remove(it) },
            onDenied = {},
            onRefreshDue = { refreshCount += 1 },
        )
        guard.authorize(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.PRO,
                expiresAtEpochMs = 30_000L,
                refreshAfterEpochMs = 15_000L,
            )
        )
        val refresh = scheduled.entries.minBy { it.value }.key
        scheduled.remove(refresh)
        now = 15_000L
        refresh.run()
        refresh.run()

        assertEquals(1, refreshCount)
        assertTrue(guard.hasCurrentAccess())
    }

    @Test
    fun clearNeutralizesStaleExpiryCallback() {
        var now = 10_000L
        var denialCount = 0
        val scheduled = linkedMapOf<Runnable, Long>()
        val guard = CommercialRuntimeAccessGuard(
            nowEpochMs = { now },
            evaluateAccess = { CommercialAccessDecision.Denied(CommercialAccessDenial.NO_LICENSE) },
            scheduleExpiry = { runnable, delay -> scheduled[runnable] = delay },
            cancelExpiry = { scheduled.remove(it) },
            onDenied = { denialCount += 1 },
        )
        guard.authorize(CommercialAccessDecision.Allowed(CommercialTier.TRIAL, 15_000L))
        val stale = scheduled.entries.single().key
        guard.clear()
        now = 15_000L
        stale.run()

        assertFalse(guard.hasCurrentAccess())
        assertEquals(0, denialCount)
    }
}
