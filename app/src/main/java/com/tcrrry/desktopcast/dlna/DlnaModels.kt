package com.tcrrry.desktopcast.dlna

enum class DlnaService(
    val pathName: String,
    val serviceType: String,
) {
    AV_TRANSPORT("AVTransport", "urn:schemas-upnp-org:service:AVTransport:1"),
    RENDERING_CONTROL("RenderingControl", "urn:schemas-upnp-org:service:RenderingControl:1"),
    CONNECTION_MANAGER("ConnectionManager", "urn:schemas-upnp-org:service:ConnectionManager:1"),
    ;

    companion object {
        fun fromPath(value: String): DlnaService? = entries.firstOrNull {
            value.equals(it.pathName, ignoreCase = true)
        }
    }
}

enum class DlnaTransportState(val wireValue: String) {
    NO_MEDIA("NO_MEDIA_PRESENT"),
    STOPPED("STOPPED"),
    TRANSITIONING("TRANSITIONING"),
    PLAYING("PLAYING"),
    PAUSED("PAUSED_PLAYBACK"),
}

enum class DlnaMediaKind {
    VIDEO,
    AUDIO,
    IMAGE,
    UNKNOWN,
}

data class DlnaMedia(
    val uri: String,
    val metadata: String = "",
    val title: String = "",
    val creator: String = "",
    val mimeType: String = "",
    val kind: DlnaMediaKind = DlnaMediaKind.UNKNOWN,
)

data class DlnaPlaybackSnapshot(
    val media: DlnaMedia? = null,
    val transportState: DlnaTransportState = DlnaTransportState.NO_MEDIA,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 50,
    val muted: Boolean = false,
)

interface DlnaPlaybackController {
    fun setMedia(media: DlnaMedia)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setVolume(percent: Int)
    fun setMuted(muted: Boolean)
    fun snapshot(): DlnaPlaybackSnapshot
}

class DlnaControlException(
    val upnpCode: Int,
    message: String,
) : Exception(message)
