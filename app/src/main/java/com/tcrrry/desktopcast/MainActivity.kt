package com.tcrrry.desktopcast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.tcrrry.desktopcast.databinding.ActivityMainBinding
import com.tcrrry.desktopcast.safety.DrivingSafetyAlert
import com.tcrrry.desktopcast.safety.DrivingState
import com.tcrrry.desktopcast.service.CastService
import com.tcrrry.desktopcast.session.CastContentKind
import com.tcrrry.desktopcast.session.CastPhase
import com.tcrrry.desktopcast.session.CastProtocol
import com.tcrrry.desktopcast.session.CastSessionEndEvent
import com.tcrrry.desktopcast.session.CastSessionEndReason
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
    private var controlsOverlayRequested = false
    private lateinit var mediaPlayControlGroup: View
    private lateinit var mediaTimelineGroup: View
    private lateinit var mediaProgressView: View
    private lateinit var windowNavigator: CastWindowNavigator
    private var settingsVisible = false
    private var selectedSettingsSection = SettingsSection.RECEIVER
    private var lastDrivingSafetyAlert: DrivingSafetyAlert? = null
    private var drivingSafetyExitHandled = false
    private var updatingDrivingGuard = false
    private var returningToStandardForDisconnect = false
    private var rootBackgroundShowsSettings = false
    private var lastHandledSessionEndSequence = 0L
    private var sessionEndEventSource: CastService? = null

    private enum class SettingsSection {
        RECEIVER,
        SAFETY,
    }

    private val hideControls = Runnable {
        controlsVisible = false
        renderState(lastState)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as CastService.LocalBinder).service
            castService = service
            bound = true
            binding.mediaControlView.player = service.mediaControlPlayer
            if (sessionEndEventSource !== service) {
                sessionEndEventSource = service
                lastHandledSessionEndSequence = service.sessionEndEvent.value?.sequence ?: 0L
            }
            windowNavigator.completeHandoffIfRequested(intent)
            attachSurfaces(service)
            collectService(service)
            service.startCasting()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            castService = null
            binding.mediaControlView.player = null
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
                returningToStandardForDisconnect = false
                Toast.makeText(this, R.string.fullscreen_switch_failed, Toast.LENGTH_SHORT).show()
            },
        )
        configureMediaControls()
        configureAgreementQrCode()
        configureActions()
        configureSurfaces()
        configureBack()
        renderSettingsSection(selectedSettingsSection)
        renderDrivingGuard(isDrivingPlaybackGuardEnabled())
        renderDrivingState(DrivingState.UNAVAILABLE)
        renderState(lastState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        syncFullscreenControlState()
        windowNavigator.completeHandoffIfRequested(intent)
    }

    override fun onStart() {
        super.onStart()
        syncFullscreenControlState()
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
        controlsOverlayRequested = false
        controlsOverlay?.dismiss()
        binding.mediaControlView.player = null
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
        binding.closeButton.setOnClickListener { disconnectCurrentSession() }
        binding.retryButton.setOnClickListener { castService?.retry() }
        binding.settingsButton.setOnClickListener { openSettings(SettingsSection.RECEIVER) }
        binding.settingsNavigationReceiver.setOnClickListener {
            renderSettingsSection(SettingsSection.RECEIVER)
        }
        binding.settingsNavigationSafety.setOnClickListener {
            renderSettingsSection(SettingsSection.SAFETY)
        }
        binding.drivingGuardSetting.setOnClickListener {
            binding.drivingGuardSwitch.performClick()
        }
        binding.drivingGuardSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingDrivingGuard) return@setOnCheckedChangeListener
            if (!enabled) {
                // The safety guard is opt-out. Revert first, then require an
                // explicit confirmation inside the car UI before persisting.
                setDrivingGuardChecked(true)
                binding.drivingGuardWarning.isVisible = true
                return@setOnCheckedChangeListener
            }
            applyDrivingGuard(enabled = true)
        }
        binding.drivingGuardKeepButton.setOnClickListener {
            binding.drivingGuardWarning.isVisible = false
            setDrivingGuardChecked(true)
        }
        binding.drivingGuardDisableButton.setOnClickListener {
            binding.drivingGuardWarning.isVisible = false
            applyDrivingGuard(enabled = false)
        }
        binding.interactionLayer.setOnClickListener {
            if (controlsVisible) hideControlsNow() else showControls()
        }
    }

    private fun configureMediaControls() {
        mediaPlayControlGroup = binding.mediaControlView.findViewById(R.id.cast_play_control_group)
        mediaTimelineGroup = binding.mediaControlView.findViewById(R.id.cast_timeline_group)
        mediaProgressView = binding.mediaControlView.findViewById(androidx.media3.ui.R.id.exo_progress)
        binding.mediaControlView.setShowTimeoutMs(0)
        binding.mediaControlView.setAnimationEnabled(false)
        syncFullscreenControlState()
        binding.mediaControlView.setOnFullScreenModeChangedListener {
            windowNavigator.switchMode()
            showControls()
        }
        binding.mediaControlView.setOnClickListener {
            if (controlsVisible) hideControlsNow() else showControls()
        }
        (mediaProgressView as DefaultTimeBar).addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                seeking = true
                mainHandler.removeCallbacks(hideControls)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                seeking = false
                showControls()
            }
        })
    }

    /** The standard task is reused after a fullscreen handoff; refresh the icon from the real mode. */
    private fun syncFullscreenControlState() {
        binding.mediaControlView.updateIsFullscreen(isFullscreenWindow)
    }

    private fun configureAgreementQrCode() {
        binding.drivingGuardAgreementQr.setImageBitmap(
            TermsQrCodeFactory.create(BuildConfig.TERMS_URL, AGREEMENT_QR_BITMAP_SIZE_PX),
        )
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
        moveToOverlay(content, binding.mediaControls)
        moveToOverlay(content, binding.topControls)
        moveToOverlay(content, binding.safetyOverlay)
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
        controlsOverlayRequested = true
        if (overlay.isShowing) return
        binding.root.post {
            if (controlsOverlayRequested && !isFinishing && !isDestroyed &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                !overlay.isShowing
            ) {
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

    private fun dismissControlsOverlay() {
        controlsOverlayRequested = false
        controlsOverlay?.dismiss()
    }

    private fun configureBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drivingGuardWarning.isVisible -> {
                        binding.drivingGuardWarning.isVisible = false
                        setDrivingGuardChecked(true)
                    }
                    settingsVisible -> closeSettings()
                    isFullscreenWindow -> windowNavigator.switchMode()
                    else -> closeReceiver()
                }
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
                launch { service.sessionEndEvent.collect(::handleSessionEndEvent) }
                launch { service.drivingState.collect(::renderDrivingState) }
                launch { service.drivingSafetyAlert.collect(::renderDrivingSafetyAlert) }
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
        val safetyVisible = lastDrivingSafetyAlert != null
        if (settingsVisible && (active || error || state.phase == CastPhase.CONNECTING)) {
            settingsVisible = false
            binding.drivingGuardWarning.isVisible = false
        }
        renderRootBackground()
        binding.settingsContent.isVisible = settingsVisible
        binding.waitingContent.isVisible = waiting && !settingsVisible && !safetyVisible
        binding.errorContent.isVisible = error && !settingsVisible && !safetyVisible
        binding.startProgress.isVisible = !settingsVisible && !safetyVisible &&
            state.phase in setOf(CastPhase.STARTING, CastPhase.CONNECTING)
        binding.waitingTitle.text = when (state.phase) {
            CastPhase.STARTING, CastPhase.CONNECTING -> getString(R.string.connecting)
            else -> getString(R.string.waiting_title)
        }
        binding.waitingDeviceName.text = getString(R.string.waiting_device_name)
        binding.errorMessage.text = state.detail.ifBlank { getString(R.string.receiver_start_failed) }

        binding.mirrorSurface.isVisible = active && !safetyVisible &&
            state.content == CastContentKind.MIRROR
        binding.mediaSurface.isVisible = active && !safetyVisible &&
            state.content == CastContentKind.NETWORK_VIDEO
        binding.castImage.isVisible = active && !safetyVisible && state.content == CastContentKind.IMAGE
        binding.audioContent.isVisible = active && !safetyVisible && state.content == CastContentKind.AUDIO
        binding.interactionLayer.isVisible = active && !safetyVisible

        binding.audioTitle.text = state.title.ifBlank { getString(R.string.unknown_title) }
        binding.audioSubtitle.text = state.detail
        binding.audioSubtitle.isVisible = state.detail.isNotBlank()
        binding.protocolLabel.text = protocolName(state.protocol).ifBlank { getString(R.string.app_name) }
        binding.mediaTitle.text = state.title
        binding.mediaTitle.isVisible = state.title.isNotBlank()

        val showOverlay = active && !safetyVisible &&
            (controlsVisible || state.content == CastContentKind.AUDIO)
        binding.topControls.isVisible = showOverlay
        binding.protocolLabel.isVisible = showOverlay
        binding.topControls.setBackgroundColor(Color.TRANSPARENT)

        val mirrorOrImage = state.content in setOf(CastContentKind.MIRROR, CastContentKind.IMAGE)
        binding.mediaControls.isVisible = showOverlay
        mediaPlayControlGroup.isVisible = !mirrorOrImage
        mediaTimelineGroup.isVisible = !mirrorOrImage
        val airPlayAudio = state.protocol == CastProtocol.AIRPLAY &&
            state.content == CastContentKind.AUDIO
        mediaProgressView.isVisible = !mirrorOrImage && !airPlayAudio
        if (showOverlay) binding.mediaControlView.show()

        updateControlsOverlayVisibility(active)

        if (binding.mirrorSurface.isVisible) fitSurface(binding.mirrorSurface, currentMirrorAspect)
        if (binding.mediaSurface.isVisible) fitSurface(binding.mediaSurface, currentMediaAspect)
    }

    private fun handleSessionEndEvent(event: CastSessionEndEvent?) {
        event ?: return
        if (event.sequence <= lastHandledSessionEndSequence) return
        lastHandledSessionEndSequence = event.sequence
        if (isFullscreenWindow && event.reason == CastSessionEndReason.REMOTE_DISCONNECTED) {
            requestReturnToStandardForDisconnect()
        }
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
        windowNavigator.finishAllTasks()
    }

    private fun disconnectCurrentSession() {
        castService?.disconnectCurrentSession()
        if (isFullscreenWindow) {
            requestReturnToStandardForDisconnect()
        }
    }

    private fun requestReturnToStandardForDisconnect() {
        if (!isFullscreenWindow || returningToStandardForDisconnect) return
        returningToStandardForDisconnect = true
        windowNavigator.switchMode()
    }

    private fun openSettings(section: SettingsSection) {
        if (lastState.phase in ACTIVE_PHASES) return
        settingsVisible = true
        renderSettingsSection(section)
        renderState(lastState)
    }

    private fun closeSettings() {
        binding.drivingGuardWarning.isVisible = false
        settingsVisible = false
        renderState(lastState)
    }

    private fun renderSettingsSection(section: SettingsSection) {
        selectedSettingsSection = section
        val receiverSelected = section == SettingsSection.RECEIVER
        binding.settingsReceiverContent.isVisible = receiverSelected
        binding.settingsSafetyContent.isVisible = !receiverSelected
        renderSettingsNavigation(
            binding.settingsNavigationReceiver,
            binding.settingsNavigationReceiverIcon,
            binding.settingsNavigationReceiverLabel,
            receiverSelected,
        )
        renderSettingsNavigation(
            binding.settingsNavigationSafety,
            binding.settingsNavigationSafetyIcon,
            binding.settingsNavigationSafetyLabel,
            !receiverSelected,
        )
        binding.settingsContentScroll.scrollTo(0, 0)
    }

    private fun renderSettingsNavigation(
        container: View,
        icon: ImageView,
        label: android.widget.TextView,
        selected: Boolean,
    ) {
        if (selected) {
            container.setBackgroundResource(R.drawable.cast_settings_navigation_selected)
            container.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.cast_accent),
            )
            icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            label.setTextColor(Color.WHITE)
        } else {
            container.background = null
            container.backgroundTintList = null
            icon.imageTintList = null
            label.setTextColor(ContextCompat.getColor(this, R.color.cast_text_primary))
        }
        container.isSelected = selected
    }

    private fun renderDrivingGuard(enabled: Boolean) {
        setDrivingGuardChecked(enabled)
        binding.drivingGuardSummary.setText(
            if (enabled) R.string.settings_driving_guard_summary
            else R.string.settings_driving_guard_off_summary,
        )
    }

    private fun setDrivingGuardChecked(enabled: Boolean) {
        updatingDrivingGuard = true
        binding.drivingGuardSwitch.isChecked = enabled
        updatingDrivingGuard = false
    }

    private fun applyDrivingGuard(enabled: Boolean) {
        castService?.setDrivingPlaybackGuardEnabled(enabled)
            ?: getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Prefs.DRIVING_PLAYBACK_GUARD, enabled)
                .apply()
        renderDrivingGuard(enabled)
    }

    private fun isDrivingPlaybackGuardEnabled(): Boolean =
        castService?.isDrivingPlaybackGuardEnabled()
            ?: getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getBoolean(Prefs.DRIVING_PLAYBACK_GUARD, Prefs.DEF_DRIVING_PLAYBACK_GUARD)

    private fun renderDrivingState(state: DrivingState) {
        binding.drivingStateValue.setText(
            when (state) {
                DrivingState.PARKED -> R.string.settings_driving_state_parked
                DrivingState.NOT_PARKED -> R.string.settings_driving_state_not_parked
                DrivingState.UNAVAILABLE -> R.string.settings_driving_state_unavailable
            },
        )
    }

    private fun renderDrivingSafetyAlert(alert: DrivingSafetyAlert?) {
        lastDrivingSafetyAlert = alert
        binding.safetyOverlay.isVisible = alert != null
        if (alert == null) {
            drivingSafetyExitHandled = false
        } else {
            binding.safetyCountdown.text = getString(
                R.string.driving_safety_countdown,
                alert.secondsRemaining,
            )
            if (alert.exitRequired && !drivingSafetyExitHandled) {
                drivingSafetyExitHandled = true
                mainHandler.post { closeReceiver() }
            }
        }
        renderState(lastState)
    }

    private fun updateControlsOverlayVisibility(active: Boolean) {
        if (active || lastDrivingSafetyAlert != null) {
            showControlsOverlay()
        } else {
            dismissControlsOverlay()
        }
    }

    private fun renderRootBackground() {
        if (rootBackgroundShowsSettings == settingsVisible) return
        rootBackgroundShowsSettings = settingsVisible
        binding.root.background = if (settingsVisible) {
            ContextCompat.getDrawable(this, android.R.color.transparent)
        } else {
            ContextCompat.getDrawable(this, R.drawable.cast_background)
        }
    }

    private fun protocolName(protocol: CastProtocol?): String = when (protocol) {
        CastProtocol.AIRPLAY -> getString(R.string.cast_protocol_airplay)
        CastProtocol.DLNA -> getString(R.string.cast_protocol_dlna)
        null -> ""
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MS = 3_000L
        const val AGREEMENT_QR_BITMAP_SIZE_PX = 512
        val ACTIVE_PHASES = setOf(
            CastPhase.PLAYING,
            CastPhase.MIRRORING,
            CastPhase.AUDIO,
        )
    }
}
