package com.ninepointnine.desktopcast.commercial

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The process-wide owner of entitlement reads, rechecks and the last trusted
 * projection. Settings and the cast service must use this object instead of
 * evaluating the gateway and access gate independently.
 */
class CommercialEntitlementCoordinator(
    private val gateway: DeviceCommercialGateway,
    private val accessGate: CommercialAccessGate,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val listeners = CopyOnWriteArrayList<(EntitlementSnapshot) -> Unit>()

    @Volatile
    private var latestSnapshot: EntitlementSnapshot? = null

    @Volatile
    private var latestKey: SnapshotKey? = null

    /**
     * A remote denial is stronger than a still-present local credential for
     * the rest of this process.  The gateway may retain the old license while
     * a device-key recovery is pending, so evaluating that license again must
     * not reopen media access or overwrite the shared error projection.
     */
    @Volatile
    private var latestAuthoritativeDenial: CommercialAccessDecision.Denied? = null

    /**
     * Registers a listener for trusted entitlement snapshots. Listener
     * failures are isolated so a UI side effect cannot turn a successful
     * entitlement query into an UNKNOWN result.
     */
    fun addListener(listener: (EntitlementSnapshot) -> Unit): () -> Unit {
        listeners += listener
        latestSnapshot?.let { snapshot -> notifyListener(listener, snapshot) }
        return { listeners -= listener }
    }

    fun currentSnapshot(nowEpochMs: Long = this.nowEpochMs()): EntitlementSnapshot? {
        evaluate(nowEpochMs)
        return latestSnapshot
    }

    fun evaluate(nowEpochMs: Long = this.nowEpochMs()): CommercialAccessDecision {
        val access = effectiveAccess(evaluateLocal(nowEpochMs))
        publishAccess(access, nowEpochMs)
        return access
    }

    suspend fun queryEntitlement(
        nowEpochMs: Long = this.nowEpochMs(),
        forceRemote: Boolean = false
    ): EntitlementQueryResult {
        val read = try {
            if (forceRemote) {
                gateway.queryEntitlementRead(nowEpochMs, forceRemote = true)
            } else {
                gateway.queryEntitlementRead(nowEpochMs, forceRemote = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CommercialEntitlementReadResult(
                value = EntitlementQueryResult.Failure(CommercialFailure.UNKNOWN),
                remoteConfirmed = false
            )
        }
        currentCoroutineContext().ensureActive()
        val result = read.value

        if (result is EntitlementQueryResult.Ready) {
            if (read.remoteConfirmed) {
                updateAuthoritativeDenialFromSnapshot(result.snapshot)
            } else if (latestAuthoritativeDenial != null) {
                // A local fallback cannot supersede a denial that came from a
                // previous authoritative cloud response.
                latestAuthoritativeDenial?.let { denial ->
                    snapshotFromAccess(denial, nowEpochMs)?.let { snapshot ->
                        publish(snapshot)
                        return EntitlementQueryResult.Ready(snapshot)
                    }
                }
            }
            publish(result.snapshot)
            return result
        }

        val failure = result as EntitlementQueryResult.Failure
        if (read.remoteConfirmed) {
            CommercialAccessReconciliationPolicy.denialFor(failure.reason)?.let { denial ->
                rememberAuthoritativeDenial(CommercialAccessDecision.Denied(denial))
                publishAccess(CommercialAccessDecision.Denied(denial), nowEpochMs)
            }
        }
        if (failure.reason.isTransient()) {
            val local = snapshotFromAccess(evaluate(nowEpochMs), nowEpochMs)
            if (local != null) {
                publish(local)
                return EntitlementQueryResult.Ready(local)
            }
        }
        return result
    }

    /**
     * Performs the online, read-only device entitlement check. A successful
     * active response does not replace the locally stored license.
     */
    suspend fun recheckEntitlement(
        nowEpochMs: Long = this.nowEpochMs()
    ): CommercialAccessRefreshResult = recheckEntitlementWithDecision(nowEpochMs).result

    /**
     * Performs one lifecycle check and returns the exact access decision that
     * the runtime must apply. Keeping the decision beside the remote result
     * prevents callers from re-reading a stale local PRO license after an
     * authoritative device or entitlement denial.
     */
    internal suspend fun recheckEntitlementWithDecision(
        nowEpochMs: Long = this.nowEpochMs()
    ): CommercialEntitlementCheckResult {
        val read = try {
            gateway.checkEntitlementRead(nowEpochMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CommercialEntitlementReadResult(
                value = CommercialAccessRefreshResult.Failure(CommercialFailure.UNKNOWN),
                remoteConfirmed = false
            )
        }
        currentCoroutineContext().ensureActive()
        val result = read.value
        // The gateway persists any newly issued purchase/trial/recovery
        // license before returning. Re-read the local verifier so runtime
        // consumers observe one authoritative gate projection.
        val localAccess = evaluateLocal(nowEpochMs)
        val reconciled = CommercialAccessReconciliationPolicy.reconcile(result, localAccess)
        if (read.remoteConfirmed) {
            updateAuthoritativeDenial(result)
        }
        val access = effectiveAccess(reconciled)
        // A remote non-transient denial must be visible to settings and the
        // waiting page even when the old local license is still present.
        publishAccess(access, nowEpochMs)
        return CommercialEntitlementCheckResult(result = result, access = access)
    }

    /**
     * Clears a previously observed remote denial after a purchase or recovery
     * flow has persisted a new signed credential.  The local gate is read and
     * published immediately so settings and the waiting page converge before
     * the next media request.
     */
    internal fun clearAuthoritativeDenial(nowEpochMs: Long = this.nowEpochMs()) {
        latestAuthoritativeDenial = null
        evaluate(nowEpochMs)
    }

    /**
     * Compatibility wrapper for the pre-check API. It intentionally performs
     * the same read-only check and never invokes license/refresh itself.
     */
    suspend fun refreshAccess(
        nowEpochMs: Long = this.nowEpochMs(),
        @Suppress("UNUSED_PARAMETER") forceRemote: Boolean = true
    ): CommercialAccessRefreshResult {
        return recheckEntitlement(nowEpochMs)
    }

    private fun publishAccess(
        access: CommercialAccessDecision,
        nowEpochMs: Long
    ) {
        snapshotFromAccess(access, nowEpochMs)?.let(::publish)
    }

    private fun evaluateLocal(nowEpochMs: Long): CommercialAccessDecision =
        runCatching { accessGate.evaluate(nowEpochMs) }.getOrElse {
            CommercialAccessDecision.Denied(CommercialAccessDenial.STORAGE_FAILURE)
        }

    private fun effectiveAccess(access: CommercialAccessDecision): CommercialAccessDecision =
        latestAuthoritativeDenial ?: access

    private fun rememberAuthoritativeDenial(
        denial: CommercialAccessDecision.Denied
    ) {
        latestAuthoritativeDenial = denial
    }

    private fun updateAuthoritativeDenial(
        result: CommercialAccessRefreshResult
    ) {
        when (result) {
            is CommercialAccessRefreshResult.Failure -> {
                CommercialAccessReconciliationPolicy.denialFor(result.reason)?.let { denial ->
                    rememberAuthoritativeDenial(CommercialAccessDecision.Denied(denial))
                }
            }
            is CommercialAccessRefreshResult.Ready -> {
                updateAuthoritativeDenialFromEntitlement(result.entitlement)
            }
        }
    }

    private fun updateAuthoritativeDenialFromSnapshot(snapshot: EntitlementSnapshot) {
        updateAuthoritativeDenialFromEntitlement(snapshot.entitlement)
    }

    private fun updateAuthoritativeDenialFromEntitlement(entitlement: EntitlementState) {
        when (entitlement) {
            EntitlementState.Pro,
            is EntitlementState.Trial -> latestAuthoritativeDenial = null
            EntitlementState.Expired -> rememberAuthoritativeDenial(
                CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED)
            )
            is EntitlementState.Error -> {
                CommercialAccessReconciliationPolicy.denialFor(entitlement.reason)?.let { denial ->
                    rememberAuthoritativeDenial(CommercialAccessDecision.Denied(denial))
                }
            }
            EntitlementState.Checking -> Unit
        }
    }

    private fun snapshotFromAccess(
        access: CommercialAccessDecision,
        nowEpochMs: Long
    ): EntitlementSnapshot? {
        val previous = latestSnapshot
        val entitlement = when (access) {
            is CommercialAccessDecision.Allowed -> when (access.tier) {
                CommercialTier.PRO -> EntitlementState.Pro
                CommercialTier.TRIAL -> {
                    val trialEndsAt = access.trialEndsAtEpochMs
                        ?: (previous?.entitlement as? EntitlementState.Trial)
                            ?.expiresAtEpochMs
                        ?: return null
                    if (nowEpochMs >= trialEndsAt) {
                        EntitlementState.Expired
                    } else {
                        EntitlementState.Trial(
                            expiresAtEpochMs = trialEndsAt,
                            remainingMillis = trialEndsAt - nowEpochMs
                        )
                    }
                }
            }
            is CommercialAccessDecision.Denied -> when (access.reason) {
                CommercialAccessDenial.LICENSE_EXPIRED -> EntitlementState.Expired
                CommercialAccessDenial.ENTITLEMENT_REVOKED -> {
                    EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED)
                }
                CommercialAccessDenial.CONFIGURATION_MISSING -> EntitlementState.Error(
                    CommercialFailure.CONFIGURATION_MISSING
                )
                CommercialAccessDenial.DEVICE_MISMATCH -> EntitlementState.Error(
                    CommercialFailure.DEVICE_MISMATCH
                )
                CommercialAccessDenial.CLOCK_ROLLBACK -> EntitlementState.Error(
                    CommercialFailure.CLOCK_ROLLBACK
                )
                CommercialAccessDenial.STORAGE_FAILURE -> EntitlementState.Error(
                    CommercialFailure.STORAGE
                )
                CommercialAccessDenial.INVALID_LICENSE -> EntitlementState.Error(
                    CommercialFailure.INVALID_LICENSE
                )
                CommercialAccessDenial.QUERY_FAILURE -> EntitlementState.Error(
                    CommercialFailure.NETWORK
                )
                // A missing local license is expected before the first
                // network trial request; keep the UI in Checking until that
                // request returns an authoritative result.
                CommercialAccessDenial.NO_LICENSE -> return null
            }
        }
        val keepPurchaseState = entitlement is EntitlementState.Trial
        return EntitlementSnapshot(
            entitlement = entitlement,
            quote = previous?.quote?.takeIf { keepPurchaseState },
            pendingPayment = previous?.pendingPayment?.takeIf { keepPurchaseState }
        )
    }

    private fun publish(snapshot: EntitlementSnapshot) {
        latestSnapshot = snapshot
        val key = SnapshotKey.from(snapshot)
        if (latestKey == key) return
        latestKey = key
        listeners.forEach { listener -> notifyListener(listener, snapshot) }
    }

    private fun notifyListener(
        listener: (EntitlementSnapshot) -> Unit,
        snapshot: EntitlementSnapshot
    ) {
        runCatching { listener(snapshot) }
    }

    private fun CommercialFailure.isTransient(): Boolean = this == CommercialFailure.NETWORK ||
        this == CommercialFailure.RATE_LIMITED

    private data class SnapshotKey(
        val entitlement: String,
        val quoteReference: String?,
        val quoteFinalAmountCents: Int?,
        val pendingPurchaseReference: String?
    ) {
        companion object {
            fun from(snapshot: EntitlementSnapshot): SnapshotKey {
                val entitlement = when (val value = snapshot.entitlement) {
                    EntitlementState.Checking -> "checking"
                    is EntitlementState.Trial -> "trial:${value.expiresAtEpochMs}"
                    EntitlementState.Expired -> "expired"
                    EntitlementState.Pro -> "pro"
                    is EntitlementState.Error -> "error:${value.reason.name}"
                }
                return SnapshotKey(
                    entitlement = entitlement,
                    quoteReference = snapshot.quote?.quoteReference,
                    quoteFinalAmountCents = snapshot.quote?.finalAmountCents,
                    pendingPurchaseReference = snapshot.pendingPayment?.purchaseReference
                )
            }
        }
    }

    companion object {
        /** Compatibility owner for isolated controller tests. */
        internal fun forGateway(
            gateway: DeviceCommercialGateway,
            nowEpochMs: () -> Long
        ): CommercialEntitlementCoordinator = CommercialEntitlementCoordinator(
            gateway = gateway,
            accessGate = FailClosedCommercialAccessGate(),
            nowEpochMs = nowEpochMs
        )
    }
}

/**
 * Maps an online check to the decision that may be used by a runtime.
 * Transport failures remain local-state fallbacks; only authoritative
 * denials are allowed to override a still-valid local credential.
 */
internal object CommercialAccessReconciliationPolicy {
    fun reconcile(
        result: CommercialAccessRefreshResult,
        localAccess: CommercialAccessDecision
    ): CommercialAccessDecision = when {
        result is CommercialAccessRefreshResult.Failure -> {
            denialFor(result.reason)?.let(CommercialAccessDecision::Denied) ?: localAccess
        }
        result is CommercialAccessRefreshResult.Ready &&
            result.entitlement is EntitlementState.Expired -> {
            CommercialAccessDecision.Denied(CommercialAccessDenial.LICENSE_EXPIRED)
        }
        else -> localAccess
    }

    fun denialFor(reason: CommercialFailure): CommercialAccessDenial? = when (reason) {
        CommercialFailure.ENTITLEMENT_REVOKED -> CommercialAccessDenial.ENTITLEMENT_REVOKED
        CommercialFailure.DEVICE_MISMATCH -> CommercialAccessDenial.DEVICE_MISMATCH
        CommercialFailure.INVALID_LICENSE -> CommercialAccessDenial.INVALID_LICENSE
        CommercialFailure.CLOCK_ROLLBACK -> CommercialAccessDenial.CLOCK_ROLLBACK
        CommercialFailure.STORAGE -> CommercialAccessDenial.STORAGE_FAILURE
        CommercialFailure.CONFIGURATION_MISSING -> CommercialAccessDenial.CONFIGURATION_MISSING
        else -> null
    }
}

internal data class CommercialEntitlementCheckResult(
    val result: CommercialAccessRefreshResult,
    val access: CommercialAccessDecision
)
