package com.ninepointnine.desktopcast.session

enum class CastPhase {
    STARTING,
    WAITING,
    CONNECTING,
    PLAYING,
    MIRRORING,
    AUDIO,
    RECOVERABLE_ERROR,
    STOPPED,
}

enum class CastProtocol {
    AIRPLAY,
    DLNA,
}

enum class CastContentKind {
    NONE,
    NETWORK_VIDEO,
    MIRROR,
    AUDIO,
    IMAGE,
}

enum class CastSessionEndReason {
    USER_REQUEST,
    REMOTE_DISCONNECTED,
}

data class CastSessionEndEvent(
    val sequence: Long,
    val protocol: CastProtocol,
    val reason: CastSessionEndReason,
)

data class CastSessionState(
    val phase: CastPhase = CastPhase.STOPPED,
    val protocol: CastProtocol? = null,
    val content: CastContentKind = CastContentKind.NONE,
    val title: String = "",
    val detail: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val canSeek: Boolean = false,
)

/**
 * Identifies one protocol claim. Native and socket callbacks can outlive the
 * connection that created them, so consumers must verify the generation before
 * mutating media output.
 */
data class CastSessionLease(
    val protocol: CastProtocol,
    val generation: Long,
    val previous: CastProtocol?,
)
