package com.ninepointnine.desktopcast.service

import android.content.Context
import android.net.Network
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ninepointnine.desktopcast.renderer.MediaMimeResolver
import com.ninepointnine.desktopcast.renderer.NetworkItemConfiguration
import androidx.media3.common.Player
import com.ninepointnine.desktopcast.bridge.RaopCallbackHandler
import com.ninepointnine.desktopcast.dlna.DlnaPlaybackController
import com.ninepointnine.desktopcast.dlna.DlnaPlaybackSnapshot
import com.ninepointnine.desktopcast.renderer.AudioConfig
import com.ninepointnine.desktopcast.renderer.MirrorDecoderProfile
import com.ninepointnine.desktopcast.renderer.NetworkMediaPlayer
import com.ninepointnine.desktopcast.renderer.PlaybackSnapshot
import com.ninepointnine.desktopcast.renderer.VideoRenderer
import com.ninepointnine.desktopcast.safety.DrivingPlaybackInterlock
import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastPhase
import com.ninepointnine.desktopcast.session.CastProtocol
import com.ninepointnine.desktopcast.session.CastSessionCoordinator
import com.ninepointnine.desktopcast.session.CastSessionEndEvent
import com.ninepointnine.desktopcast.session.CastSessionEndReason
import com.ninepointnine.desktopcast.session.CastSessionLease
import com.ninepointnine.desktopcast.session.CastSessionState
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sole owner of the active media session and shared network-video output.
 *
 * Protocol adapters own only their protocol-specific callback state. They must
 * obtain a lease here before changing playback, which keeps DLNA, AirPlay HLS,
 * mirroring and audio under one session authority.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CastPlaybackRouter internal constructor(
    context: Context,
    scope: CoroutineScope,
    private val coordinator: CastSessionCoordinator,
    audioManager: AudioManager,
    private val commercialAccess: CastCommercialAccessPort,
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val physicalNetwork = AtomicReference<Network?>(null)
    private val networkPlayer = NetworkMediaPlayer(
        context = appContext,
        physicalNetworkProvider = { physicalNetwork.get() },
    )
    private val dlnaAdapter = DlnaPlaybackAdapter(
        appContext,
        scope,
        this,
        physicalNetworkProvider = { physicalNetwork.get() },
    )
    private val airPlayAdapter = AirPlayPlaybackAdapter(appContext, audioManager, this)
    private val drivingPlaybackInterlock = DrivingPlaybackInterlock()
    private var networkSession: NetworkPlaybackSession? = null
    private val mutableQueueState = MutableStateFlow(PlaybackQueueState())
    internal val queueState = mutableQueueState.asStateFlow()
    private val networkQueue = NetworkPlaybackQueue(
        player = object : PlaybackQueuePlayer {
            override fun sync(items: List<PlaybackQueueItem>) {
                networkPlayer.syncPlaylist(items.map { item ->
                    MediaItem.Builder().setMediaId(item.playbackId).setUri(item.uri)
                        .setMimeType(MediaMimeResolver.resolve(item.uri, item.mimeType))
                        .setTag(NetworkItemConfiguration(item.allowHlsFallback, (item.startPositionSeconds * 1000).toLong()))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.detail).build())
                        .build()
                })
            }
            override fun select(item: PlaybackQueueItem, playing: Boolean) =
                networkPlayer.selectMediaItem(item.playbackId, (item.startPositionSeconds * 1000).toLong(), playing)
            override fun retry(playing: Boolean) = networkPlayer.retryCurrent(playing)
        },
        scheduler = QueueScheduler { delay, action ->
            val task = Runnable(action)
            mainHandler.postDelayed(task, delay)
            QueueCancellation { mainHandler.removeCallbacks(task) }
        },
        onStateChanged = { state ->
            if (mutableQueueState.value != state) {
                if (mutableQueueState.value.current?.playbackId != state.current?.playbackId ||
                    mutableQueueState.value.current?.status != state.current?.status ||
                    mutableQueueState.value.items.size != state.items.size ||
                    mutableQueueState.value.awaitingNext != state.awaitingNext
                ) android.util.Log.i("PlaybackQueue", "revision=${state.revision} protocol=${state.current?.protocol} " +
                    "item=${state.current?.playbackId} state=${state.current?.status} count=${state.items.size} " +
                    "retry=${state.current?.retryCount} awaitingNext=${state.awaitingNext}")
                mutableQueueState.value = state
                withActiveNetworkObserver { it.onQueueChanged(state) }
            }
        },
        onQueueEnded = { withActiveNetworkObserver { it.onEnded() } },
        onQueueFailed = { message -> withActiveNetworkObserver { it.onError(message) } },
    )
    private var sessionEndSequence = 0L

    private val mutableMediaAspect = MutableStateFlow(16f / 9f)
    private val mutableSessionEndEvent = MutableStateFlow<CastSessionEndEvent?>(null)
    val mediaAspect: StateFlow<Float> = mutableMediaAspect.asStateFlow()
    internal val networkQueueState: PlaybackQueueState get() = networkQueue.state
    val artwork get() = airPlayAdapter.artwork
    val image get() = dlnaAdapter.image
    val mirrorAspect get() = airPlayAdapter.mirrorAspect
    val sessionState: StateFlow<CastSessionState> = coordinator.state
    val sessionEndEvent: StateFlow<CastSessionEndEvent?> = mutableSessionEndEvent.asStateFlow()
    private val mediaControlBridge = CastMediaControlBridge(
        looper = Looper.getMainLooper(),
        snapshot = { sessionState.value },
        setPlaying = ::setPlayingFromControls,
        onSeekRequested = ::seekToPosition,
    )
    val mediaControlPlayer: Player get() = mediaControlBridge
    val videoRenderer: VideoRenderer get() = airPlayAdapter.videoRenderer
    val dlnaController: DlnaPlaybackController get() = dlnaAdapter
    val airPlayCallbacks: RaopCallbackHandler get() = airPlayAdapter

    /** Runtime supplies these protocol side-effects after it has started. */
    var onDropAirPlayConnections: (() -> Unit)? = null
    var onDlnaTransportChanged: (() -> Unit)? = null

    init {
        configureNetworkPlayer()
        scope.launch {
            sessionState.collect { mediaControlBridge.refresh() }
        }
    }

    fun beginReceiverLifecycle() = runOnMain {
        drivingPlaybackInterlock.beginReceiverLifecycle()
        mediaControlBridge.refresh()
    }

    /** Supplies the physical network selected for sender-local media URLs. */
    internal fun setPhysicalNetwork(network: Network?) {
        physicalNetwork.set(network)
    }

    fun attachAirPlay(handle: Long, audioConfig: AudioConfig) = runOnMain {
        airPlayAdapter.attach(handle, audioConfig)
    }

    internal fun configureMirrorDecoder(profile: MirrorDecoderProfile) =
        videoRenderer.configureDecoderProfile(profile)

    fun setMirrorSurface(holder: SurfaceHolder, bufferWidth: Int = 0, bufferHeight: Int = 0) =
        videoRenderer.setSurface(holder, bufferWidth, bufferHeight)

    fun clearMirrorSurface(holder: SurfaceHolder) = videoRenderer.clearSurface(holder)

    fun setMediaSurface(holder: SurfaceHolder) = networkPlayer.setSurface(holder)

    fun clearMediaSurface(holder: SurfaceHolder) = networkPlayer.clearSurface(holder)

    /**
     * Establishes the media-side boundary for a cross-Activity window handoff.
     * Both decoders release their Activity-owned BufferQueue before the
     * navigator is allowed to launch the target task.
     */
    internal fun prepareWindowHandoff(): Boolean {
        checkOnMainThread()
        val networkDetached = networkPlayer.detachSurfaceForWindowHandoff()
        videoRenderer.detachSurfaceForWindowHandoff()
        if (networkDetached) return true

        // A failed preflight must leave the visible source window usable and
        // must never hand a half-detached session to a new Activity.
        networkPlayer.restoreSurfaceAfterWindowHandoff()
        videoRenderer.restoreSurfaceAfterWindowHandoff()
        return false
    }

    internal fun restoreWindowHandoffOutput() {
        checkOnMainThread()
        networkPlayer.restoreSurfaceAfterWindowHandoff()
        videoRenderer.restoreSurfaceAfterWindowHandoff()
    }

    /**
     * Verifies that the target Activity owns the output queue before the
     * window token can retire the source Activity.
     */
    internal fun confirmWindowHandoffOutput(
        content: CastContentKind,
        targetHolder: SurfaceHolder?,
    ): Boolean {
        checkOnMainThread()
        return when (content) {
            CastContentKind.NETWORK_VIDEO ->
                targetHolder != null && networkPlayer.confirmSurfaceForWindowHandoff(targetHolder)
            CastContentKind.MIRROR ->
                targetHolder != null && videoRenderer.isSurfaceBound(targetHolder)
            CastContentKind.NONE,
            CastContentKind.AUDIO,
            CastContentKind.IMAGE,
            -> true
        }
    }

    internal fun commitWindowHandoffOutput() {
        checkOnMainThread()
        networkPlayer.commitSurfaceWindowHandoff()
        videoRenderer.commitSurfaceWindowHandoff()
    }

    fun togglePlayback() = runOnMain {
        if (drivingPlaybackInterlock.isBlocked) return@runOnMain
        when (sessionState.value.protocol) {
            CastProtocol.DLNA -> dlnaAdapter.toggle()
            CastProtocol.AIRPLAY -> airPlayAdapter.toggle()
            null -> Unit
        }
    }

    fun seekToPosition(positionMs: Long) = runOnMain {
        if (drivingPlaybackInterlock.isBlocked) return@runOnMain
        when (sessionState.value.protocol) {
            CastProtocol.DLNA -> dlnaAdapter.seekFromUi(positionMs)
            CastProtocol.AIRPLAY -> airPlayAdapter.seek(positionMs)
            null -> Unit
        }
    }

    fun nextVideo(): Boolean = moveVideo(+1)
    fun previousVideo(): Boolean = moveVideo(-1)

    private fun moveVideo(direction: Int): Boolean {
        checkOnMainThread()
        val session = networkSession ?: return false
        if (!isCurrent(session.lease) || sessionState.value.content != CastContentKind.NETWORK_VIDEO) return false
        return if (direction > 0) networkQueue.next() else networkQueue.previous()
    }

    fun dlnaSnapshot(): DlnaPlaybackSnapshot = dlnaAdapter.snapshot()

    /** Ends only the active sender session while keeping both receiver listeners available. */
    fun disconnectCurrentSession() = runOnMain {
        val protocol = sessionState.value.protocol ?: return@runOnMain
        when (protocol) {
            CastProtocol.DLNA -> dlnaAdapter.disconnectFromUi()
            CastProtocol.AIRPLAY -> airPlayAdapter.disconnectFromUi()
        }
        if (sessionState.value.protocol == protocol) {
            releaseProtocolOutput(protocol)
            stopNetworkPlayback()
            coordinator.disconnected(protocol)
            publishSessionEnd(protocol, CastSessionEndReason.USER_REQUEST)
        }
    }

    /** Irreversibly blocks this receiver lifecycle before releasing every output. */
    fun blockForDrivingSafety() = runOnMain {
        if (drivingPlaybackInterlock.isBlocked) return@runOnMain
        drivingPlaybackInterlock.block()
        dropAirPlayConnections()
        dlnaAdapter.releaseOutput(clearMedia = true)
        notifyDlnaTransportChanged()
        airPlayAdapter.blockOutputForDrivingSafety()
        stopNetworkPlayback()
        mutableMediaAspect.value = 16f / 9f
        mediaControlBridge.refresh()
    }

    /** Releases only media output after entitlement revocation or expiry. */
    fun blockForCommercialAccess() = runOnMain {
        if (sessionState.value.phase !in setOf(
                CastPhase.CONNECTING,
                CastPhase.PLAYING,
                CastPhase.MIRRORING,
                CastPhase.AUDIO,
            )
        ) return@runOnMain
        // Invalidate the generation first so callbacks racing with release
        // cannot reclaim output or rewrite the waiting state.
        val protocol = sessionState.value.protocol
        coordinator.commercialAccessEnded()
        when (protocol) {
            CastProtocol.AIRPLAY -> {
                dropAirPlayConnections()
                airPlayAdapter.releaseOutput()
            }
            CastProtocol.DLNA -> {
                dlnaAdapter.releaseOutput(clearMedia = true)
                notifyDlnaTransportChanged()
            }
            null -> Unit
        }
        stopNetworkPlayback()
        mutableMediaAspect.value = 16f / 9f
        mediaControlBridge.refresh()
    }

    /** Stops outputs while keeping receiver listeners available for a restart. */
    fun stopOutputs() = runOnMain(::stopOutputsInternal)

    /** Releases player objects only when the service itself is being destroyed. */
    fun release() = runOnMain {
        stopOutputsInternal()
        mediaControlBridge.release()
        networkPlayer.release()
        airPlayAdapter.release()
    }

    /** Starts a new external sender or media item; never use this for a callback update. */
    internal fun beginSession(protocol: CastProtocol): CastSessionLease? {
        checkOnMainThread()
        if (!commercialAccess.hasCurrentAccess()) {
            commercialAccess.onMediaAttemptDenied()
            return null
        }
        if (drivingPlaybackInterlock.isBlocked) return null
        val previousProtocol = sessionState.value.protocol
        val lease = coordinator.beginSession(protocol) ?: return null
        releaseProtocolOutput(previousProtocol)
        clearStaleNetworkSession()
        return lease
    }

    /**
     * Claims a fresh AirPlay generation for a newly started RTP mirror stream.
     * The stream-start callback is emitted by the native connection that must
     * remain alive, so the normal AirPlay takeover path (which drops every
     * native connection) cannot be used when the previous owner is also
     * AirPlay.
     */
    internal fun beginAirPlayStreamSession(preserveAudio: Boolean): CastSessionLease? {
        checkOnMainThread()
        if (!commercialAccess.hasCurrentAccess()) {
            commercialAccess.onMediaAttemptDenied()
            return null
        }
        if (drivingPlaybackInterlock.isBlocked) return null
        if (preserveAudio &&
            sessionState.value.protocol == CastProtocol.AIRPLAY &&
            sessionState.value.content == CastContentKind.AUDIO
        ) {
            return coordinator.leaseFor(CastProtocol.AIRPLAY)
        }
        if (sessionState.value.protocol != CastProtocol.AIRPLAY) {
            return beginSession(CastProtocol.AIRPLAY)
        }
        val lease = coordinator.beginSession(CastProtocol.AIRPLAY) ?: return null
        airPlayAdapter.releaseOutput()
        clearStaleNetworkSession()
        return lease
    }

    /** Reuses the current generation for callbacks belonging to the same sender. */
    internal fun ensureSession(protocol: CastProtocol): CastSessionLease? {
        checkOnMainThread()
        if (!commercialAccess.hasCurrentAccess()) {
            commercialAccess.onMediaAttemptDenied()
            return null
        }
        if (drivingPlaybackInterlock.isBlocked) return null
        return coordinator.leaseFor(protocol) ?: beginSession(protocol)
    }

    internal fun isCurrent(lease: CastSessionLease): Boolean =
        commercialAccess.hasCurrentAccess() &&
            !drivingPlaybackInterlock.isBlocked && coordinator.isCurrent(lease)

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

    internal fun disconnectImmediately(lease: CastSessionLease) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.disconnected(lease.protocol)
        publishSessionEnd(lease.protocol, CastSessionEndReason.USER_REQUEST)
    }

    /** Ends a remote session immediately without changing the current window mode. */
    internal fun disconnectRemoteImmediately(lease: CastSessionLease) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        coordinator.disconnected(lease.protocol)
        publishSessionEnd(lease.protocol, CastSessionEndReason.REMOTE_DISCONNECTED)
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
        declaredMimeType: String? = null,
        allowHlsFallback: Boolean = false,
        itemId: String,
        title: String = "",
        detail: String = "",
        metadata: String = "",
        content: CastContentKind = CastContentKind.NETWORK_VIDEO,
        playing: Boolean = true,
    ) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        if (networkSession?.lease != lease) {
            networkSession = null
            networkQueue.clear()
            mutableMediaAspect.value = 16f / 9f
        }
        networkSession = NetworkPlaybackSession(lease, observer)
        networkQueue.load(PlaybackQueueItem(
            id = itemId, protocol = lease.protocol, uri = location,
            startPositionSeconds = startPositionSeconds, mimeType = declaredMimeType,
            title = title, detail = detail, metadata = metadata, content = content,
            allowHlsFallback = allowHlsFallback,
        ), playing)
    }

    internal fun setNextNetworkPlayback(lease: CastSessionLease, item: PlaybackQueueItem?) {
        checkOnMainThread()
        if (!isNetworkPlaybackActive(lease)) return
        networkQueue.setNext(item)
    }

    /** Adds an item to the current sender queue without replacing its player. */
    internal fun appendNetworkPlayback(
        lease: CastSessionLease,
        location: String,
        startPositionSeconds: Float,
        observer: NetworkPlaybackObserver,
        itemId: String,
        title: String = "",
        detail: String = "",
        metadata: String = "",
        content: CastContentKind = CastContentKind.NETWORK_VIDEO,
        declaredMimeType: String? = null,
        allowHlsFallback: Boolean = false,
    ) {
        checkOnMainThread()
        if (!isCurrent(lease)) return
        if (networkSession?.lease != lease) {
            startNetworkPlayback(
                lease = lease,
                location = location,
                startPositionSeconds = startPositionSeconds,
                observer = observer,
                declaredMimeType = declaredMimeType,
                allowHlsFallback = allowHlsFallback,
                itemId = itemId,
                title = title,
                detail = detail,
                metadata = metadata,
                content = content,
                playing = false,
            )
            return
        }
        networkSession = NetworkPlaybackSession(lease, observer)
        networkQueue.append(
            PlaybackQueueItem(
                id = itemId,
                protocol = lease.protocol,
                uri = location,
                startPositionSeconds = startPositionSeconds,
                mimeType = declaredMimeType,
                title = title,
                detail = detail,
                metadata = metadata,
                content = content,
                allowHlsFallback = allowHlsFallback,
            ),
        )
    }

    internal fun selectNetworkPlayback(lease: CastSessionLease, itemId: String): Boolean {
        checkOnMainThread()
        if (!isCurrent(lease) || networkSession?.lease != lease) return false
        return networkQueue.select(itemId)
    }

    internal fun removeNetworkPlaybackItem(lease: CastSessionLease, itemId: String) {
        checkOnMainThread()
        if (!isCurrent(lease) || networkSession?.lease != lease) return
        networkQueue.remove(itemId)
    }

    internal fun hasNetworkPlaybackItem(itemId: String): Boolean =
        networkQueue.state.items.any { it.id == itemId }

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
        if (isNetworkPlaybackActive(lease)) {
            networkQueue.setPlaying(rate > 0)
            networkPlayer.setRate(rate)
        }
    }

    internal fun setNetworkPlaying(lease: CastSessionLease, playing: Boolean) {
        checkOnMainThread()
        if (isNetworkPlaybackActive(lease)) {
            if (playing && networkQueue.state.awaitingNext) {
                networkQueue.state.currentItemId?.let(networkQueue::select)
            } else {
                networkQueue.setPlaying(playing)
                networkPlayer.setPlaying(playing)
            }
        }
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
            runOnMain {
                withActiveNetworkObserver {
                    if (networkQueue.state.current?.playbackId == snapshot.playbackId) {
                        networkQueue.onPlayerStatus(snapshot.playbackId, snapshot.playable, snapshot.buffering, snapshot.playWhenReady)
                        it.onPlaybackInfo(snapshot)
                    }
                }
            }
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
        networkPlayer.onMediaItemTransition = { itemId ->
            runOnMain {
                withActiveNetworkObserver { networkQueue.onPlayerTransition(itemId) }
            }
        }
        networkPlayer.onHasVideo = { hasVideo ->
            runOnMain { withActiveNetworkObserver { it.onHasVideo(hasVideo) } }
        }
        networkPlayer.onSourceReady = { itemId -> withActiveNetworkObserver { networkQueue.onSourceReady(itemId) } }
        networkPlayer.onEnded = { itemId -> withActiveNetworkObserver { networkQueue.onPlayerEnded(itemId) } }
        networkPlayer.onError = { itemId, message, retryable ->
            withActiveNetworkObserver { networkQueue.onPlayerError(itemId, message, retryable) }
        }
    }

    private fun setPlayingFromControls(playing: Boolean) {
        checkOnMainThread()
        if (drivingPlaybackInterlock.isBlocked || sessionState.value.playing == playing) return
        when (sessionState.value.protocol) {
            CastProtocol.DLNA -> dlnaAdapter.toggle()
            CastProtocol.AIRPLAY -> airPlayAdapter.toggle()
            null -> Unit
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
        networkQueue.clear()
        networkPlayer.stop()
    }

    private fun publishSessionEnd(protocol: CastProtocol, reason: CastSessionEndReason) {
        sessionEndSequence += 1
        mutableSessionEndEvent.value = CastSessionEndEvent(sessionEndSequence, protocol, reason)
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
    fun onQueueChanged(state: PlaybackQueueState) = Unit
    fun onEnded()
    fun onError(message: String)
}
