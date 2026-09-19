package com.ninepointnine.desktopcast.service

import android.content.Context
import android.graphics.Bitmap
import android.net.Network
import android.util.Log
import com.ninepointnine.desktopcast.R
import com.ninepointnine.desktopcast.dlna.DlnaMedia
import com.ninepointnine.desktopcast.dlna.DlnaMediaKind
import com.ninepointnine.desktopcast.dlna.DlnaPlaybackController
import com.ninepointnine.desktopcast.dlna.DlnaPlaybackSnapshot
import com.ninepointnine.desktopcast.dlna.DlnaTransportState
import com.ninepointnine.desktopcast.media.DlnaImageLoader
import com.ninepointnine.desktopcast.renderer.PlaybackSnapshot
import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastProtocol
import com.ninepointnine.desktopcast.session.CastSessionLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** DLNA-only transport state and SOAP command adapter. */
internal class DlnaPlaybackAdapter(
    context: Context,
    scope: CoroutineScope,
    private val host: CastPlaybackRouter,
    physicalNetworkProvider: () -> Network? = { null },
) : DlnaPlaybackController, NetworkPlaybackObserver {

    private val appContext = context.applicationContext
    private val imageLoader = DlnaImageLoader(
        scope = scope,
        physicalNetworkProvider = physicalNetworkProvider,
        context = appContext,
    )
    @Volatile private var state = DlnaPlaybackSnapshot()
    private var lease: CastSessionLease? = null

    private val mutableImage = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = mutableImage.asStateFlow()

    override fun setMedia(media: DlnaMedia) = host.runOnMainBlocking {
        val newLease = host.ensureSession(CastProtocol.DLNA) ?: return@runOnMainBlocking
        state = state.copy(
            media = media,
            nextMedia = null,
            transportState = DlnaTransportState.STOPPED,
            positionMs = 0,
            durationMs = 0,
        )
        mutableImage.value = null
        lease = newLease
        host.updateMetadata(newLease, media.title, media.creator)
        if (media.kind == DlnaMediaKind.IMAGE) host.stopNetworkPlayback(newLease)
        else playMedia(media, newLease, playing = false)
    }

    override fun setNextMedia(media: DlnaMedia?) = host.runOnMainBlocking {
        // Images use a different output path and cannot enter a network-media playlist.
        if (media?.kind == DlnaMediaKind.IMAGE) throw com.ninepointnine.desktopcast.dlna.DlnaControlException(714, "Illegal MIME-type")
        state = state.copy(nextMedia = media)
        val activeLease = activeLease() ?: return@runOnMainBlocking
        host.setNextNetworkPlayback(activeLease, media?.toQueueItem())
    }

    override fun play() = host.runOnMainBlocking {
        val media = state.media ?: return@runOnMainBlocking
        val activeLease = host.ensureSession(CastProtocol.DLNA) ?: return@runOnMainBlocking
        lease = activeLease
        if (host.isNetworkPlaybackActive(activeLease)) {
            state = state.copy(transportState = DlnaTransportState.PLAYING)
            host.setNetworkPlaying(activeLease, true)
            host.updatePlayback(activeLease, state.positionMs, state.durationMs, true)
        } else {
            // A paused player may have been released by a protocol transition;
            // retain the last known position when rebuilding it.
            val startPositionSeconds = if (state.transportState == DlnaTransportState.PAUSED) {
                state.positionMs / 1000f
            } else {
                0f
            }
            playMedia(media, activeLease, startPositionSeconds)
        }
    }

    override fun pause() = host.runOnMainBlocking {
        val activeLease = activeLease() ?: return@runOnMainBlocking
        state = state.copy(transportState = DlnaTransportState.PAUSED)
        host.setNetworkPlaying(activeLease, false)
        host.updatePlayback(activeLease, state.positionMs, state.durationMs, false)
    }

    override fun stop() = host.runOnMainBlocking {
        val activeLease = activeLease() ?: return@runOnMainBlocking
        releaseOutput(clearMedia = false)
        host.disconnectRemoteImmediately(activeLease)
    }

    override fun next(): Boolean {
        var selected = false
        host.runOnMainBlocking {
            val activeLease = activeLease() ?: return@runOnMainBlocking
            selected = host.selectNextNetworkPlayback(activeLease)
        }
        return selected
    }

    override fun seekTo(positionMs: Long) = host.runOnMainBlocking {
        seekInternal(positionMs)
    }

    override fun setVolume(percent: Int) = host.runOnMainBlocking {
        state = state.copy(volume = percent.coerceIn(0, 100))
        applyVolume(activeLease())
    }

    override fun setMuted(muted: Boolean) = host.runOnMainBlocking {
        state = state.copy(muted = muted)
        applyVolume(activeLease())
    }

    override fun snapshot(): DlnaPlaybackSnapshot = state

    fun toggle() {
        if (state.transportState == DlnaTransportState.PLAYING) pause() else play()
        host.notifyDlnaTransportChanged()
    }

    fun seekFromUi(positionMs: Long) {
        seekTo(positionMs)
        host.notifyDlnaTransportChanged()
    }

    fun disconnectFromUi() = host.runOnMain {
        val activeLease = activeLease() ?: return@runOnMain
        releaseOutput(clearMedia = true)
        host.disconnectImmediately(activeLease)
        host.notifyDlnaTransportChanged()
    }

    fun releaseOutput(clearMedia: Boolean) {
        lease?.let(host::stopNetworkPlayback)
        imageLoader.cancel()
        mutableImage.value = null
        state = if (clearMedia) {
            DlnaPlaybackSnapshot(volume = state.volume, muted = state.muted)
        } else {
            state.copy(
                nextMedia = null,
                transportState = if (state.media == null) {
                    DlnaTransportState.NO_MEDIA
                } else {
                    DlnaTransportState.STOPPED
                },
                positionMs = 0,
            )
        }
        lease = null
    }

    override fun onPlaybackInfo(snapshot: PlaybackSnapshot) {
        val activeLease = activeLease() ?: return
        val transport = when {
            snapshot.ended -> DlnaTransportState.STOPPED
            !snapshot.playWhenReady && state.transportState == DlnaTransportState.STOPPED -> DlnaTransportState.STOPPED
            snapshot.buffering -> DlnaTransportState.TRANSITIONING
            snapshot.playWhenReady -> DlnaTransportState.PLAYING
            else -> DlnaTransportState.PAUSED
        }
        val previousTransport = state.transportState
        state = state.copy(
            transportState = transport,
            positionMs = (snapshot.position * 1000).toLong(),
            durationMs = if (snapshot.duration > 0) (snapshot.duration * 1000).toLong() else 0,
        )
        host.updatePlayback(activeLease, state.positionMs, state.durationMs, transport == DlnaTransportState.PLAYING)
        if (previousTransport != transport) {
            host.notifyDlnaTransportChanged()
        }
    }

    override fun onVideoSize(width: Int, height: Int, aspect: Float) = Unit

    override fun onQueueChanged(state: PlaybackQueueState) {
        val activeLease = activeLease() ?: return
        val current = state.current ?: return
        val old = this.state
        this.state = old.copy(
            media = current.toDlnaMedia(), nextMedia = state.next?.toDlnaMedia(),
            transportState = when {
                state.awaitingNext -> DlnaTransportState.STOPPED
                current.status == PlaybackQueueItemStatus.PREPARING && state.playWhenReady -> DlnaTransportState.TRANSITIONING
                current.status == PlaybackQueueItemStatus.PLAYING -> DlnaTransportState.PLAYING
                current.status == PlaybackQueueItemStatus.PAUSED && old.transportState != DlnaTransportState.STOPPED -> DlnaTransportState.PAUSED
                else -> old.transportState
            },
        )
        if (old.media != this.state.media) {
            host.showContent(activeLease, current.content, current.title, current.detail, state.playWhenReady)
            this.state = this.state.copy(positionMs = 0, durationMs = 0)
        }
        if (old != this.state) {
            host.notifyDlnaTransportChanged()
        }
    }

    override fun onTitle(title: String?) {
        val activeLease = activeLease() ?: return
        if (!title.isNullOrBlank()) host.updateMetadata(activeLease, title, state.media?.creator.orEmpty())
    }

    override fun onHasVideo(hasVideo: Boolean) {
        val activeLease = activeLease() ?: return
        val media = state.media ?: return
        val content = if (hasVideo) CastContentKind.NETWORK_VIDEO else CastContentKind.AUDIO
        host.showContent(
            activeLease,
            content,
            media.title,
            media.creator,
            playing = state.transportState == DlnaTransportState.PLAYING,
            canSeek = state.durationMs > 0,
        )
    }

    override fun onEnded() {
        val activeLease = activeLease() ?: return
        host.stopNetworkPlayback(activeLease)
        state = state.copy(transportState = DlnaTransportState.STOPPED)
        lease = null
        host.disconnectRemoteImmediately(activeLease)
        host.notifyDlnaTransportChanged()
    }

    override fun onError(message: String) {
        val activeLease = activeLease() ?: return
        Log.w(TAG, "DLNA media playback failed", IllegalStateException(message))
        releaseOutput(clearMedia = false)
        host.reportFailure(activeLease, appContext.getString(R.string.media_playback_failed))
        host.notifyDlnaTransportChanged()
    }

    private fun playMedia(
        media: DlnaMedia,
        activeLease: CastSessionLease,
        startPositionSeconds: Float = 0f,
        playing: Boolean = true,
    ) {
        host.showContent(
            activeLease,
            when (media.kind) {
                DlnaMediaKind.AUDIO -> CastContentKind.AUDIO
                DlnaMediaKind.IMAGE -> CastContentKind.IMAGE
                else -> CastContentKind.NETWORK_VIDEO
            },
            media.title,
            media.creator,
            playing = playing,
        )
        if (media.kind == DlnaMediaKind.IMAGE) {
            host.stopNetworkPlayback(activeLease)
            mutableImage.value = null
            imageLoader.load(
                media.uri,
                onLoaded = { bitmap ->
                    host.runOnMain {
                        if (!host.isCurrent(activeLease)) return@runOnMain
                        mutableImage.value = bitmap
                        state = state.copy(transportState = DlnaTransportState.PLAYING)
                        host.showContent(
                            activeLease,
                            CastContentKind.IMAGE,
                            media.title,
                            media.creator,
                        )
                        host.notifyDlnaTransportChanged()
                    }
                },
                onError = { error ->
                    host.runOnMain {
                        if (host.isCurrent(activeLease)) onError(error.message ?: "Image playback failed")
                    }
                },
            )
        } else {
            mutableImage.value = null
            val pendingNext = state.nextMedia
            host.startNetworkPlayback(
                lease = activeLease,
                location = media.uri,
                startPositionSeconds = startPositionSeconds,
                observer = this,
                itemId = media.queueId(),
                title = media.title,
                detail = media.creator,
                metadata = media.metadata,
                content = if (media.kind == DlnaMediaKind.AUDIO) CastContentKind.AUDIO else CastContentKind.NETWORK_VIDEO,
                playing = playing,
                declaredMimeType = media.mimeType,
                allowHlsFallback = media.kind in setOf(
                    DlnaMediaKind.VIDEO,
                    DlnaMediaKind.UNKNOWN,
                ),
            )
            pendingNext?.let { host.setNextNetworkPlayback(activeLease, it.toQueueItem()) }
            applyVolume(activeLease)
        }
    }

    private fun DlnaMedia.queueId(): String = "dlna:${java.util.UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8))}"

    private fun DlnaMedia.toQueueItem() = PlaybackQueueItem(
        id = queueId(), protocol = CastProtocol.DLNA, uri = uri, mimeType = mimeType,
        title = title, detail = creator, metadata = metadata,
        content = if (kind == DlnaMediaKind.AUDIO) CastContentKind.AUDIO else CastContentKind.NETWORK_VIDEO,
        allowHlsFallback = kind in setOf(DlnaMediaKind.VIDEO, DlnaMediaKind.UNKNOWN),
    )

    private fun PlaybackQueueItem.toDlnaMedia() = DlnaMedia(
        uri = uri, metadata = metadata, title = title, creator = detail, mimeType = mimeType.orEmpty(),
        kind = if (content == CastContentKind.AUDIO) DlnaMediaKind.AUDIO else DlnaMediaKind.VIDEO,
    )

    private fun seekInternal(positionMs: Long) {
        val activeLease = activeLease() ?: return
        state = state.copy(positionMs = positionMs.coerceAtLeast(0))
        host.scrubNetworkPlayback(activeLease, state.positionMs / 1000f)
        host.updatePlayback(
            activeLease,
            state.positionMs,
            state.durationMs,
            state.transportState == DlnaTransportState.PLAYING,
        )
    }

    private fun applyVolume(activeLease: CastSessionLease?) {
        activeLease ?: return
        host.setNetworkVolume(activeLease, if (state.muted) 0f else state.volume / 100f)
    }

    private fun activeLease(): CastSessionLease? = lease?.takeIf(host::isCurrent)

    private companion object {
        const val TAG = "DlnaPlaybackAdapter"
    }
}
