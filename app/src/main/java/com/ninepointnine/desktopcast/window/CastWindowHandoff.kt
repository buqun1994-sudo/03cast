package com.ninepointnine.desktopcast.window

import com.ninepointnine.desktopcast.session.CastContentKind
import java.util.concurrent.atomic.AtomicLong

/**
 * Main-thread state for one cross-task window handoff. A token makes a late
 * Activity intent harmless once a newer handoff has started or timed out.
 */
class CastWindowHandoff {
    private var activeHandoff: ActiveHandoff? = null

    val isActive: Boolean get() = activeHandoff != null

    fun begin(onComplete: () -> Unit = {}): Long {
        val token = tokenSequence.incrementAndGet()
        activeHandoff = ActiveHandoff(token, onComplete)
        return token
    }

    fun complete(token: Long, beforeSourceRetirement: () -> Unit = {}): Boolean {
        val handoff = activeHandoff?.takeIf { it.token == token } ?: return false
        activeHandoff = null
        beforeSourceRetirement()
        handoff.onComplete()
        return true
    }

    fun cancel(token: Long): Boolean = clear(token)

    fun timeout(token: Long): Boolean = clear(token)

    fun clear() {
        activeHandoff = null
    }

    private fun clear(token: Long): Boolean {
        if (activeHandoff?.token != token) return false
        activeHandoff = null
        return true
    }

    private data class ActiveHandoff(
        val token: Long,
        val onComplete: () -> Unit,
    )

    private companion object {
        val tokenSequence = AtomicLong()
    }
}

internal fun isWindowHandoffOutputReady(
    content: CastContentKind,
    mirrorSurfaceReady: Boolean,
    mediaSurfaceReady: Boolean,
): Boolean = when (content) {
    CastContentKind.MIRROR -> mirrorSurfaceReady
    CastContentKind.NETWORK_VIDEO -> mediaSurfaceReady
    CastContentKind.NONE,
    CastContentKind.AUDIO,
    CastContentKind.IMAGE,
    -> true
}
