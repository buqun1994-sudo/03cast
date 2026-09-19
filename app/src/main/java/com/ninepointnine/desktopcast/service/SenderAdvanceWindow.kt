package com.ninepointnine.desktopcast.service

/**
 * Correlates a receiver-originated terminal projection with the sender's
 * Stop-then-new-media handoff. The window never delays a new media item; it
 * only keeps an otherwise terminal Stop from destroying the active player.
 */
internal class SenderAdvanceWindow(
    private val scheduler: QueueScheduler,
    private val stopHandoffTimeoutMs: Long = STOP_HANDOFF_TIMEOUT_MS,
    private val onExpired: (stopObserved: Boolean) -> Unit,
) {
    var isActive: Boolean = false
        private set
    var stopObserved: Boolean = false
        private set

    private var timeout: QueueCancellation? = null

    fun begin() {
        cancel()
        isActive = true
    }

    /** Returns true when this Stop belongs to the pending sender handoff. */
    fun deferStop(): Boolean {
        if (!isActive) return false
        if (stopObserved) return true
        stopObserved = true
        timeout = scheduler.schedule(stopHandoffTimeoutMs) {
            if (!isActive || !stopObserved) return@schedule
            isActive = false
            stopObserved = false
            timeout = null
            onExpired(true)
        }
        return true
    }

    /** Completes the handoff immediately when a new media item arrives. */
    fun complete(): Boolean {
        if (!isActive) return false
        cancel()
        return true
    }

    fun cancel() {
        timeout?.cancel()
        timeout = null
        isActive = false
        stopObserved = false
    }

    companion object {
        const val STOP_HANDOFF_TIMEOUT_MS = 1_500L
    }
}
