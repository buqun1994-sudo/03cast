package com.tcrrry.desktopcast.service

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import com.tcrrry.desktopcast.bridge.RaopCallbackHandler
import com.tcrrry.desktopcast.dlna.DlnaPlaybackController
import com.tcrrry.desktopcast.dlna.DlnaPlaybackSnapshot
import com.tcrrry.desktopcast.renderer.AudioConfig
import com.tcrrry.desktopcast.renderer.NetworkMediaPlayer
import com.tcrrry.desktopcast.renderer.PlaybackSnapshot
import com.tcrrry.desktopcast.renderer.VideoRenderer
import com.tcrrry.desktopcast.session.CastContentKind
import com.tcrrry.desktopcast.session.CastProtocol
import com.tcrrry.desktopcast.session.CastSessionCoordinator
import com.tcrrry.desktopcast.session.CastSessionLease
import com.tcrrry.desktopcast.session.CastSessionState
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sole owner of the active media session and shared network-video output.
 *
 * Protocol adapters own only their protocol-specific callback state. They must
 * obtain a lease here before changing playback, which keeps DLNA, AirPlay HLS,
 * mirroring and audio under one session authority.
 */
class CastPlaybackRouter(
    context: Context,
    scope: CoroutineScope,
    private val coordinator: CastSessionCoordinator,
    audioManager: AudioManager,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkPlayer = NetworkMediaPlayer(appContext)
    private val dlnaAdapter = DlnaPlaybackAdapter(appContext, scope, this)
    private val airPlayAdapter = AirPlayPlaybackAdapter(appContext, audioManager, this)
    private var networkSession: NetworkPlaybackSession? = null

    private val mutableMediaAspect = MutableStateFlow(16f / 9f)
    val mediaAspect: StateFlow<Float> = mutableMediaAspect.asStateFlow()
    val artwork get() = airPlayAdapter.artwork
    val image get() = dlnaAdapter.image
    val mirrorAspect get() = airPlayAdapter.mirrorAspect
    val sessionState: StateFlow<CastSessionState> = coordinator.state
    val videoRenderer: VideoRenderer get() = airPlayAdapter.videoRenderer
    val dlnaController: DlnaPlaybackController get() = dlnaAdapter
    val airPlayCallbacks: RaopCallbackHandler get() = airPlayAdapter

    /** Runtime supplies these protocol side-effects after it has started. */
    var onDropAirPlayConnections: (() -> Unit)? = null
    var onDlnaTransportChanged: (() -> Unit)? = null

    init {
        configureNetworkPlayer()
    }

    fun attachAirPlay(handle: Long, audioConfig: AudioConfig) = runOnMain {
        airPlayAdapter.attach(handle, audioConfig)
    }

    fun setMirrorSurface(surface: Surface) = videoRenderer.setSurface(surface)

    fun clearMirrorSurface(surface: Surface) = videoRenderer.clearSurface(surface)

    fun setMediaSurface(holder: SurfaceHolder) = networkPlayer.setSurface(holder)

    fun clearMediaSurface(holder: SurfaceHolder) = networkPlayer.clearSurface(holder)

    fun togglePlayback() = runOnMain {
        when (sessionState.value.protocol) {
            CastProtocol.DLNA -> dlnaAdapter.toggle()
            CastProtocol.AIRPLAY -> airPlayAdapter.toggle()
            null -> Unit
        }
    }

    fun seekToPosition(positionMs: Long) = runOnMain {
        when (sessionState.value.protocol) {
            CastProtocol.DLNA -> dlnaAdapter.seekFromUi(positionMs)
            CastProtocol.AIRPLAY -> airPlayAdapter.seek(positionMs)
            null -> Unit
        }
    }

    fun dlnaSnapshot(): DlnaPlaybackSnapshot = dlnaAdapter.snapshot()

    /** Stops outputs while keeping receiver listeners available for a restart. */
    fun stopOutputs() = runOnMain(::stopOutputsInternal)

    /** Releases player objects only when the service itself is being destroyed. */
    fun release() = runOnMain {
        stopOutputsInternal()
        networkPlayer.release()
        airPlayAdapter.release()
    }

    /** Starts a new external sender or media item; never use this for a callback update. */
    internal fun beginSession(protocol: CastProtocol): CastSessionLease? {
        checkOnMainThread()
        val previousProtocol = sessionState.value.protocol
        val lease = coordinator.beginSession(protocol) ?: return null
        releaseProtocolOutput(previousProtocol)
        clearStaleNetworkSession()
        return lease
    }

    /** Reuses the current generation for callbacks belonging to the same sender. */
    internal fun ensureSession(protocol: CastProtocol): CastSessionLease? {
        checkOnMainThread()
        return coordinator.leaseFor(protocol) ?: beginSession(protocol)
    }

    internal fun isCurrent(lease: CastSessionLease): Boolean = coordinator.isCurrent(lease)

    internal fun showContent(
        lease: CastSessionLease,
        content: CastContentKind,
        title: String = "",
        detail: String = "",
        playing: Boolean = true,
        canSeek: Boolean = false,
    ) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.showContent(lease.protocol, content, title, detail, playing, canSeek)
    }

    internal fun updatePlayback(
        lease: CastSessionLease,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
    ) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.updatePlayback(lease.protocol, positionMs, durationMs, playing)
    }

    internal fun updateMetadata(
        lease: CastSessionLease,
        title: String,
        detail: String = "",
    ) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.updateMetadata(lease.protocol, title, detail)
    }

    internal fun disconnect(lease: CastSessionLease) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.disconnected(lease.protocol)
    }

    internal fun reportFailure(lease: CastSessionLease, message: String) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.recoverableError(lease.protocol, message)
    }

    internal fun startNetworkPlayback(
        lease: CastSessionLease,
        location: String,
        startPositionSeconds: Float,
        observer: NetworkPlaybackObserver,
    ) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        stopNetworkPlayback()
        networkSession = NetworkPlaybackSession(lease, observer)
        mutableMediaAspect.value = 16f / 9f
        networkPlayer.play(location, startPositionSeconds)
    }

    internal fun stopNetworkPlayback(lease: CastSessionLease) {
        checkOnMainThread()
        if (networkSession?.lease != lease) return
        stopNetworkPlayback()
    }

    internal fun isNetworkPlaybackActive(lease: CastSessionLease): Boolean =
        networkSession?.lease == lease && isCurrent(lease)

    internal fun scrubNetworkPlayback(lease: CastSessionLease, positionSeconds: Float) {
        checkOnMainThread()
        if (isNetworkPlaybackActive(lease)) networkPlayer.scrub(positionSeconds)
    }

    internal fun setNetworkRate(lease: CastSessionLease, rate: Float) {
        checkOnMainThread()
        if (isNetworkPlaybackActive(lease)) networkPlayer.setRate(rate)
    }

    internal fun setNetworkPlaying(lease: CastSessionLease, playing: Boolean) {
        checkOnMainThread()
        if (isNetworkPlaybackActive(lease)) networkPlayer.setPlaying(playing)
    }

    internal fun setNetworkVolume(lease: CastSessionLease, volume: Float) {
        checkOnMainThread()
        if (isNetworkPlaybackActive(lease)) networkPlayer.setVolume(volume)
    }

    internal fun dropAirPlayConnections() {
        checkOnMainThread()
        onDropAirPlayConnections?.invoke()
    }

    internal fun notifyDlnaTransportChanged() {
        checkOnMainThread()
        onDlnaTransportChanged?.invoke()
    }

    internal fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    internal fun runOnMainBlocking(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val command = Runnable {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        mainHandler.post(command)
        try {
            if (!latch.await(MAIN_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mainHandler.removeCallbacks(command)
                throw InterruptedIOException("Playback command timed out")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Playback command interrupted")
        }
        failure.get()?.let { throw it }
    }

    private fun configureNetworkPlayer() {
        networkPlayer.onPlaybackInfo = { snapshot ->
            runOnMain { withActiveNetworkObserver { it.onPlaybackInfo(snapshot) } }
        }
        networkPlayer.onVideoSize = { width, height, aspect ->
            runOnMain {
                if (width > 0 && height > 0) mutableMediaAspect.value = aspect
                withActiveNetworkObserver { it.onVideoSize(width, height, aspect) }
            }
        }
        networkPlayer.onTitle = { title ->
            runOnMain { withActiveNetworkObserver { it.onTitle(title) } }
        }
        networkPlayer.onHasVideo = { hasVideo ->
            runOnMain { withActiveNetworkObserver { it.onHasVideo(hasVideo) } }
        }
        networkPlayer.onEnded = {
            runOnMain { withActiveNetworkObserver(NetworkPlaybackObserver::onEnded) }
        }
        networkPlayer.onError = { message ->
            runOnMain { withActiveNetworkObserver { it.onError(message) } }
        }
    }

    private fun withActiveNetworkObserver(action: (NetworkPlaybackObserver) -> Unit) {
        checkOnMainThread()
        val session = networkSession ?: return
        if (!isCurrent(session.lease)) {
            stopNetworkPlayback()
            return
        }
        action(session.observer)
    }

    private fun releaseProtocolOutput(protocol: CastProtocol?) {
        when (protocol) {
            CastProtocol.AIRPLAY -> {
                dropAirPlayConnections()
                airPlayAdapter.releaseOutput()
            }
            CastProtocol.DLNA -> {
                dlnaAdapter.releaseOutput(clearMedia = false)
                notifyDlnaTransportChanged()
            }
            null -> Unit
        }
    }

    private fun clearStaleNetworkSession() {
        val session = networkSession ?: return
        if (!isCurrent(session.lease)) stopNetworkPlayback()
    }

    private fun stopOutputsInternal() {
        checkOnMainThread()
        dlnaAdapter.releaseOutput(clearMedia = true)
        airPlayAdapter.stopAll()
        stopNetworkPlayback()
        mutableMediaAspect.value = 16f / 9f
    }

    private fun stopNetworkPlayback() {
        checkOnMainThread()
        networkSession = null
        networkPlayer.stop()
    }

    private fun checkOnMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CastPlaybackRouter mutation must run on the main thread"
        }
    }

    private data class NetworkPlaybackSession(
        val lease: CastSessionLease,
        val observer: NetworkPlaybackObserver,
    )

    private companion object {
        const val MAIN_COMMAND_TIMEOUT_MS = 2_000L
    }
}

/** Callbacks from the one shared Media3 player, scoped by a router-managed lease. */
internal interface NetworkPlaybackObserver {
    fun onPlaybackInfo(snapshot: PlaybackSnapshot)
    fun onVideoSize(width: Int, height: Int, aspect: Float)
    fun onTitle(title: String?)
    fun onHasVideo(hasVideo: Boolean)
    fun onEnded()
    fun onError(message: String)
}
