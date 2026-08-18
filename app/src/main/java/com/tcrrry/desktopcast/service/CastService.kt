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
import com.tcrrry.desktopcast.session.CastSessionCoordinator
import com.tcrrry.desktopcast.session.CastSessionState
import com.tcrrry.desktopcast.window.CastWindowHandoff
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

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

    val sessionState: StateFlow<CastSessionState> get() = playback.sessionState
    val artwork get() = playback.artwork
    val image get() = playback.image
    val mirrorAspect get() = playback.mirrorAspect
    val mediaAspect get() = playback.mediaAspect

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
    }

    fun startCasting() = runOnMain {
        if (!started.compareAndSet(false, true)) return@runOnMain
        coordinator.beginStart()
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

    fun stopCasting() = runOnMain {
        if (!started.compareAndSet(true, false)) return@runOnMain
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
    fun dlnaSnapshot() = playback.dlnaSnapshot()

    override fun onDestroy() {
        windowHandoff.clear()
        clearWindowHandoffTimeout()
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

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Window handoff must run on the main thread"
        }
    }

    private companion object {
        const val TAG = "CastService"
        const val WINDOW_HANDOFF_TIMEOUT_MS = 3_000L
    }
}
