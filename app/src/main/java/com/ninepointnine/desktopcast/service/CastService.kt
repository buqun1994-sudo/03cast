package com.ninepointnine.desktopcast.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.ninepointnine.desktopcast.Prefs
import com.ninepointnine.desktopcast.R
import com.ninepointnine.desktopcast.commercial.CommercialAccessDecision
import com.ninepointnine.desktopcast.network.LanAddressMonitor
import com.ninepointnine.desktopcast.safety.DrivingSafetyAlert
import com.ninepointnine.desktopcast.safety.DrivingSafetyPolicy
import com.ninepointnine.desktopcast.safety.DrivingState
import com.ninepointnine.desktopcast.safety.IcarDrivingStateMonitor
import com.ninepointnine.desktopcast.session.CastSessionCoordinator
import com.ninepointnine.desktopcast.session.CastSessionEndEvent
import com.ninepointnine.desktopcast.session.CastSessionState
import com.ninepointnine.desktopcast.window.CastWindowHandoff
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android lifecycle and dependency assembly boundary for the receiver.
 * Protocol details live in CastReceiverRuntime; playback details live in
 * CastPlaybackRouter. Keeping this class small prevents lifecycle callbacks
 * from becoming a second application state machine.
 */
class CastService : LifecycleService() {

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val windowHandoff = CastWindowHandoff()
    private var windowHandoffTimeout: Runnable? = null
    private var safetyCountdown: Runnable? = null
    private var safetySecondsRemaining = 0
    private var safetyStateCollector: Job? = null
    private var commercialAccessBoundaryReceiverRegistered = false
    private var retainAcrossActivityRecreation = false
    private val coordinator = CastSessionCoordinator()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val preferences by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    private val commercialAccess by lazy {
        CastCommercialAccessAdapter(
            context = this,
            scope = lifecycleScope,
            mainHandler = mainHandler,
            onAccessDenied = ::onCommercialAccessDenied,
        )
    }
    private val playback by lazy {
        CastPlaybackRouter(
            context = this,
            scope = lifecycleScope,
            coordinator = coordinator,
            audioManager = audioManager,
            commercialAccess = commercialAccess,
        )
    }
    private val runtime by lazy {
        CastReceiverRuntime(
            context = this,
            audioManager = audioManager,
            preferences = preferences,
            playback = playback,
            isStarted = started::get,
            onFailure = ::onRuntimeFailure,
        )
    }
    private val networkMonitor by lazy {
        LanAddressMonitor(this) { address ->
            runOnMain { handleLanAddress(address) }
        }
    }
    private val mutableDrivingState = MutableStateFlow(DrivingState.UNAVAILABLE)
    private val mutableDrivingSafetyAlert = MutableStateFlow<DrivingSafetyAlert?>(null)
    private val commercialAccessBoundaryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON ||
                intent?.action == Intent.ACTION_TIME_CHANGED
            ) {
                commercialAccess.revalidate()
            }
        }
    }
    private val drivingStateMonitor by lazy {
        IcarDrivingStateMonitor(this) { state ->
            runOnMain {
                mutableDrivingState.value = state
                evaluateDrivingSafety()
            }
        }
    }

    internal val playbackQueueState get() = playback.queueState
    val sessionState: StateFlow<CastSessionState> get() = playback.sessionState
    val artwork get() = playback.artwork
    val image get() = playback.image
    val mirrorAspect get() = playback.mirrorAspect
    val mediaAspect get() = playback.mediaAspect
    val mediaControlPlayer get() = playback.mediaControlPlayer
    val sessionEndEvent: StateFlow<CastSessionEndEvent?> get() = playback.sessionEndEvent
    val drivingState: StateFlow<DrivingState> = mutableDrivingState.asStateFlow()
    val drivingSafetyAlert: StateFlow<DrivingSafetyAlert?> = mutableDrivingSafetyAlert.asStateFlow()

    inner class LocalBinder : Binder() {
        val service: CastService get() = this@CastService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (retainAcrossActivityRecreation) {
            retainAcrossActivityRecreation = false
        } else if (!windowHandoff.isActive) {
            stopCasting()
        }
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Lazy construction is intentionally triggered after LifecycleService
        // has installed its scope and before the first client binds.
        playback
        runtime
        commercialAccess
        safetyStateCollector = lifecycleScope.launch {
            playback.sessionState.collect { evaluateDrivingSafety() }
        }
    }

    fun startCasting() = runOnMain {
        if (!started.compareAndSet(false, true)) {
            // A new Activity binding is a service lifecycle boundary even
            // when the receiver itself is retained across configuration
            // changes. Recheck entitlement without restarting the receiver.
            commercialAccess.recheck()
            return@runOnMain
        }
        registerCommercialAccessBoundaryReceiver()
        commercialAccess.start()
        playback.beginReceiverLifecycle()
        coordinator.beginStart()
        drivingStateMonitor.start()
        networkMonitor.start()
        handleLanAddress(networkMonitor.currentAddress())
    }

    /** Keeps the receiver alive while an Activity rebuilds for a theme change. */
    fun retainAcrossActivityRecreation() {
        checkMainThread()
        retainAcrossActivityRecreation = true
    }

    fun beginWindowHandoff(sourceRetirement: (() -> Unit)? = null): Long? {
        checkMainThread()
        if (!playback.prepareWindowHandoff()) {
            Log.w(TAG, "Window handoff refused: media output did not detach cleanly")
            return null
        }
        clearWindowHandoffTimeout()
        val token = windowHandoff.begin(sourceRetirement ?: {})
        windowHandoffTimeout = Runnable {
            if (!windowHandoff.timeout(token)) return@Runnable
            windowHandoffTimeout = null
            Log.w(TAG, "Window handoff timed out: $token")
            playback.restoreWindowHandoffOutput()
            stopCasting()
            stopSelf()
        }.also { mainHandler.postDelayed(it, WINDOW_HANDOFF_TIMEOUT_MS) }
        return token
    }

    fun completeWindowHandoff(token: Long, targetHolder: SurfaceHolder? = null): Boolean {
        checkMainThread()
        if (!windowHandoff.isActive) return false
        if (!playback.confirmWindowHandoffOutput(sessionState.value.content, targetHolder)) {
            Log.w(TAG, "Window handoff target output not acknowledged: $token")
            return false
        }
        if (!windowHandoff.complete(token, playback::commitWindowHandoffOutput)) return false
        clearWindowHandoffTimeout()
        return true
    }

    fun cancelWindowHandoff(token: Long): Boolean {
        checkMainThread()
        if (!windowHandoff.cancel(token)) return false
        playback.restoreWindowHandoffOutput()
        clearWindowHandoffTimeout()
        return true
    }

    fun retry() = runOnMain {
        if (!started.get()) {
            startCasting()
            return@runOnMain
        }
        commercialAccess.recheck()
        coordinator.beginStart()
        handleLanAddress(networkMonitor.currentAddress(), force = true)
    }

    fun refreshCommercialAccess() = runOnMain {
        if (started.get()) commercialAccess.recheck()
    }

    fun stopCasting() = stopCasting(clearSafetyAlert = true)

    private fun stopCasting(clearSafetyAlert: Boolean) = runOnMain {
        if (!started.compareAndSet(true, false)) {
            if (clearSafetyAlert) cancelDrivingSafetyCountdown()
            return@runOnMain
        }
        cancelDrivingSafetyCountdown(clearSafetyAlert)
        drivingStateMonitor.stop()
        mutableDrivingState.value = DrivingState.UNAVAILABLE
        coordinator.stop()
        networkMonitor.stop()
        runtime.stop()
        commercialAccess.clear()
        unregisterCommercialAccessBoundaryReceiver()
    }

    fun setMirrorSurface(holder: SurfaceHolder, bufferWidth: Int = 0, bufferHeight: Int = 0) =
        playback.setMirrorSurface(holder, bufferWidth, bufferHeight)
    fun clearMirrorSurface(holder: SurfaceHolder) = playback.clearMirrorSurface(holder)
    fun setMediaSurface(holder: SurfaceHolder) = playback.setMediaSurface(holder)
    fun clearMediaSurface(holder: SurfaceHolder) = playback.clearMediaSurface(holder)
    fun togglePlayback() = playback.togglePlayback()
    fun seekTo(positionMs: Long) = playback.seekToPosition(positionMs)
    fun nextVideo() = playback.nextVideo()
    fun previousVideo() = playback.previousVideo()
    fun disconnectCurrentSession() = playback.disconnectCurrentSession()
    fun isDrivingPlaybackGuardEnabled(): Boolean =
        preferences.getBoolean(Prefs.DRIVING_PLAYBACK_GUARD, Prefs.DEF_DRIVING_PLAYBACK_GUARD)

    fun setDrivingPlaybackGuardEnabled(enabled: Boolean) = runOnMain {
        if (mutableDrivingSafetyAlert.value != null) return@runOnMain
        preferences.edit().putBoolean(Prefs.DRIVING_PLAYBACK_GUARD, enabled).apply()
        if (enabled) evaluateDrivingSafety() else cancelDrivingSafetyCountdown()
    }
    fun dlnaSnapshot() = playback.dlnaSnapshot()

    override fun onDestroy() {
        windowHandoff.clear()
        clearWindowHandoffTimeout()
        safetyStateCollector?.cancel()
        safetyStateCollector = null
        cancelDrivingSafetyCountdown()
        drivingStateMonitor.stop()
        mutableDrivingState.value = DrivingState.UNAVAILABLE
        started.set(false)
        coordinator.stop()
        networkMonitor.stop()
        runtime.stop()
        commercialAccess.clear()
        unregisterCommercialAccessBoundaryReceiver()
        playback.release()
        super.onDestroy()
    }

    private fun handleLanAddress(address: Inet4Address?, force: Boolean = false) {
        if (!started.get()) return
        if (address == null) {
            runtime.stop()
            coordinator.recoverableError(null, getString(R.string.network_unavailable))
            return
        }
        if (!force && address == runtime.address && runtime.isReady) return

        coordinator.beginStart()
        try {
            runtime.start(address)
            coordinator.ready()
        } catch (error: Throwable) {
            onRuntimeFailure(error)
        }
    }

    private fun onRuntimeFailure(error: Throwable) {
        runOnMain {
            if (!started.get()) return@runOnMain
            Log.e(TAG, "Receiver runtime failed", error)
            runtime.stop()
            coordinator.recoverableError(null, getString(R.string.receiver_start_failed))
        }
    }

    private fun onCommercialAccessDenied(access: CommercialAccessDecision.Denied) {
        runOnMain {
            if (!started.get()) return@runOnMain
            Log.i(TAG, "Commercial media access ended: ${access.reason}")
            playback.blockForCommercialAccess()
        }
    }

    private fun registerCommercialAccessBoundaryReceiver() {
        if (commercialAccessBoundaryReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                this,
                commercialAccessBoundaryReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            commercialAccessBoundaryReceiverRegistered = true
        }.onFailure { error ->
            Log.w(TAG, "Commercial access boundary receiver unavailable", error)
        }
    }

    private fun unregisterCommercialAccessBoundaryReceiver() {
        if (!commercialAccessBoundaryReceiverRegistered) return
        runCatching { unregisterReceiver(commercialAccessBoundaryReceiver) }
        commercialAccessBoundaryReceiverRegistered = false
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun clearWindowHandoffTimeout() {
        windowHandoffTimeout?.let(mainHandler::removeCallbacks)
        windowHandoffTimeout = null
    }

    private fun evaluateDrivingSafety() {
        if (!started.get() || mutableDrivingSafetyAlert.value != null) return
        if (
            DrivingSafetyPolicy.shouldBeginExit(
                protectionEnabled = isDrivingPlaybackGuardEnabled(),
                drivingState = mutableDrivingState.value,
                castPhase = playback.sessionState.value.phase,
            )
        ) {
            beginDrivingSafetyExit()
        }
    }

    private fun beginDrivingSafetyExit() {
        if (mutableDrivingSafetyAlert.value != null) return
        playback.blockForDrivingSafety()
        safetySecondsRemaining = DRIVING_SAFETY_EXIT_SECONDS
        mutableDrivingSafetyAlert.value = DrivingSafetyAlert(safetySecondsRemaining)
        safetyCountdown = object : Runnable {
            override fun run() {
                safetySecondsRemaining -= 1
                if (safetySecondsRemaining <= 0) {
                    mutableDrivingSafetyAlert.value = DrivingSafetyAlert(0, exitRequired = true)
                    safetyCountdown = null
                    stopCasting(clearSafetyAlert = false)
                } else {
                    mutableDrivingSafetyAlert.value = DrivingSafetyAlert(safetySecondsRemaining)
                    mainHandler.postDelayed(this, ONE_SECOND_MS)
                }
            }
        }.also { mainHandler.postDelayed(it, ONE_SECOND_MS) }
    }

    private fun cancelDrivingSafetyCountdown(clearAlert: Boolean = true) {
        safetyCountdown?.let(mainHandler::removeCallbacks)
        safetyCountdown = null
        safetySecondsRemaining = 0
        if (clearAlert) mutableDrivingSafetyAlert.value = null
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Window handoff must run on the main thread"
        }
    }

    private companion object {
        const val TAG = "CastService"
        const val WINDOW_HANDOFF_TIMEOUT_MS = 3_000L
        const val DRIVING_SAFETY_EXIT_SECONDS = 5
        const val ONE_SECOND_MS = 1_000L
    }
}
