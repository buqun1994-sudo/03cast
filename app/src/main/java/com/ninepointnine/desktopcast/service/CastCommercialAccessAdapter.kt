package com.ninepointnine.desktopcast.service

import android.content.Context
import android.os.Handler
import android.util.Log
import com.ninepointnine.desktopcast.commercial.CommercialAccessDecision
import com.ninepointnine.desktopcast.commercial.CommercialAccessRefreshResult
import com.ninepointnine.desktopcast.commercial.CommercialEntitlementCheckResult
import com.ninepointnine.desktopcast.commercial.CommercialFailure
import com.ninepointnine.desktopcast.commercial.CommercialRuntimeAccessGuard
import com.ninepointnine.desktopcast.commercial.CommercialRuntimeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Narrow synchronous contract consumed by the media router. */
internal interface CastCommercialAccessPort {
    fun hasCurrentAccess(): Boolean
    fun onMediaAttemptDenied()
}

/** Bridges signed entitlement state into the receiver without owning outputs. */
internal class CastCommercialAccessAdapter(
    context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val onAccessDenied: (CommercialAccessDecision.Denied) -> Unit,
) : CastCommercialAccessPort {
    private val coordinator = CommercialRuntimeFactory.entitlementCoordinator(context.applicationContext)
    private var checkJob: Job? = null
    private var checkGeneration = 0L
    private var hadAuthorizedAccess = false
    private var trialLeaseCheckPending = false
    private val guard = CommercialRuntimeAccessGuard(
        nowEpochMs = System::currentTimeMillis,
        evaluateAccess = { now -> coordinator.evaluate(now) },
        scheduleExpiry = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancelExpiry = mainHandler::removeCallbacks,
        onDenied = { denied ->
            hadAuthorizedAccess = false
            onAccessDenied(denied)
        },
        onTrialLeaseDue = ::recheckCommercialEntitlementAtTrialLease,
    )

    fun start() {
        val decision = coordinator.evaluate(System.currentTimeMillis())
        if (decision is CommercialAccessDecision.Allowed) {
            authorize(decision)
        } else {
            guard.clear()
            hadAuthorizedAccess = false
        }
        recheck()
    }

    /** Starts the read-only cloud entitlement check for a service lifecycle. */
    fun recheck() {
        // A lifecycle recheck supersedes a trial-lease callback that may still
        // be in flight.  Leaving this set would suppress every later lease
        // boundary after the superseding job is cancelled.
        trialLeaseCheckPending = false
        launchEntitlementCheck(::reconcile)
    }

    /** Compatibility wrapper for callers that still use the old refresh name. */
    fun refresh(@Suppress("UNUSED_PARAMETER") forceRemote: Boolean = true) {
        recheck()
    }

    private fun launchEntitlementCheck(onComplete: (CommercialEntitlementCheckResult) -> Unit) {
        val generation = ++checkGeneration
        checkJob?.cancel()
        checkJob = scope.launch(Dispatchers.IO) {
            val result = try {
                coordinator.recheckEntitlementWithDecision(System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Commercial entitlement recheck failed", error)
                CommercialEntitlementCheckResult(
                    result = CommercialAccessRefreshResult.Failure(CommercialFailure.UNKNOWN),
                    access = coordinator.evaluate(System.currentTimeMillis())
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != checkGeneration) return@withContext
                onComplete(result)
            }
        }
    }

    fun clear() {
        checkGeneration += 1
        checkJob?.cancel()
        checkJob = null
        trialLeaseCheckPending = false
        guard.clear()
        hadAuthorizedAccess = false
    }

    /** Re-checks the signed local boundary after a system clock or screen event. */
    fun revalidate() {
        guard.revalidate()
    }

    override fun hasCurrentAccess(): Boolean = guard.hasCurrentAccess()

    override fun onMediaAttemptDenied() {
        // The coordinator already published the state that caused the gate to
        // deny this attempt.  Re-evaluating here could resurrect a locally
        // stored PRO license after an authoritative remote denial.
    }

    private fun recheckCommercialEntitlementAtTrialLease() {
        if (trialLeaseCheckPending) return
        trialLeaseCheckPending = true
        Log.i(TAG, "Commercial trial lease boundary reached; checking entitlement")
        launchEntitlementCheck(::finishTrialLeaseCheck)
    }

    private fun finishTrialLeaseCheck(result: CommercialEntitlementCheckResult) {
        if (!trialLeaseCheckPending) return
        trialLeaseCheckPending = false
        reconcile(result)
    }

    private fun reconcile(check: CommercialEntitlementCheckResult) {
        val decision = check.access
        when (decision) {
            is CommercialAccessDecision.Allowed -> authorize(decision)
            is CommercialAccessDecision.Denied -> {
                val wasAuthorized = hadAuthorizedAccess
                guard.clear()
                hadAuthorizedAccess = false
                if (wasAuthorized) onAccessDenied(decision)
                if (check.result is CommercialAccessRefreshResult.Failure) {
                    Log.i(
                        TAG,
                        "Commercial access remains denied after entitlement check: " +
                            check.result.reason
                    )
                }
            }
        }
    }

    private fun authorize(decision: CommercialAccessDecision.Allowed) {
        hadAuthorizedAccess = true
        guard.authorize(decision)
    }

    private companion object {
        const val TAG = "CastCommercialAccess"
    }
}
