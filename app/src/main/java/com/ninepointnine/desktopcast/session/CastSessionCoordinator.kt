package com.ninepointnine.desktopcast.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CastSessionCoordinator {
    private val lock = Any()
    private val mutableState = MutableStateFlow(CastSessionState())
    private var generation = 0L

    val state: StateFlow<CastSessionState> = mutableState.asStateFlow()

    fun beginStart() = mutate {
        CastSessionState(phase = CastPhase.STARTING)
    }

    fun ready() = mutate {
        if (it.phase == CastPhase.STOPPED) it else CastSessionState(phase = CastPhase.WAITING)
    }

    /**
     * Starts a new sender/media session. A new generation is always created,
     * including when the protocol is unchanged, and all prior content is reset.
     */
    fun beginSession(protocol: CastProtocol): CastSessionLease? = synchronized(lock) {
        val current = mutableState.value
        if (current.phase == CastPhase.STOPPED) return@synchronized null
        val previous = current.protocol.takeUnless { it == protocol }
        generation += 1
        mutableState.value = CastSessionState(
            phase = CastPhase.CONNECTING,
            protocol = protocol,
        )
        CastSessionLease(protocol, generation, previous)
    }

    /** Returns the current generation without creating a new session. */
    fun leaseFor(protocol: CastProtocol): CastSessionLease? = synchronized(lock) {
        val current = mutableState.value
        if (current.protocol != protocol || current.phase in NON_LEASABLE_PHASES) {
            null
        } else {
            CastSessionLease(protocol, generation, null)
        }
    }

    fun isCurrent(lease: CastSessionLease): Boolean = synchronized(lock) {
        generation == lease.generation &&
            mutableState.value.phase != CastPhase.STOPPED &&
            mutableState.value.protocol == lease.protocol
    }

    fun showContent(
        protocol: CastProtocol,
        content: CastContentKind,
        title: String = "",
        detail: String = "",
        playing: Boolean = true,
        canSeek: Boolean = false,
    ) = mutateFor(protocol) { current ->
        current.copy(
            phase = when (content) {
                CastContentKind.MIRROR -> CastPhase.MIRRORING
                CastContentKind.AUDIO -> CastPhase.AUDIO
                else -> CastPhase.PLAYING
            },
            content = content,
            title = title,
            detail = detail,
            playing = playing,
            canSeek = canSeek,
        )
    }

    fun updatePlayback(
        protocol: CastProtocol,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) = mutateFor(protocol) { current ->
        current.copy(
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            playing = playing,
            canSeek = durationMs > 0,
        )
    }

    fun updateMetadata(protocol: CastProtocol, title: String, detail: String = "") =
        mutateFor(protocol) { it.copy(title = title, detail = detail) }

    fun recoverableError(protocol: CastProtocol?, message: String) = mutate { current ->
        if (protocol != null && current.protocol != protocol) {
            current
        } else {
            generation += 1
            CastSessionState(
                phase = CastPhase.RECOVERABLE_ERROR,
                protocol = protocol,
                detail = message,
            )
        }
    }

    fun disconnected(protocol: CastProtocol) = mutate { current ->
        if (current.protocol == protocol && current.phase != CastPhase.STOPPED) {
            generation += 1
            CastSessionState(phase = CastPhase.WAITING)
        } else {
            current
        }
    }

    /** Ends media because commercial access changed, while keeping the receiver alive. */
    fun commercialAccessEnded() = mutate { current ->
        if (current.phase in setOf(
                CastPhase.CONNECTING,
                CastPhase.PLAYING,
                CastPhase.MIRRORING,
                CastPhase.AUDIO,
            )
        ) {
            generation += 1
            CastSessionState(phase = CastPhase.WAITING)
        } else {
            current
        }
    }

    fun stop() = mutate {
        generation += 1
        CastSessionState(phase = CastPhase.STOPPED)
    }

    private inline fun mutate(transform: (CastSessionState) -> CastSessionState) {
        synchronized(lock) {
            mutableState.value = transform(mutableState.value)
        }
    }

    private inline fun mutateFor(
        protocol: CastProtocol,
        transform: (CastSessionState) -> CastSessionState,
    ) = mutate { current ->
        if (current.protocol == protocol && current.phase != CastPhase.STOPPED) {
            transform(current)
        } else {
            current
        }
    }

    private companion object {
        val NON_LEASABLE_PHASES = setOf(
            CastPhase.STARTING,
            CastPhase.WAITING,
            CastPhase.RECOVERABLE_ERROR,
            CastPhase.STOPPED,
        )
    }
}
