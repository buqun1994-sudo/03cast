package com.tcrrry.desktopcast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tcrrry.desktopcast.databinding.ActivityMainBinding
import com.tcrrry.desktopcast.service.CastService
import com.tcrrry.desktopcast.session.CastContentKind
import com.tcrrry.desktopcast.session.CastPhase
import com.tcrrry.desktopcast.session.CastProtocol
import com.tcrrry.desktopcast.session.CastSessionState
import com.tcrrry.desktopcast.window.CastWindowMode
import com.tcrrry.desktopcast.window.CastWindowNavigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

open class MainActivity : AppCompatActivity() {
    protected open val isFullscreenWindow: Boolean = false

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var castService: CastService? = null
    private var bound = false
    private var bindingRequested = false
    private var collectors: Job? = null
    private var lastState = CastSessionState()
    private var lastModeKey: Triple<CastPhase, CastProtocol?, CastContentKind>? = null
    private var controlsVisible = true
    private var seeking = false
    private var mirrorSurface: Surface? = null
    private var mediaSurfaceHolder: SurfaceHolder? = null
    private var currentMirrorAspect = 16f / 9f
    private var currentMediaAspect = 16f / 9f
    private var controlsOverlay: PopupWindow? = null
    private lateinit var windowNavigator: CastWindowNavigator

    private val hideControls = Runnable {
        controlsVisible = false
        renderState(lastState)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as CastService.LocalBinder).service
            castService = service
            bound = true
            windowNavigator.completeHandoffIfRequested(intent)
            attachSurfaces(service)
            collectService(service)
            service.startCasting()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            castService = null
            collectors?.cancel()
            collectors = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureControlsOverlay()
        windowNavigator = CastWindowNavigator(
            activity = this,
            mode = if (isFullscreenWindow) CastWindowMode.FULLSCREEN else CastWindowMode.STANDARD,
            serviceProvider = { castService },
            onFailure = {
                Toast.makeText(this, R.string.fullscreen_switch_failed, Toast.LENGTH_SHORT).show()
            },
        )
        configureActions()
        configureSurfaces()
        configureBack()
        renderState(lastState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        windowNavigator.completeHandoffIfRequested(intent)
    }

    override fun onStart() {
        super.onStart()
        showControlsOverlay()
        if (!bindingRequested) {
            bindingRequested = bindService(
                Intent(this, CastService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    override fun onStop() {
        mainHandler.removeCallbacks(hideControls)
        controlsOverlay?.dismiss()
        collectors?.cancel()
        collectors = null
        if (bindingRequested) {
            if (!windowNavigator.consumeOutgoingHandoff()) castService?.stopCasting()
            unbindService(connection)
            bindingRequested = false
            bound = false
            castService = null
        }
        super.onStop()
    }

    private fun configureActions() {
        binding.closeButton.setOnClickListener { closeReceiver() }
        binding.retryButton.setOnClickListener { castService?.retry() }
        binding.interactionLayer.setOnClickListener {
            if (controlsVisible) hideControlsNow() else showControls()
        }
        binding.playPauseButton.setOnClickListener {
            castService?.togglePlayback()
            showControls()
        }
        binding.fullscreenButton.setOnClickListener {
            windowNavigator.switchMode()
            showControls()
        }
        binding.playbackSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = lastState.durationMs * progress / SEEK_MAX
                binding.positionText.text = formatTime(target)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                seeking = true
                mainHandler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (lastState.durationMs <= 0L) {
                    seeking = false
                    showControls()
                    return
                }
                val target = lastState.durationMs * seekBar.progress / SEEK_MAX
                castService?.seekTo(target)
                seeking = false
                showControls()
            }
        })
    }

    /**
     * A topmost SurfaceView is required by the Android 9 HWC path, so ordinary
     * siblings in this Activity cannot draw over the video. Keep one source of
     * truth for the controls, but host them in an attached application window.
     */
    private fun configureControlsOverlay() {
        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
        }
        moveToOverlay(content, binding.interactionLayer)
        moveToOverlay(content, binding.topControls)
        moveToOverlay(content, binding.mediaControls)
        controlsOverlay = PopupWindow(
            content,
            1,
            1,
            false,
        ).apply {
            isTouchable = true
            isFocusable = false
            isOutsideTouchable = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun moveToOverlay(content: ViewGroup, view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        content.addView(view)
    }

    private fun showControlsOverlay() {
        val overlay = controlsOverlay ?: return
        if (overlay.isShowing) return
        binding.root.post {
            if (!isFinishing && !isDestroyed && !overlay.isShowing) {
                overlay.width = binding.root.width
                overlay.height = binding.root.height
                overlay.showAsDropDown(
                    binding.root,
                    0,
                    -binding.root.height,
                    Gravity.START,
                )
            }
        }
    }

    private fun configureBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreenWindow) windowNavigator.switchMode() else closeReceiver()
            }
        })
    }

    private fun configureSurfaces() {
        binding.mirrorSurface.setZOrderOnTop(true)
        binding.mirrorSurface.holder.setFormat(PixelFormat.OPAQUE)
        binding.mirrorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mirrorSurface = holder.surface
                castService?.setMirrorSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                castService?.clearMirrorSurface(holder.surface)
                if (mirrorSurface === holder.surface) mirrorSurface = null
            }
        })
        binding.mediaSurface.setZOrderOnTop(true)
        binding.mediaSurface.holder.setFormat(PixelFormat.OPAQUE)
        binding.mediaSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mediaSurfaceHolder = holder
                castService?.setMediaSurface(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                castService?.clearMediaSurface(holder)
                if (mediaSurfaceHolder === holder) mediaSurfaceHolder = null
            }
        })
    }

    private fun attachSurfaces(service: CastService) {
        mirrorSurface?.takeIf { it.isValid }?.let(service::setMirrorSurface)
        mediaSurfaceHolder
            ?.takeIf { it.surface.isValid }
            ?.let(service::setMediaSurface)
    }

    private fun collectService(service: CastService) {
        collectors?.cancel()
        collectors = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { service.sessionState.collect(::renderState) }
                launch {
                    service.artwork.collect { bitmap ->
                        if (bitmap == null) {
                            binding.audioArtwork.setImageResource(R.drawable.ic_cast)
                        } else {
                            binding.audioArtwork.setImageBitmap(bitmap)
                        }
                    }
                }
                launch { service.image.collect(binding.castImage::setImageBitmap) }
                launch {
                    service.mirrorAspect.collect { aspect ->
                        currentMirrorAspect = aspect
                        fitSurface(binding.mirrorSurface, aspect)
                    }
                }
                launch {
                    service.mediaAspect.collect { aspect ->
                        currentMediaAspect = aspect
                        fitSurface(binding.mediaSurface, aspect)
                    }
                }
            }
        }
    }

    private fun renderState(state: CastSessionState) {
        val modeKey = Triple(state.phase, state.protocol, state.content)
        if (modeKey != lastModeKey) {
            lastModeKey = modeKey
            controlsVisible = true
            scheduleControlsIfNeeded(state)
        }
        lastState = state

        val active = state.phase in ACTIVE_PHASES
        val error = state.phase == CastPhase.RECOVERABLE_ERROR
        val waiting = !active && !error
        binding.waitingContent.isVisible = waiting
        binding.errorContent.isVisible = error
        binding.startProgress.isVisible = state.phase in setOf(CastPhase.STARTING, CastPhase.CONNECTING)
        binding.waitingTitle.text = when (state.phase) {
            CastPhase.STARTING, CastPhase.CONNECTING -> getString(R.string.connecting)
            else -> getString(R.string.waiting_title)
        }
        binding.waitingDeviceName.text = protocolName(state.protocol).ifBlank {
            getString(R.string.waiting_device_name)
        }
        binding.errorMessage.text = state.detail.ifBlank { getString(R.string.receiver_start_failed) }

        binding.mirrorSurface.isVisible = active && state.content == CastContentKind.MIRROR
        binding.mediaSurface.isVisible = active && state.content == CastContentKind.NETWORK_VIDEO
        binding.castImage.isVisible = active && state.content == CastContentKind.IMAGE
        binding.audioContent.isVisible = active && state.content == CastContentKind.AUDIO
        binding.interactionLayer.isVisible = active

        binding.audioTitle.text = state.title.ifBlank { getString(R.string.unknown_title) }
        binding.audioSubtitle.text = state.detail
        binding.audioSubtitle.isVisible = state.detail.isNotBlank()
        binding.protocolLabel.text = protocolName(state.protocol).ifBlank { getString(R.string.app_name) }
        binding.mediaTitle.text = state.title
        binding.mediaTitle.isVisible = state.title.isNotBlank()

        val showOverlay = !active || controlsVisible || state.content == CastContentKind.AUDIO
        binding.topControls.isVisible = showOverlay
        binding.protocolLabel.isVisible = active
        binding.topControls.setBackgroundColor(
            if (active) ContextCompat.getColor(this, R.color.cast_control_scrim_light) else Color.TRANSPARENT,
        )

        val mirrorOrImage = state.content in setOf(CastContentKind.MIRROR, CastContentKind.IMAGE)
        binding.mediaControls.isVisible = active && showOverlay
        binding.mediaControls.gravity = if (mirrorOrImage) Gravity.CENTER else Gravity.CENTER_VERTICAL
        binding.playPauseButton.isVisible = !mirrorOrImage
        binding.positionText.isVisible = !mirrorOrImage
        val airPlayAudio = state.protocol == CastProtocol.AIRPLAY &&
            state.content == CastContentKind.AUDIO
        binding.playbackSeek.isVisible = !mirrorOrImage && !airPlayAudio
        binding.durationText.isVisible = !mirrorOrImage

        binding.playPauseButton.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription = getString(if (state.playing) R.string.pause else R.string.play)
        binding.fullscreenButton.setImageResource(
            if (isFullscreenWindow) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
        )
        binding.fullscreenButton.contentDescription = getString(
            if (isFullscreenWindow) R.string.exit_fullscreen else R.string.enter_fullscreen,
        )
        binding.playbackSeek.isEnabled = state.canSeek && !airPlayAudio
        if (!seeking) {
            binding.playbackSeek.progress = if (state.durationMs > 0) {
                ((state.positionMs.coerceIn(0, state.durationMs) * SEEK_MAX) / state.durationMs).toInt()
            } else {
                0
            }
            binding.positionText.text = formatTime(state.positionMs)
        }
        binding.durationText.text = if (airPlayAudio) {
            " / ${formatTime(state.durationMs)}"
        } else {
            formatTime(state.durationMs)
        }

        if (binding.mirrorSurface.isVisible) fitSurface(binding.mirrorSurface, currentMirrorAspect)
        if (binding.mediaSurface.isVisible) fitSurface(binding.mediaSurface, currentMediaAspect)
    }

    private fun showControls() {
        controlsVisible = true
        renderState(lastState)
        scheduleControlsIfNeeded(lastState)
    }

    private fun hideControlsNow() {
        mainHandler.removeCallbacks(hideControls)
        if (lastState.content != CastContentKind.AUDIO) {
            controlsVisible = false
            renderState(lastState)
        }
    }

    private fun scheduleControlsIfNeeded(state: CastSessionState) {
        mainHandler.removeCallbacks(hideControls)
        if (state.phase in ACTIVE_PHASES && state.content != CastContentKind.AUDIO && !seeking) {
            mainHandler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun fitSurface(view: View, aspect: Float) {
        if (aspect <= 0f) return
        binding.root.post {
            val containerWidth = binding.root.width
            val containerHeight = binding.root.height
            if (containerWidth <= 0 || containerHeight <= 0) return@post
            val availableHeight = containerHeight
            val containerAspect = containerWidth.toFloat() / availableHeight
            val width: Int
            val height: Int
            if (aspect >= containerAspect) {
                width = containerWidth
                height = (containerWidth / aspect).toInt().coerceAtLeast(1)
            } else {
                height = availableHeight
                width = (availableHeight * aspect).toInt().coerceAtLeast(1)
            }
            view.layoutParams = FrameLayout.LayoutParams(
                width,
                height,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = (availableHeight - height) / 2
            }
        }
    }

    private fun closeReceiver() {
        windowNavigator.cancelPendingHandoff()
        castService?.stopCasting()
        stopService(Intent(this, CastService::class.java))
        finishAndRemoveTask()
    }

    private fun protocolName(protocol: CastProtocol?): String = when (protocol) {
        CastProtocol.AIRPLAY -> getString(R.string.cast_protocol_airplay)
        CastProtocol.DLNA -> getString(R.string.cast_protocol_dlna)
        null -> ""
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MS = 3_000L
        const val SEEK_MAX = 1000L
        val ACTIVE_PHASES = setOf(
            CastPhase.PLAYING,
            CastPhase.MIRRORING,
            CastPhase.AUDIO,
        )
    }
}
