package com.tcrrry.desktopcast.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tcrrry.desktopcast.Prefs
import com.tcrrry.desktopcast.R
import com.tcrrry.desktopcast.network.LanAddressMonitor
import com.tcrrry.desktopcast.safety.DrivingSafetyAlert
import com.tcrrry.desktopcast.safety.DrivingSafetyPolicy
import com.tcrrry.desktopcast.safety.DrivingState
import com.tcrrry.desktopcast.safety.IcarDrivingStateMonitor
import com.tcrrry.desktopcast.session.CastSessionCoordinator
import com.tcrrry.desktopcast.session.CastSessionEndEvent
import com.tcrrry.desktopcast.session.CastSessionState
import com.tcrrry.desktopcast.window.CastWindowHandoff
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
    private val coordinator = CastSessionCoordinator()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val preferences by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    private val playback by lazy {
        CastPlaybackRouter(
            context = this,
            scope = lifecycleScope,
            coordinator = coordinator,
            audioManager = audioManager,
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
    private val drivingStateMonitor by lazy {
        IcarDrivingStateMonitor(this) { state ->
            runOnMain {
                mutableDrivingState.value = state
                evaluateDrivingSafety()
            }
        }
    }

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
        if (!windowHandoff.isActive) stopCasting()
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        // Lazy construction is intentionally triggered after LifecycleService
        // has installed its scope and before the first client binds.
        playback
        runtime
        safetyStateCollector = lifecycleScope.launch {
            playback.sessionState.collect { evaluateDrivingSafety() }
        }
    }

    fun startCasting() = runOnMain {
        if (!started.compareAndSet(false, true)) return@runOnMain
        playback.beginReceiverLifecycle()
        coordinator.beginStart()
        drivingStateMonitor.start()
        networkMonitor.start()
        handleLanAddress(networkMonitor.currentAddress())
    }

    fun beginWindowHandoff(): Long {
        checkMainThread()
        clearWindowHandoffTimeout()
        val token = windowHandoff.begin()
        windowHandoffTimeout = Runnable {
            if (!windowHandoff.timeout(token)) return@Runnable
            windowHandoffTimeout = null
            Log.w(TAG, "Window handoff timed out: $token")
            stopCasting()
            stopSelf()
        }.also { mainHandler.postDelayed(it, WINDOW_HANDOFF_TIMEOUT_MS) }
        return token
    }

    fun completeWindowHandoff(token: Long): Boolean {
        checkMainThread()
        if (!windowHandoff.complete(token)) return false
        clearWindowHandoffTimeout()
        return true
    }

    fun cancelWindowHandoff(token: Long): Boolean {
        checkMainThread()
        if (!windowHandoff.cancel(token)) return false
        clearWindowHandoffTimeout()
        return true
    }

    fun retry() = runOnMain {
        if (!started.get()) {
            startCasting()
            return@runOnMain
        }
        coordinator.beginStart()
        handleLanAddress(networkMonitor.currentAddress(), force = true)
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
    }

    fun setMirrorSurface(surface: Surface) = playback.setMirrorSurface(surface)
    fun clearMirrorSurface(surface: Surface) = playback.clearMirrorSurface(surface)
    fun setMediaSurface(holder: SurfaceHolder) = playback.setMediaSurface(holder)
    fun clearMediaSurface(holder: SurfaceHolder) = playback.clearMediaSurface(holder)
    fun togglePlayback() = playback.togglePlayback()
    fun seekTo(positionMs: Long) = playback.seekToPosition(positionMs)
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
