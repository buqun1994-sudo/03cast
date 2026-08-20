package com.ninepointnine.desktopcast.service

import android.content.Context
import android.os.Handler
import android.util.Log
import com.ninepointnine.desktopcast.commercial.CommercialAccessDecision
import com.ninepointnine.desktopcast.commercial.CommercialAccessRefreshResult
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
    private var refreshJob: Job? = null
    private var hadAuthorizedAccess = false
    private val guard = CommercialRuntimeAccessGuard(
        nowEpochMs = System::currentTimeMillis,
        evaluateAccess = { now -> coordinator.evaluate(now) },
        scheduleExpiry = { runnable, delayMillis -> mainHandler.postDelayed(runnable, delayMillis) },
        cancelExpiry = mainHandler::removeCallbacks,
        onDenied = { denied ->
            hadAuthorizedAccess = false
            onAccessDenied(denied)
        },
        onRefreshDue = { refresh(forceRemote = true) },
    )

    fun start() {
        val decision = coordinator.evaluate(System.currentTimeMillis())
        if (decision is CommercialAccessDecision.Allowed) {
            authorize(decision)
        } else {
            guard.clear()
            hadAuthorizedAccess = false
        }
        refresh(forceRemote = false)
    }

    fun refresh(forceRemote: Boolean = true) {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            val result = try {
                coordinator.refreshAccess(System.currentTimeMillis(), forceRemote)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Commercial access refresh failed", error)
                CommercialAccessRefreshResult.Failure(CommercialFailure.UNKNOWN)
            }
            withContext(Dispatchers.Main.immediate) {
                reconcile(result)
            }
        }
    }

    fun clear() {
        refreshJob?.cancel()
        refreshJob = null
        guard.clear()
        hadAuthorizedAccess = false
    }

    /** Re-checks the signed local boundary after a system clock or screen event. */
    fun revalidate() {
        guard.revalidate()
    }

    override fun hasCurrentAccess(): Boolean = guard.hasCurrentAccess()

    override fun onMediaAttemptDenied() {
        // Publish the latest locally verified state so the Activity can show
        // the same expired / error result without touching the receiver.
        coordinator.evaluate(System.currentTimeMillis())
    }

    private fun reconcile(result: CommercialAccessRefreshResult) {
        val decision = coordinator.evaluate(System.currentTimeMillis())
        when (decision) {
            is CommercialAccessDecision.Allowed -> authorize(decision)
            is CommercialAccessDecision.Denied -> {
                val wasAuthorized = hadAuthorizedAccess
                guard.clear()
                hadAuthorizedAccess = false
                if (wasAuthorized) onAccessDenied(decision)
                if (result is CommercialAccessRefreshResult.Failure) {
                    Log.i(TAG, "Commercial access remains denied after refresh: ${result.reason}")
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
