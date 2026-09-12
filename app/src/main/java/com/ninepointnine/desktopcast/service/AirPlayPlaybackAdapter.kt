package com.ninepointnine.desktopcast.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ninepointnine.desktopcast.R
import com.ninepointnine.desktopcast.audio.DacpController
import com.ninepointnine.desktopcast.audio.DmapParser
import com.ninepointnine.desktopcast.audio.TrackInfo
import com.ninepointnine.desktopcast.bridge.LogListener
import com.ninepointnine.desktopcast.bridge.NativeBridge
import com.ninepointnine.desktopcast.bridge.RaopCallbackHandler
import com.ninepointnine.desktopcast.renderer.AudioConfig
import com.ninepointnine.desktopcast.renderer.AudioRenderer
import com.ninepointnine.desktopcast.renderer.PlaybackSnapshot
import com.ninepointnine.desktopcast.renderer.VideoRenderer
import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastProtocol
import com.ninepointnine.desktopcast.session.CastSessionLease
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Native AirPlay callback adapter; all stateful mutations are forwarded to main. */
internal class AirPlayPlaybackAdapter(
    context: Context,
    private val audioManager: AudioManager,
    private val host: CastPlaybackRouter,
) : RaopCallbackHandler, LogListener, NetworkPlaybackObserver {

    private val appContext = context.applicationContext
    private val dacpController = DacpController(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()

    @Volatile private var nativeHandle = 0L
    @Volatile private var lease: CastSessionLease? = null
    @Volatile private var mirrorActive = false
    @Volatile private var mirrorStreamToken = 0L
    /* Native tokens are monotonic for the process; retain the high-water mark
     * even after output release so a late start event cannot reclaim a session. */
    private var highestMirrorStreamToken = 0L
    @Volatile private var audioActive = false
    @Volatile private var droppedMirrorFrameCount = 0L
    private var playing = true
    private var title = ""
    private var detail = ""
    private var transportDestroySequence = 0L
    private var pendingTransportDestroy: Runnable? = null
    private var networkEndSequence = 0L
    private var pendingNetworkEnd: Runnable? = null

    private val mutableArtwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = mutableArtwork.asStateFlow()
    private val mutableMirrorAspect = MutableStateFlow(16f / 9f)
    val mirrorAspect: StateFlow<Float> = mutableMirrorAspect.asStateFlow()

    fun attach(handle: Long, audioConfig: AudioConfig) {
        nativeHandle = handle
        cancelPendingTransportDestroy()
        mirrorActive = false
        mirrorStreamToken = 0L
        audioActive = false
        mutableMirrorAspect.value = DEFAULT_MIRROR_ASPECT
        audioRenderer.attachEngine(handle)
        audioRenderer.updateConfig(audioConfig)
        videoRenderer.keyAllowFrameDrop = true
        videoRenderer.realtimeDecoderPriority = true
        videoRenderer.lowLatency = true
        videoRenderer.operatingRateHint = true
        videoRenderer.scheduledOutputBufferRelease = true
    }

    fun detach() {
        nativeHandle = 0L
        audioRenderer.detachEngine()
    }

    fun toggle() {
        val activeLease = activeLease() ?: return
        when (host.sessionState.value.content) {
            CastContentKind.NETWORK_VIDEO -> host.setNetworkPlaying(
                activeLease,
                !host.sessionState.value.playing,
            )
            CastContentKind.AUDIO -> {
                playing = !playing
                if (playing) dacpController.play() else dacpController.pause()
                host.updatePlayback(
                    activeLease,
                    host.sessionState.value.positionMs,
                    host.sessionState.value.durationMs,
                    playing,
                )
            }
            else -> Unit
        }
    }

    fun seek(positionMs: Long) {
        activeLease()?.let { host.scrubNetworkPlayback(it, positionMs / 1000f) }
    }

    fun blockOutputForDrivingSafety() {
        releaseOutput()
    }

    fun disconnectFromUi() = host.runOnMain {
        val activeLease = activeLease() ?: return@runOnMain
        host.dropAirPlayConnections()
        releaseOutput()
        host.disconnectImmediately(activeLease)
    }

    fun releaseOutput(
        restoreMirrorSurfaceGeometry: Boolean = true,
        resetMirrorAspect: Boolean = true,
    ) {
        cancelPendingTransportDestroy()
        lease?.let(host::stopNetworkPlayback)
        mirrorActive = false
        mirrorStreamToken = 0L
        audioActive = false
        playing = true
        videoRenderer.reset(restoreSurfaceGeometry = restoreMirrorSurfaceGeometry)
        audioRenderer.stop()
        if (resetMirrorAspect) mutableMirrorAspect.value = DEFAULT_MIRROR_ASPECT
        mutableArtwork.value = null
        title = ""
        detail = ""
        dacpController.reset()
        lease = null
    }

    fun stopAll() {
        releaseOutput()
        detach()
    }

    fun release() {
        stopAll()
        dacpController.release()
        videoRenderer.release()
    }

    override fun onVideoData(streamToken: Long, data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        val activeLease = lease ?: return
        if (!mirrorActive || mirrorStreamToken != streamToken ||
            activeLease.protocol != CastProtocol.AIRPLAY || !host.isCurrent(activeLease)
        ) {
            val dropped = ++droppedMirrorFrameCount
            if (dropped <= 3 || dropped % 120L == 0L) {
                Log.w(
                    TAG,
                    "Dropping AirPlay mirror frame: token=$streamToken active=$mirrorStreamToken " +
                        "mirrorActive=$mirrorActive leaseCurrent=${host.isCurrent(activeLease)} " +
                        "count=$dropped",
                )
            }
            return
        }
        droppedMirrorFrameCount = 0L
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) = host.runOnMain {
        cancelPendingTransportDestroy()
        val activeLease = ensureLease() ?: return@runOnMain
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        audioActive = true
        playing = true
        if (!usingScreen && !mirrorActive) {
            host.stopNetworkPlayback(activeLease)
            mirrorStreamToken = 0L
            videoRenderer.reset()
            mutableMirrorAspect.value = DEFAULT_MIRROR_ASPECT
            host.showContent(activeLease, CastContentKind.AUDIO, title, detail)
        } else if (mirrorActive) {
            host.showContent(activeLease, CastContentKind.MIRROR)
        } else {
            // Some senders establish the audio stream before SETUP type 110.
            // Expose the temporary audio state so mirror startup can preserve
            // the same AirPlay lease and renderer regardless of SETUP order.
            host.showContent(activeLease, CastContentKind.AUDIO, title, detail)
        }
    }

    override fun onVideoSize(
        streamToken: Long,
        srcW: Float,
        srcH: Float,
        w: Float,
        h: Float,
    ) = runMirrorControlOnMain("size token=$streamToken") {
        if (w <= 0 || h <= 0 || streamToken <= 0L) {
            return@runMirrorControlOnMain
        }
        val encodedAspect = w / h
        if (!encodedAspect.isFinite() || encodedAspect <= 0f) return@runMirrorControlOnMain
        // The size packet is emitted by the RTP thread, while the native
        // mirror-start callback comes from the RTSP thread. Both control
        // callbacks are completed synchronously on main, so this token can
        // never inherit the previous stream's aspect or lease.
        val activeLease = if (mirrorActive && mirrorStreamToken == streamToken) {
            activeLease()
        } else {
            if (streamToken <= highestMirrorStreamToken) {
                Log.i(TAG, "Ignoring stale AirPlay mirror size token=$streamToken highest=$highestMirrorStreamToken")
                null
            } else {
                activateMirrorStream(streamToken)
            }
        } ?: return@runMirrorControlOnMain
        cancelPendingTransportDestroy()
        Log.i(TAG, "AirPlay mirror size: source=${srcW}x${srcH}, encoded=${w}x${h}")
        host.stopNetworkPlayback(activeLease)
        // SurfaceView displays decoded pixels. The RTP source dimensions can
        // describe the sender's logical desktop and may differ from the actual
        // encoded frame (for example, a Mac compatibility stream); using them
        // for layout would stretch the decoded image.
        mutableMirrorAspect.value = encodedAspect
        videoRenderer.setResolution(w.roundToInt(), h.roundToInt())
        host.showContent(activeLease, CastContentKind.MIRROR, playing = true)
    }

    override fun onMirrorVideoRunning(streamToken: Long, running: Boolean) =
        runMirrorControlOnMain("running=$running token=$streamToken") {
        if (running) {
            activateMirrorStream(streamToken)
            return@runMirrorControlOnMain
        }

        if (mirrorStreamToken != streamToken) {
            Log.i(TAG, "Ignoring stale AirPlay mirror stop token=$streamToken active=$mirrorStreamToken")
            return@runMirrorControlOnMain
        }
        cancelPendingTransportDestroy()
        val activeLease = activeLease()
        if (!mirrorActive || activeLease == null) {
            mirrorActive = false
            mirrorStreamToken = 0L
            return@runMirrorControlOnMain
        }
        mirrorActive = false
        mirrorStreamToken = 0L
        if (audioActive) {
            host.showContent(activeLease, CastContentKind.AUDIO, title, detail, playing)
            videoRenderer.reset(restoreSurfaceGeometry = false)
            mutableMirrorAspect.value = DEFAULT_MIRROR_ASPECT
        } else {
            host.disconnectRemoteImmediately(activeLease)
            releaseOutput(
                restoreMirrorSurfaceGeometry = false,
                resetMirrorAspect = false,
            )
        }
        Log.i(TAG, "AirPlay mirror transport stopped")
    }

    /** Claims a new native mirror generation exactly once on the main looper. */
    private fun activateMirrorStream(streamToken: Long): CastSessionLease? {
        if (streamToken <= 0L) return null
        if (mirrorActive && mirrorStreamToken == streamToken) return activeLease()
        if (streamToken <= highestMirrorStreamToken) {
            Log.i(TAG, "Ignoring stale AirPlay mirror start token=$streamToken highest=$highestMirrorStreamToken")
            return null
        }
        cancelPendingTransportDestroy()
        highestMirrorStreamToken = streamToken
        val preserveAudio = audioActive &&
            host.sessionState.value.content == CastContentKind.AUDIO &&
            activeLease() != null
        val activeLease = host.beginAirPlayMirrorSession(preserveAudio) ?: return null
        // beginAirPlayMirrorSession may replace the coordinator generation
        // and release the previous adapter output. Reattach the returned
        // lease before any RTP frame can arrive on the native thread.
        videoRenderer.reset()
        mutableMirrorAspect.value = DEFAULT_MIRROR_ASPECT
        lease = activeLease
        mirrorStreamToken = streamToken
        mirrorActive = true
        if (!preserveAudio) audioActive = false
        host.showContent(activeLease, CastContentKind.MIRROR, playing = true)
        Log.i(TAG, "AirPlay mirror transport started token=$streamToken")
        return activeLease
    }

    override fun onVolumeChange(volume: Float) {
        val expectedLease = lease ?: return
        val fraction = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        host.runOnMain {
            if (!host.isCurrent(expectedLease)) return@runOnMain
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt(), 0)
        }
    }

    override fun onClientVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (current == 0) -144f else -30f + 30f * current / max
    }

    override fun onAudioTeardown() = host.runOnMain {
        cancelPendingTransportDestroy()
        audioActive = false
        audioRenderer.stop()
        val activeLease = activeLease() ?: return@runOnMain
        playing = false
        if (!mirrorActive && !host.isNetworkPlaybackActive(activeLease)) {
            mirrorStreamToken = 0L
            releaseOutput()
            host.disconnectRemoteImmediately(activeLease)
        } else if (!mirrorActive) {
            host.updatePlayback(
                activeLease,
                host.sessionState.value.positionMs,
                host.sessionState.value.durationMs,
                false,
            )
        }
    }

    override fun onConnectionInit() {
        host.runOnMain {
            cancelPendingTransportDestroy()
            ensureLease()
        }
        Log.i(TAG, "AirPlay transport connection initialized")
    }

    override fun onConnectionDestroy() {
        // UxPlay creates one HTTP callback for every RAOP/AirPlay/HLS helper
        // connection. It is therefore only a fallback signal; authoritative
        // mirror/audio teardown comes from their protocol callbacks above.
        host.runOnMain {
            cancelPendingConnectionDestroy()
            activeLease() ?: return@runOnMain
            val sequence = transportDestroySequence
            val task = Runnable {
                if (sequence != transportDestroySequence) return@Runnable
                pendingTransportDestroy = null
                val activeLease = activeLease() ?: return@Runnable
                if (mirrorActive || audioActive || host.isNetworkPlaybackActive(activeLease)) return@Runnable
                host.disconnectRemoteImmediately(activeLease)
                releaseOutput(
                    restoreMirrorSurfaceGeometry = false,
                    resetMirrorAspect = false,
                )
            }
            pendingTransportDestroy = task
            mainHandler.postDelayed(task, TRANSPORT_DESTROY_CONFIRMATION_MS)
        }
        Log.i(TAG, "AirPlay transport connection destroyed")
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(TAG, "AirPlay connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        Log.w(TAG, "Unexpected AirPlay PIN request while pairing is disabled")
    }

    override fun onMetadata(data: ByteArray) {
        val info = TrackInfo.fromDmap(DmapParser.parse(data), mutableArtwork.value)
        host.runOnMain {
            val activeLease = activeLease() ?: return@runOnMain
            title = info.title
            detail = listOf(info.artist, info.album).filter { it.isNotBlank() }.joinToString(" · ")
            host.updateMetadata(activeLease, title, detail)
        }
    }

    override fun onCoverArt(data: ByteArray) {
        val expectedLease = lease ?: return
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        host.runOnMain {
            if (host.isCurrent(expectedLease)) mutableArtwork.value = bitmap
        }
    }

    override fun onProgress(start: Long, curr: Long, end: Long) = host.runOnMain {
        val activeLease = activeLease() ?: return@runOnMain
        val position = ((curr - start) / AUDIO_SAMPLE_RATE * 1000).toLong().coerceAtLeast(0)
        val duration = ((end - start) / AUDIO_SAMPLE_RATE * 1000).toLong().coerceAtLeast(0)
        host.updatePlayback(activeLease, position, duration, playing)
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        val expectedLease = lease ?: return
        host.runOnMain {
            if (host.isCurrent(expectedLease)) dacpController.update(dacpId, activeRemote)
        }
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) = host.runOnMain {
        cancelPendingTransportDestroy()
        val activeLease = ensureLease() ?: return@runOnMain
        mirrorActive = false
        mirrorStreamToken = 0L
        audioActive = false
        mutableMirrorAspect.value = DEFAULT_MIRROR_ASPECT
        videoRenderer.reset()
        audioRenderer.stop()
        host.startNetworkPlayback(activeLease, location, startPositionSeconds, this)
        host.showContent(activeLease, CastContentKind.NETWORK_VIDEO, playing = true)
    }

    override fun onVideoScrub(positionSeconds: Float) = host.runOnMain {
        activeLease()?.let { host.scrubNetworkPlayback(it, positionSeconds) }
    }

    override fun onVideoRate(rate: Float) = host.runOnMain {
        activeLease()?.let { host.setNetworkRate(it, rate) }
    }

    override fun onVideoStop() = host.runOnMain {
        cancelPendingTransportDestroy()
        val activeLease = activeLease() ?: return@runOnMain
        if (!host.isNetworkPlaybackActive(activeLease)) return@runOnMain
        host.stopNetworkPlayback(activeLease)
        mirrorStreamToken = 0L
        lease = null
        host.disconnectRemoteImmediately(activeLease)
    }

    override fun onVideoSessionPoll() = host.runOnMain {
        cancelPendingTransportDestroy()
        ensureLease()
    }

    override fun onLog(msg: String) {
        Log.i(TAG, msg)
    }

    override fun onPlaybackInfo(snapshot: PlaybackSnapshot) {
        val activeLease = activeLease() ?: return
        if (!host.isNetworkPlaybackActive(activeLease)) return
        val handle = nativeHandle
        if (handle != 0L) {
            NativeBridge.nativeUpdatePlaybackInfo(
                handle,
                snapshot.position,
                snapshot.duration,
                snapshot.rate,
                snapshot.ready,
                snapshot.playWhenReady,
            )
        }
        host.updatePlayback(
            activeLease,
            (snapshot.position * 1000).toLong(),
            if (snapshot.duration > 0) (snapshot.duration * 1000).toLong() else 0,
            snapshot.playWhenReady && !snapshot.buffering,
        )
    }

    override fun onVideoSize(width: Int, height: Int, aspect: Float) = Unit

    override fun onTitle(title: String?) {
        val activeLease = activeLease() ?: return
        if (!title.isNullOrBlank()) host.updateMetadata(activeLease, title, detail)
    }

    override fun onHasVideo(hasVideo: Boolean) = Unit

    override fun onEnded() {
        val activeLease = activeLease() ?: return
        if (!host.isNetworkPlaybackActive(activeLease)) return
        /* AirPlay playlistInsert can arrive immediately after the renderer's
         * EOS callback. Keep the lease alive long enough for that action to
         * switch the player to the next prepared item; the task below remains
         * the terminal-item fallback. */
        cancelPendingConnectionDestroy()
        cancelPendingNetworkEnd()
        val sequence = networkEndSequence
        val task = Runnable {
            if (sequence != networkEndSequence) return@Runnable
            pendingNetworkEnd = null
            val currentLease = activeLease() ?: return@Runnable
            if (!host.isNetworkPlaybackActive(currentLease)) return@Runnable
            host.stopNetworkPlayback(currentLease)
            mirrorStreamToken = 0L
            lease = null
            host.disconnectRemoteImmediately(currentLease)
        }
        pendingNetworkEnd = task
        mainHandler.postDelayed(task, NETWORK_END_CONFIRMATION_MS)
    }

    override fun onError(message: String) {
        val activeLease = activeLease() ?: return
        if (!host.isNetworkPlaybackActive(activeLease)) return
        host.stopNetworkPlayback(activeLease)
        mirrorStreamToken = 0L
        lease = null
        host.reportFailure(activeLease, appContext.getString(R.string.media_playback_failed))
    }

    private fun ensureLease(): CastSessionLease? {
        val current = host.ensureSession(CastProtocol.AIRPLAY) ?: return null
        lease = current
        return current
    }

    private fun activeLease(): CastSessionLease? = lease?.takeIf(host::isCurrent)

    /**
     * Mirror control callbacks bracket the RTP data thread. Completing their
     * main-thread mutation before returning guarantees that the first access
     * unit (which normally carries SPS/PPS/VPS) cannot outrun session setup or
     * the encoded-size update.
     */
    private fun runMirrorControlOnMain(event: String, action: () -> Unit) {
        try {
            host.runOnMainBlocking(action)
        } catch (error: Exception) {
            Log.e(TAG, "AirPlay mirror control failed: $event", error)
        }
    }

    private fun cancelPendingTransportDestroy() {
        cancelPendingConnectionDestroy()
        cancelPendingNetworkEnd()
    }

    private fun cancelPendingConnectionDestroy() {
        transportDestroySequence += 1
        pendingTransportDestroy?.let(mainHandler::removeCallbacks)
        pendingTransportDestroy = null
    }

    private fun cancelPendingNetworkEnd() {
        networkEndSequence += 1
        pendingNetworkEnd?.let(mainHandler::removeCallbacks)
        pendingNetworkEnd = null
    }

    private companion object {
        const val TAG = "AirPlayPlaybackAdapter"
        const val AUDIO_SAMPLE_RATE = 44_100.0
        const val DEFAULT_MIRROR_ASPECT = 16f / 9f
        const val TRANSPORT_DESTROY_CONFIRMATION_MS = 300L
        const val NETWORK_END_CONFIRMATION_MS = 1_500L
    }
}
