package com.ninepointnine.desktopcast.window

import java.util.concurrent.atomic.AtomicLong

/**
 * Main-thread state for one cross-task window handoff. A token makes a late
 * Activity intent harmless once a newer handoff has started or timed out.
 */
class CastWindowHandoff {
    private var activeToken: Long? = null

    val isActive: Boolean get() = activeToken != null

    fun begin(): Long {
        val token = tokenSequence.incrementAndGet()
        activeToken = token
        return token
    }

    fun complete(token: Long): Boolean = clear(token)

    fun cancel(token: Long): Boolean = clear(token)

    fun timeout(token: Long): Boolean = clear(token)

    fun clear() {
        activeToken = null
    }

    private fun clear(token: Long): Boolean {
        if (activeToken != token) return false
        activeToken = null
        return true
    }

    private companion object {
        val tokenSequence = AtomicLong()
    }
}
