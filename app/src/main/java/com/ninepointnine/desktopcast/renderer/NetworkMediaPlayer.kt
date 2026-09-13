package com.ninepointnine.desktopcast.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Media3 normally uses MediaCodec.setOutputSurface() when a player receives a
 * new Surface. The Android 9 Qualcomm decoder on the target car can keep the
 * old BufferQueue alive after that call and fail asynchronously in qbuf. Make
 * the renderer take the documented workaround path, which releases and
 * recreates the codec instead of hot-swapping its output queue.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Suppress("DEPRECATION")
private class SurfaceHandoffVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedVideoFrameCountToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    selector,
    allowedVideoJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedVideoFrameCountToNotify,
) {
    private val outputStateLock = Any()
    private var outputGeneration = 0L
    private var outputSurface: Surface? = null
    private var outputWaiter: OutputWaiter? = null

    override fun handleMessage(messageType: Int, message: Any?) {
        super.handleMessage(messageType, message)
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) return
        val deliveredSurface = message as? Surface
        val waiter = synchronized(outputStateLock) {
            outputSurface = deliveredSurface
            outputGeneration += 1
            outputWaiter?.takeIf {
                it.afterGeneration < outputGeneration && it.surface === deliveredSurface
            }?.also { outputWaiter = null }
        }
        waiter?.latch?.countDown()
    }

    fun outputGeneration(): Long = synchronized(outputStateLock) { outputGeneration }

    fun isOutputSurface(surface: Surface): Boolean = synchronized(outputStateLock) {
        outputSurface === surface
    }

    /**
     * Waits for the exact Surface message submitted by ExoPlayer to be handled
     * on the playback thread. Surface.isValid alone only proves that the
     * BufferQueue exists; this proves that this renderer owns that queue.
     */
    fun awaitOutputSurface(
        surface: Surface,
        afterGeneration: Long,
        timeoutMs: Long,
    ): Boolean {
        val waiter: OutputWaiter
        synchronized(outputStateLock) {
            if (outputGeneration > afterGeneration && outputSurface === surface) return true
            waiter = OutputWaiter(afterGeneration, surface, CountDownLatch(1))
            outputWaiter = waiter
        }
        try {
            waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } finally {
            synchronized(outputStateLock) {
                if (outputWaiter === waiter) outputWaiter = null
            }
        }
        return synchronized(outputStateLock) {
            outputGeneration > afterGeneration && outputSurface === surface
        }
    }

    private data class OutputWaiter(
        val afterGeneration: Long,
        val surface: Surface,
        val latch: CountDownLatch,
    )

    override fun codecNeedsSetOutputSurfaceWorkaround(codecName: String): Boolean {
        val android9VendorCodec = SurfaceHandoffPolicy.forceCodecRecreate(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            codecName = codecName,
        )
        if (android9VendorCodec) {
            Log.i(TAG, "Forcing codec recreation for Android 9 vendor output: $codecName")
        }
        return android9VendorCodec || super.codecNeedsSetOutputSurfaceWorkaround(codecName)
    }

    private companion object {
        const val TAG = "SurfaceHandoffVideoRenderer"
    }
}

/** Keeps the exact renderer instance so a handoff can wait for its output detach. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SurfaceHandoffRenderersFactory(
    context: Context,
    selector: MediaCodecSelector,
) : DefaultRenderersFactory(context) {

    var videoRenderer: SurfaceHandoffVideoRenderer? = null
        private set

    fun resetVideoRendererReference() {
        videoRenderer = null
    }

    init {
        setMediaCodecSelector(selector)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val renderer = SurfaceHandoffVideoRenderer(
            context = context,
            codecAdapterFactory = getCodecAdapterFactory(),
            selector = mediaCodecSelector,
            allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
            enableDecoderFallback = enableDecoderFallback,
            eventHandler = eventHandler,
            eventListener = eventListener,
            maxDroppedVideoFrameCountToNotify =
                DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        )
        videoRenderer = renderer
        out.add(renderer)
    }
}

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
    val playbackId: String? = null,
    val playable: Boolean = false,
    val ended: Boolean = false,
)

internal data class NetworkItemConfiguration(val allowHlsFallback: Boolean, val startPositionMs: Long)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NetworkMediaPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderersFactory = SurfaceHandoffRenderersFactory(
        context = context,
        selector = HARDWARE_VIDEO_CODEC_SELECTOR,
    )
    private var player: ExoPlayer? = null
    private var activeVideoRenderer: SurfaceHandoffVideoRenderer? = null
    private var pendingSurfaceHolder: SurfaceHolder? = null
    private var pendingSurfaceOutputGeneration: Long? = null
    private var handoffSurfaceHolder: SurfaceHolder? = null
    private var currentLocation: String? = null
    private var currentPlaybackId: String? = null
    private var hlsFallbackAttempted = false
    private val mimeOverrides = mutableMapOf<String, String>()

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onMediaItemTransition: ((mediaId: String?) -> Unit)? = null
    var onSourceReady: ((String) -> Unit)? = null
    var onEnded: ((String?) -> Unit)? = null
    var onError: ((String?, String, Boolean) -> Unit)? = null
    var onHasVideo: ((Boolean) -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if ((player?.currentMediaItem?.localConfiguration?.tag as? NetworkItemConfiguration)?.allowHlsFallback == true &&
                !hlsFallbackAttempted && isUnrecognizedInput(error)
            ) {
                hlsFallbackAttempted = true
                val location = currentLocation
                if (location != null) {
                    Log.w(TAG, "Progressive probe failed; retrying DLNA media as HLS")
                    _retryCurrentWithMime(MimeTypes.APPLICATION_M3U8)
                    return
                }
            }
            Log.w(TAG, "playback error", error)
            val retryable = error.errorCode in setOf(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            ) || generateSequence<Throwable>(error) { it.cause }.any {
                it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                    (it.responseCode in setOf(408, 429) || it.responseCode in 500..599)
            }
            onError?.invoke(player?.currentMediaItem?.mediaId, error.message ?: "Playback failed", retryable)
        }
        override fun onPlaybackStateChanged(state: Int) {
            Log.i(TAG, "Playback state: ${stateName(state)}")
            if (state == Player.STATE_ENDED) onEnded?.invoke(player?.currentMediaItem?.mediaId)
            _reportPlaybackInfo()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem?.mediaId != player?.currentMediaItem?.mediaId) return
            currentLocation = mediaItem?.localConfiguration?.uri?.toString()
            if (currentPlaybackId != mediaItem?.mediaId) hlsFallbackAttempted = false
            currentPlaybackId = mediaItem?.mediaId
            Log.i(TAG, "Media item transition: id=${mediaItem?.mediaId} reason=$reason")
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                onMediaItemTransition?.invoke(mediaItem?.mediaId)
            }
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val window = Timeline.Window()
            for (index in 0 until timeline.windowCount) {
                timeline.getWindow(index, window)
                if (!window.isPlaceholder) onSourceReady?.invoke(window.mediaItem.mediaId)
            }
            _reportPlaybackInfo()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            onTitle?.invoke(mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            Log.i(TAG, "Video size: ${width}x${videoSize.height}")
            onVideoSize?.invoke(width, videoSize.height, width.toFloat() / videoSize.height)
        }
        override fun onTracksChanged(tracks: Tracks) {
            tracks.groups
                .asSequence()
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    (0 until group.length)
                        .asSequence()
                        .filter(group::isTrackSelected)
                        .map(group::getTrackFormat)
                }
                .forEach { format -> Log.i(TAG, "Selected video: ${Format.toLogString(format)}") }
            onHasVideo?.invoke(
                tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_VIDEO &&
                        (0 until group.length).any(group::isTrackSelected)
                },
            )
        }
        override fun onRenderedFirstFrame() {
            Log.i(TAG, "First video frame rendered")
        }
    }

    /** Reconcile the receiver's ordered projection without recreating ExoPlayer or its Surface. */
    internal fun syncPlaylist(mediaItems: List<MediaItem>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val p = ensurePlayer()
        mediaItems.forEachIndexed { index, desired ->
            val projected = mimeOverrides[desired.mediaId]?.let { desired.buildUpon().setMimeType(it).build() } ?: desired
            val existing = (index until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == projected.mediaId }
            if (existing == null) p.addMediaItem(index, projected)
            else {
                if (existing != index) p.moveMediaItem(existing, index)
                val current = p.getMediaItemAt(index)
                val currentConfig = current.localConfiguration
                val desiredConfig = projected.localConfiguration
                val sourceChanged = currentConfig?.uri != desiredConfig?.uri ||
                    currentConfig?.mimeType != desiredConfig?.mimeType ||
                    currentConfig?.tag != desiredConfig?.tag
                if (sourceChanged || current.mediaMetadata != projected.mediaMetadata) {
                    // Preserve the desired queue projection exactly. A source
                    // change is followed by NetworkPlaybackQueue.select when
                    // it is current; metadata-only changes do not restart it.
                    p.replaceMediaItem(index, projected)
                }
            }
        }
        if (p.mediaItemCount > mediaItems.size) p.removeMediaItems(mediaItems.size, p.mediaItemCount)
    }

    internal fun selectMediaItem(mediaId: String, startPositionMs: Long, playing: Boolean) {
        val p = player ?: return
        val index = (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == mediaId } ?: return
        p.seekTo(index, startPositionMs.coerceAtLeast(0))
        p.playWhenReady = playing
        p.prepare()
        _reportPlaybackInfo()
        mainHandler.removeCallbacks(_reportTick)
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    internal fun retryCurrent(playing: Boolean) {
        val p = player ?: return
        // prepare() alone does nothing during an indefinitely buffering load.
        val position = p.currentPosition
        val index = p.currentMediaItemIndex
        if (index !in 0 until p.mediaItemCount) return
        p.stop()
        p.seekTo(index, position)
        p.playWhenReady = playing
        p.prepare()
    }

    private fun _retryCurrentWithMime(mimeType: String) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        if (index !in 0 until p.mediaItemCount) return
        val current = p.getMediaItemAt(index)
        val replacement = current.buildUpon().setMimeType(mimeType).build()
        mimeOverrides[current.mediaId] = mimeType
        p.replaceMediaItem(index, replacement)
        p.seekTo(index, (current.localConfiguration?.tag as? NetworkItemConfiguration)?.startPositionMs ?: 0L)
        p.prepare()
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        renderersFactory.resetVideoRendererReference()
        val created = ExoPlayer.Builder(context, renderersFactory)
            .setUseLazyPreparation(false)
            .setDetachSurfaceTimeoutMs(SURFACE_HANDOFF_TIMEOUT_MS)
            .build()
            .also { it.addListener(_listener) }
        player = created
        activeVideoRenderer = renderersFactory.videoRenderer
        pendingSurfaceHolder?.let { holder ->
            pendingSurfaceOutputGeneration = activeVideoRenderer?.outputGeneration()
            created.setVideoSurfaceHolder(holder)
        }
        return created
    }

    fun scrub(positionSeconds: Float) = runOnMain {
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    // coalesces the seek spam from drag-seeking
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setScrubbing(enabled: Boolean) = runOnMain {
        player?.isScrubbingModeEnabled = enabled
    }

    fun setRate(rate: Float) = runOnMain {
        val p = player ?: return@runOnMain
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = runOnMain {
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    fun setSpeed(speed: Float) = runOnMain {
        player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    fun setSkipSilence(enabled: Boolean) = runOnMain {
        player?.skipSilenceEnabled = enabled
    }

    fun setVolume(volume: Float) = runOnMain {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun seekBy(deltaMs: Long) = runOnMain {
        val p = player ?: return@runOnMain
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(holder: SurfaceHolder) = runOnMain {
        if (!holder.surface.isValid) return@runOnMain
        if (pendingSurfaceHolder === holder) return@runOnMain
        pendingSurfaceHolder = holder
        pendingSurfaceOutputGeneration = activeVideoRenderer?.outputGeneration()
        player?.setVideoSurfaceHolder(holder)
    }

    // no-op if a newer holder already replaced this one
    fun clearSurface(holder: SurfaceHolder) = runOnMain {
        if (pendingSurfaceHolder !== holder) return@runOnMain
        pendingSurfaceHolder = null
        pendingSurfaceOutputGeneration = null
        player?.clearVideoSurfaceHolder(holder)
    }

    /**
     * Confirms that the target Activity's Surface reached the Media3 renderer.
     * The caller must have already submitted this holder through setSurface;
     * if the player was created after the callback, this method submits it once
     * and waits for the matching output generation.
     */
    fun confirmSurfaceForWindowHandoff(holder: SurfaceHolder): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Surface handoff must run on the main thread"
        }
        val surface = holder.surface.takeIf { it.isValid } ?: return false
        if (pendingSurfaceHolder !== holder) return false
        val p = player ?: return false
        val renderer = activeVideoRenderer ?: return false
        if (renderer.isOutputSurface(surface)) {
            Log.i(TAG, "Network video target output already owned: surface=${surfaceId(surface)}")
            return true
        }

        val afterGeneration = pendingSurfaceOutputGeneration ?: renderer.outputGeneration().also {
            pendingSurfaceOutputGeneration = it
            p.setVideoSurfaceHolder(holder)
        }
        val delivered = renderer.awaitOutputSurface(
            surface = surface,
            afterGeneration = afterGeneration,
            timeoutMs = SURFACE_HANDOFF_TIMEOUT_MS,
        )
        if (delivered) {
            Log.i(
                TAG,
                "Network video target output acknowledged: " +
                    "surface=${surfaceId(surface)} generation=${renderer.outputGeneration()}",
            )
        } else {
            Log.w(
                TAG,
                "Network video target output acknowledgement timed out: " +
                    "surface=${surfaceId(surface)} after=$afterGeneration",
            )
        }
        return delivered && p.playerError == null
    }

    /**
     * Detaches the current Activity-owned output and waits for Media3's
     * playback thread to finish the renderer message. This is an acknowledgement
     * boundary, not a sleep: the source Surface may be destroyed only after the
     * old codec has stopped touching its BufferQueue.
     */
    fun detachSurfaceForWindowHandoff(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Surface handoff must run on the main thread"
        }
        val sourceHolder = pendingSurfaceHolder
        handoffSurfaceHolder = sourceHolder
        pendingSurfaceHolder = null
        pendingSurfaceOutputGeneration = null
        val p = player ?: return true
        return try {
            // Send the renderer detach first and wait for the codec release.
            // Only after that acknowledgement do we update ExoPlayer's holder
            // bookkeeping, so a destroyed source Surface can never race the
            // old codec queue.
            val renderer = checkNotNull(activeVideoRenderer) {
                "Media3 video renderer was not created"
            }
            val delivered = p.createMessage(renderer)
                .setType(Renderer.MSG_SET_VIDEO_OUTPUT)
                .setPayload(null)
                .send()
                .blockUntilDelivered(SURFACE_HANDOFF_TIMEOUT_MS)
            check(delivered) { "Media3 video output detach was not delivered" }
            p.clearVideoSurface()
            check(p.playerError == null) { "Media3 rejected the video output detach" }
            Log.i(TAG, "Network video output detached before window handoff")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Network video output detach failed; keeping source window", error)
            handoffSurfaceHolder = null
            if (sourceHolder?.surface?.isValid == true) {
                pendingSurfaceHolder = sourceHolder
                pendingSurfaceOutputGeneration = activeVideoRenderer?.outputGeneration()
                p.setVideoSurfaceHolder(sourceHolder)
            }
            false
        }
    }

    /** Restores the source output when target Activity launch is rejected/expired. */
    fun restoreSurfaceAfterWindowHandoff() = runOnMain {
        val sourceHolder = handoffSurfaceHolder ?: return@runOnMain
        handoffSurfaceHolder = null
        if (pendingSurfaceHolder === sourceHolder) return@runOnMain
        val targetHolder = pendingSurfaceHolder
        pendingSurfaceHolder = null
        pendingSurfaceOutputGeneration = null
        if (targetHolder != null) {
            player?.clearVideoSurfaceHolder(targetHolder)
        } else {
            player?.clearVideoSurface()
        }
        if (sourceHolder.surface?.isValid != true) return@runOnMain
        pendingSurfaceHolder = sourceHolder
        pendingSurfaceOutputGeneration = activeVideoRenderer?.outputGeneration()
        player?.setVideoSurfaceHolder(sourceHolder)
        Log.i(TAG, "Network video output restored after cancelled window handoff")
    }

    /** Drops the retained source reference once the target Surface owns output. */
    fun commitSurfaceWindowHandoff() = runOnMain {
        handoffSurfaceHolder = null
    }

    fun stop() = runOnMain { _stopInternal(reportStopped = true) }

    fun release() = runOnMain {
        _stopInternal(reportStopped = false)
        pendingSurfaceHolder = null
        pendingSurfaceOutputGeneration = null
        handoffSurfaceHolder = null
    }

    private fun _stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        currentPlaybackId = null
        currentLocation = null
        mimeOverrides.clear()
        activeVideoRenderer = null
        pendingSurfaceOutputGeneration = null
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                playbackId = p.currentMediaItem?.mediaId,
                playable = p.playbackState == Player.STATE_READY,
                ended = p.playbackState == Player.STATE_ENDED,
            )
        )
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun isUnrecognizedInput(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is UnrecognizedInputFormatException }

    companion object {
        private const val TAG = "NetworkMediaPlayer"
        private const val REPORT_INTERVAL_MS = 250L
        private const val SURFACE_HANDOFF_TIMEOUT_MS = 1_000L

        private fun surfaceId(surface: Surface): String =
            "0x${System.identityHashCode(surface).toString(16)}"

        private val HARDWARE_VIDEO_CODEC_SELECTOR = MediaCodecSelector {
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!MimeTypes.isVideo(mimeType)) {
                decoders
            } else {
                decoders.filterNot { it.softwareOnly }.ifEmpty { decoders }
            }
        }

        private fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }
    }
}
