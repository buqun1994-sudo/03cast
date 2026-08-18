package com.tcrrry.desktopcast.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.util.Log
import com.tcrrry.desktopcast.R
import com.tcrrry.desktopcast.audio.DacpController
import com.tcrrry.desktopcast.audio.DmapParser
import com.tcrrry.desktopcast.audio.TrackInfo
import com.tcrrry.desktopcast.bridge.LogListener
import com.tcrrry.desktopcast.bridge.NativeBridge
import com.tcrrry.desktopcast.bridge.RaopCallbackHandler
import com.tcrrry.desktopcast.renderer.AudioConfig
import com.tcrrry.desktopcast.renderer.AudioRenderer
import com.tcrrry.desktopcast.renderer.PlaybackSnapshot
import com.tcrrry.desktopcast.renderer.VideoRenderer
import com.tcrrry.desktopcast.session.CastContentKind
import com.tcrrry.desktopcast.session.CastProtocol
import com.tcrrry.desktopcast.session.CastSessionLease
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Native AirPlay callback adapter; all stateful mutations are forwarded to main. */
internal class AirPlayPlaybackAdapter(
    context: Context,
    private val audioManager: AudioManager,
    private val host: CastPlaybackRouter,
) : RaopCallbackHandler, LogListener, NetworkPlaybackObserver {

    private val appContext = context.applicationContext
    private val dacpController = DacpController(appContext)
    private val airPlayConnections = AtomicInteger(0)

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()

    @Volatile private var nativeHandle = 0L
    @Volatile private var lease: CastSessionLease? = null
    private var mirrorActive = false
    private var playing = true
    private var title = ""
    private var detail = ""

    private val mutableArtwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = mutableArtwork.asStateFlow()
    private val mutableMirrorAspect = MutableStateFlow(16f / 9f)
    val mirrorAspect: StateFlow<Float> = mutableMirrorAspect.asStateFlow()

    fun attach(handle: Long, audioConfig: AudioConfig) {
        nativeHandle = handle
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
        airPlayConnections.set(0)
        releaseOutput()
    }

    fun disconnectFromUi() = host.runOnMain {
        val activeLease = activeLease() ?: return@runOnMain
        host.dropAirPlayConnections()
        airPlayConnections.set(0)
        releaseOutput()
        host.disconnectImmediately(activeLease)
    }

    fun releaseOutput() {
        lease?.let(host::stopNetworkPlayback)
        mirrorActive = false
        playing = true
        videoRenderer.reset()
        audioRenderer.stop()
        mutableArtwork.value = null
        title = ""
        detail = ""
        dacpController.reset()
        lease = null
    }

    fun stopAll() {
        releaseOutput()
        airPlayConnections.set(0)
        detach()
    }

    fun release() {
        stopAll()
        dacpController.release()
        videoRenderer.release()
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        val activeLease = lease ?: return
        if (activeLease.protocol != CastProtocol.AIRPLAY || !host.isCurrent(activeLease)) return
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) = host.runOnMain {
        val activeLease = ensureLease() ?: return@runOnMain
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        playing = true
        if (!usingScreen) {
            host.stopNetworkPlayback(activeLease)
            mirrorActive = false
            videoRenderer.reset()
            host.showContent(activeLease, CastContentKind.AUDIO, title, detail)
        }
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) = host.runOnMain {
        if (w <= 0 || h <= 0) return@runOnMain
        val activeLease = ensureLease() ?: return@runOnMain
        host.stopNetworkPlayback(activeLease)
        mirrorActive = true
        mutableMirrorAspect.value = w / h
        videoRenderer.setResolution(w.toInt(), h.toInt())
        host.showContent(activeLease, CastContentKind.MIRROR, playing = true)
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
        val activeLease = activeLease() ?: return@runOnMain
        playing = false
        if (!mirrorActive) {
            audioRenderer.stop()
            host.updatePlayback(
                activeLease,
                host.sessionState.value.positionMs,
                host.sessionState.value.durationMs,
                false,
            )
        }
    }

    override fun onConnectionInit() {
        airPlayConnections.incrementAndGet()
        host.runOnMain(::ensureLease)
        Log.i(TAG, "AirPlay client connected (${airPlayConnections.get()})")
    }

    override fun onConnectionDestroy() {
        val remaining = airPlayConnections.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            host.runOnMain {
                val activeLease = activeLease() ?: return@runOnMain
                releaseOutput()
                host.deferRemoteDisconnect(activeLease)
            }
        }
        Log.i(TAG, "AirPlay client disconnected ($remaining)")
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

    override fun onAudioOnly(audioOnly: Boolean) = host.runOnMain {
        val activeLease = ensureLease() ?: return@runOnMain
        if (audioOnly) {
            host.stopNetworkPlayback(activeLease)
            mirrorActive = false
            videoRenderer.reset()
            host.showContent(activeLease, CastContentKind.AUDIO, title, detail)
        } else if (mirrorActive) {
            host.showContent(activeLease, CastContentKind.MIRROR)
        }
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) = host.runOnMain {
        val activeLease = ensureLease() ?: return@runOnMain
        mirrorActive = false
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
        val activeLease = activeLease() ?: return@runOnMain
        if (!host.isNetworkPlaybackActive(activeLease)) return@runOnMain
        host.stopNetworkPlayback(activeLease)
        lease = null
        host.deferRemoteDisconnect(activeLease)
    }

    override fun onVideoSessionPoll() = host.runOnMain(::ensureLease)

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
        host.stopNetworkPlayback(activeLease)
        lease = null
        host.deferRemoteDisconnect(activeLease)
    }

    override fun onError(message: String) {
        val activeLease = activeLease() ?: return
        if (!host.isNetworkPlaybackActive(activeLease)) return
        host.stopNetworkPlayback(activeLease)
        lease = null
        host.reportFailure(activeLease, appContext.getString(R.string.media_playback_failed))
    }

    private fun ensureLease(): CastSessionLease? {
        val current = host.ensureSession(CastProtocol.AIRPLAY) ?: return null
        lease = current
        return current
    }

    private fun activeLease(): CastSessionLease? = lease?.takeIf(host::isCurrent)

    private companion object {
        const val TAG = "AirPlayPlaybackAdapter"
        const val AUDIO_SAMPLE_RATE = 44_100.0
    }
}
