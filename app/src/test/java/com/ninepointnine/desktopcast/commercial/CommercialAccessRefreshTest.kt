package com.ninepointnine.desktopcast.commercial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommercialAccessRefreshTest {
    @Test
    fun `concurrent startup and settings refresh share one request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = SingleFlightCommercialEntitlementCheck(
            operation = { nowEpochMs ->
                calls += 1
                started.complete(Unit)
                release.await()
                nowEpochMs
            },
            scope = this
        )

        val startup = async { refresh.refresh(100L) }
        started.await()
        val settings = async(start = CoroutineStart.UNDISPATCHED) { refresh.refresh(200L) }

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(100L, startup.await())
        assertEquals(100L, settings.await())

        assertEquals(300L, refresh.refresh(300L))
        assertEquals(2, calls)
    }

    @Test
    fun `cancelled service waiter does not cancel the shared cloud request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val refresh = SingleFlightCommercialEntitlementCheck(
            operation = { nowEpochMs ->
                calls += 1
                started.complete(Unit)
                release.await()
                nowEpochMs
            },
            scope = requestScope
        )

        val service = async { refresh.refresh(100L) }
        started.await()
        service.cancelAndJoin()
        val settings = async(start = CoroutineStart.UNDISPATCHED) { refresh.refresh(200L) }

        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(100L, settings.await())
        requestScope.cancel()
    }

    @Test
    fun `coordinator preserves local trial and exposes its signed remaining time on network failure`() =
        runBlocking {
            val now = 10_000L
            val trialEndsAt = 20_000L
            val snapshots = mutableListOf<EntitlementSnapshot>()
            val coordinator = CommercialEntitlementCoordinator(
                gateway = StubGateway(
                    queryResult = EntitlementQueryResult.Failure(CommercialFailure.NETWORK)
                ),
                accessGate = CommercialAccessGate {
                    CommercialAccessDecision.Allowed(
                        tier = CommercialTier.TRIAL,
                        expiresAtEpochMs = trialEndsAt,
                        trialEndsAtEpochMs = trialEndsAt
                    )
                },
                nowEpochMs = { now }
            )
            coordinator.addListener(snapshots::add)

            val result = coordinator.queryEntitlement(forceRemote = true)

            val ready = result as EntitlementQueryResult.Ready
            assertEquals(EntitlementState.Trial(trialEndsAt, 10_000L), ready.snapshot.entitlement)
            assertEquals(ready.snapshot, snapshots.single())
            val diagnostic = coordinator.diagnostic()
            assertEquals(CommercialTier.TRIAL, diagnostic.tier)
            assertEquals(trialEndsAt, diagnostic.trialEndsAtEpochMs)
            assertEquals(10_000L, diagnostic.remainingMillis)
        }

    @Test
    fun `listener failure cannot turn a successful entitlement query into unknown`() = runBlocking {
        val snapshot = EntitlementSnapshot(EntitlementState.Pro, quote = null)
        val coordinator = CommercialEntitlementCoordinator(
            gateway = StubGateway(queryResult = EntitlementQueryResult.Ready(snapshot)),
            accessGate = CommercialAccessGate {
                CommercialAccessDecision.Allowed(CommercialTier.PRO, expiresAtEpochMs = null)
            },
            nowEpochMs = { 1_000L }
        )
        coordinator.addListener { error("render failed") }

        val result = coordinator.queryEntitlement()

        assertEquals(EntitlementQueryResult.Ready(snapshot), result)
        assertEquals(snapshot, coordinator.currentSnapshot())
    }

    @Test
    fun `lifecycle revocation replaces the shared pro snapshot`() = runBlocking {
        val pro = EntitlementSnapshot(EntitlementState.Pro, quote = null)
        val gateway = StubGateway(EntitlementQueryResult.Ready(pro))
        var localAccess: CommercialAccessDecision = CommercialAccessDecision.Allowed(
            CommercialTier.PRO,
            expiresAtEpochMs = null
        )
        val snapshots = mutableListOf<EntitlementSnapshot>()
        val coordinator = CommercialEntitlementCoordinator(
            gateway = gateway,
            accessGate = CommercialAccessGate { localAccess },
            nowEpochMs = { 1_000L }
        )
        coordinator.addListener(snapshots::add)
        assertEquals(EntitlementQueryResult.Ready(pro), coordinator.queryEntitlement())

        gateway.queryResult = EntitlementQueryResult.Failure(
            CommercialFailure.ENTITLEMENT_REVOKED
        )
        localAccess = CommercialAccessDecision.Denied(
            CommercialAccessDenial.ENTITLEMENT_REVOKED
        )

        assertEquals(
            CommercialAccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED),
            coordinator.recheckEntitlement()
        )
        val revoked = EntitlementSnapshot(
            EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
            quote = null
        )
        assertEquals(revoked, coordinator.currentSnapshot())
        assertEquals(listOf(pro, revoked), snapshots)
    }

    @Test
    fun `authoritative device mismatch overrides local pro and projects an error snapshot`() =
        runBlocking {
            val snapshots = mutableListOf<EntitlementSnapshot>()
            val coordinator = CommercialEntitlementCoordinator(
                gateway = StubGateway(
                    EntitlementQueryResult.Failure(CommercialFailure.DEVICE_MISMATCH)
                ),
                accessGate = CommercialAccessGate {
                    CommercialAccessDecision.Allowed(
                        tier = CommercialTier.PRO,
                        expiresAtEpochMs = null
                    )
                },
                nowEpochMs = { 1_000L }
            )
            coordinator.addListener(snapshots::add)

            val check = coordinator.recheckEntitlementWithDecision()

            assertEquals(
                CommercialAccessDecision.Denied(CommercialAccessDenial.DEVICE_MISMATCH),
                check.access
            )
            assertEquals(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Error(CommercialFailure.DEVICE_MISMATCH),
                    quote = null,
                    pendingPayment = null
                ),
                snapshots.last()
            )
        }

    @Test
    fun `authoritative denial cannot be reopened by local evaluation or fallback query`() =
        runBlocking {
            val pro = EntitlementSnapshot(EntitlementState.Pro, quote = null)
            val gateway = StubGateway(
                EntitlementQueryResult.Failure(CommercialFailure.DEVICE_MISMATCH)
            )
            val coordinator = CommercialEntitlementCoordinator(
                gateway = gateway,
                accessGate = CommercialAccessGate {
                    CommercialAccessDecision.Allowed(
                        tier = CommercialTier.PRO,
                        expiresAtEpochMs = null
                    )
                },
                nowEpochMs = { 1_000L }
            )

            val denied = coordinator.recheckEntitlementWithDecision()
            assertEquals(
                CommercialAccessDecision.Denied(CommercialAccessDenial.DEVICE_MISMATCH),
                denied.access
            )
            assertEquals(denied.access, coordinator.evaluate())

            gateway.queryResult = EntitlementQueryResult.Ready(pro)
            gateway.queryRemoteConfirmed = false
            val query = coordinator.queryEntitlement()
            assertEquals(
                EntitlementQueryResult.Ready(
                    EntitlementSnapshot(
                        EntitlementState.Error(CommercialFailure.DEVICE_MISMATCH),
                        quote = null,
                        pendingPayment = null
                    )
                ),
                query
            )
            assertEquals(denied.access, coordinator.evaluate())
        }

    @Test
    fun `successful signed check clears the previous authoritative denial`() = runBlocking {
        val gateway = StubGateway(
            EntitlementQueryResult.Failure(CommercialFailure.DEVICE_MISMATCH)
        )
        val coordinator = CommercialEntitlementCoordinator(
            gateway = gateway,
            accessGate = CommercialAccessGate {
                CommercialAccessDecision.Allowed(
                    tier = CommercialTier.PRO,
                    expiresAtEpochMs = null
                )
            },
            nowEpochMs = { 1_000L }
        )

        coordinator.recheckEntitlementWithDecision()
        gateway.queryResult = EntitlementQueryResult.Ready(
            EntitlementSnapshot(EntitlementState.Pro, quote = null)
        )

        val restored = coordinator.recheckEntitlementWithDecision()

        assertEquals(
            CommercialAccessDecision.Allowed(CommercialTier.PRO, expiresAtEpochMs = null),
            restored.access
        )
        assertEquals(restored.access, coordinator.evaluate())
    }

    @Test
    fun `transient lifecycle fallback cannot clear a previous authoritative denial`() =
        runBlocking {
            val gateway = StubGateway(
                EntitlementQueryResult.Failure(CommercialFailure.DEVICE_MISMATCH)
            )
            val coordinator = CommercialEntitlementCoordinator(
                gateway = gateway,
                accessGate = CommercialAccessGate {
                    CommercialAccessDecision.Allowed(
                        tier = CommercialTier.PRO,
                        expiresAtEpochMs = null
                    )
                },
                nowEpochMs = { 1_000L }
            )

            coordinator.recheckEntitlementWithDecision()
            gateway.queryResult = EntitlementQueryResult.Ready(
                EntitlementSnapshot(EntitlementState.Pro, quote = null)
            )
            gateway.checkRemoteConfirmed = false

            val fallback = coordinator.recheckEntitlementWithDecision()

            assertEquals(
                CommercialAccessDecision.Denied(CommercialAccessDenial.DEVICE_MISMATCH),
                fallback.access
            )
            assertEquals(fallback.access, coordinator.evaluate())
        }

    @Test
    fun `transient check failure keeps local pro access`() {
        val localPro = CommercialAccessDecision.Allowed(
            tier = CommercialTier.PRO,
            expiresAtEpochMs = null
        )

        assertEquals(
            localPro,
            CommercialAccessReconciliationPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(CommercialFailure.NETWORK),
                localPro
            )
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.INVALID_LICENSE),
            CommercialAccessReconciliationPolicy.reconcile(
                CommercialAccessRefreshResult.Failure(CommercialFailure.INVALID_LICENSE),
                localPro
            )
        )
    }

    @Test
    fun `coordinator propagates cancellation instead of publishing unknown`() = runBlocking {
        val coordinator = CommercialEntitlementCoordinator(
            gateway = object : StubGateway(
                EntitlementQueryResult.Failure(CommercialFailure.UNKNOWN)
            ) {
                override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult {
                    throw CancellationException("cancelled")
                }
            },
            accessGate = CommercialAccessGate {
                CommercialAccessDecision.Denied(CommercialAccessDenial.NO_LICENSE)
            }
        )

        try {
            coordinator.queryEntitlement()
            fail("query cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: a newer query or a closed controller owns cancellation.
        }
    }

    private open class StubGateway(
        var queryResult: EntitlementQueryResult,
        var queryRemoteConfirmed: Boolean = true,
        var checkRemoteConfirmed: Boolean = true
    ) : DeviceCommercialGateway {
        override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult = queryResult

        override suspend fun checkEntitlementRead(
            nowEpochMs: Long
        ): CommercialEntitlementReadResult<CommercialAccessRefreshResult> =
            CommercialEntitlementReadResult(
                value = checkEntitlement(nowEpochMs),
                remoteConfirmed = checkRemoteConfirmed
            )

        override suspend fun queryEntitlementRead(
            nowEpochMs: Long,
            forceRemote: Boolean
        ): CommercialEntitlementReadResult<EntitlementQueryResult> =
            CommercialEntitlementReadResult(queryEntitlement(nowEpochMs), queryRemoteConfirmed)

        override suspend fun requestQuote(
            discountCode: String,
            nowEpochMs: Long
        ): QuoteRequestResult = error("unused")

        override suspend fun createPayment(
            quote: ProductQuote,
            method: PaymentMethod,
            nowEpochMs: Long
        ): PaymentCreationResult = error("unused")

        override suspend fun refreshPayment(
            session: PaymentSession,
            nowEpochMs: Long
        ): PaymentStatusResult = error("unused")

        override suspend fun restorePurchase(nowEpochMs: Long): PurchaseRecoveryResult =
            error("unused")
    }
}
