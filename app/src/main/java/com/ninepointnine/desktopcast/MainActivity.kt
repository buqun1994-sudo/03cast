package com.ninepointnine.desktopcast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.ninepointnine.desktopcast.commercial.CommercialAccessUpdate
import com.ninepointnine.desktopcast.commercial.CommercialAction
import com.ninepointnine.desktopcast.commercial.CommercialController
import com.ninepointnine.desktopcast.commercial.CommercialRuntimeFactory
import com.ninepointnine.desktopcast.commercial.CommercialSettingsRenderer
import com.ninepointnine.desktopcast.commercial.CommercialStateMachine
import com.ninepointnine.desktopcast.commercial.CommercialUiState
import com.ninepointnine.desktopcast.commercial.CommercialVariantUi
import com.ninepointnine.desktopcast.commercial.CommercialViewActions
import com.ninepointnine.desktopcast.commercial.CheckoutState
import com.ninepointnine.desktopcast.commercial.EntitlementSnapshot
import com.ninepointnine.desktopcast.commercial.EntitlementState
import com.ninepointnine.desktopcast.commercial.RecoveryState
import com.ninepointnine.desktopcast.databinding.ActivityMainBinding
import com.ninepointnine.desktopcast.safety.DrivingSafetyAlert
import com.ninepointnine.desktopcast.safety.DrivingState
import com.ninepointnine.desktopcast.service.CastService
import com.ninepointnine.desktopcast.session.CastContentKind
import com.ninepointnine.desktopcast.session.CastPhase
import com.ninepointnine.desktopcast.session.CastProtocol
import com.ninepointnine.desktopcast.session.CastSessionState
import com.ninepointnine.desktopcast.window.CastWindowMode
import com.ninepointnine.desktopcast.window.CastWindowNavigator
import com.ninepointnine.desktopcast.window.isWindowHandoffOutputReady
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    private var mirrorSurfaceHolder: SurfaceHolder? = null
    private var mediaSurfaceHolder: SurfaceHolder? = null
    private var currentMirrorAspect = 16f / 9f
    private var currentMediaAspect = 16f / 9f
    private var controlsOverlay: PopupWindow? = null
    private var controlsOverlayRequested = false
    private lateinit var mediaPlayControlGroup: View
    private lateinit var mediaTimelineGroup: View
    private lateinit var mediaProgressView: View
    private lateinit var mediaFullscreenControl: View
    private var settingsCommercialContent: View? = null
    private var settingsAboutContent: View? = null
    private lateinit var windowNavigator: CastWindowNavigator
    private var settingsVisible = false
    private var selectedSettingsSection = SettingsSection.RECEIVER
    private var lastDrivingSafetyAlert: DrivingSafetyAlert? = null
    private var drivingSafetyExitHandled = false
    private var updatingDrivingGuard = false
    private var commercialRenderer: CommercialSettingsRenderer? = null
    private var commercialController: CommercialController? = null
    private lateinit var commercialWaitingRenderer: CastCommercialWaitingRenderer
    private var commercialState = CommercialUiState()
    private var drivingAgreementQrConfigured = false
    private var removeCommercialSnapshotListener: (() -> Unit)? = null
    private var themePalette = IcarThemeColorPalette.resolve(null, false)
    private var renderedNightMode = false
    private var inflatedNightMode = false
    private var themeRecreatePending = false
    private var themeObserverRegistered = false
    private var audioArtworkIsFallback = true
    private var rootBackgroundMode: RootBackgroundMode? = null
    private var serviceRetainedForConfiguration = false

    private enum class RootBackgroundMode {
        DEFAULT,
        SETTINGS,
        PLAYBACK_BLACK,
    }

    private enum class SettingsSection {
        RECEIVER,
        SAFETY,
        COMMERCIAL,
        ABOUT,
    }

    private val hideControls = Runnable {
        controlsVisible = false
        renderState(lastState)
    }

    private val themeColorObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshThemePalette()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as CastService.LocalBinder).service
            castService = service
            bound = true
            binding.mediaControlView.player = service.mediaControlPlayer
            applyMediaControlColors()
            attachSurfaces(service)
            completeHandoffWhenOutputReady()
            collectService(service)
            observeCommercialSnapshots()
            service.startCasting()
            renderState(service.sessionState.value)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            castService = null
            binding.mediaControlView.player = null
            collectors?.cancel()
            collectors = null
            stopObservingCommercialSnapshots()
            renderState(lastState)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderedNightMode = isNightMode()
        inflatedNightMode = renderedNightMode
        settingsVisible = savedInstanceState?.getBoolean(STATE_SETTINGS_VISIBLE) == true
        serviceRetainedForConfiguration =
            savedInstanceState?.getBoolean(STATE_SERVICE_RETAINED) == true
        selectedSettingsSection = savedInstanceState?.getString(STATE_SETTINGS_SECTION)
            ?.let { value -> SettingsSection.entries.firstOrNull { it.name == value } }
            ?: SettingsSection.RECEIVER
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureControlsOverlay()
        windowNavigator = CastWindowNavigator(
            activity = this,
            mode = if (isFullscreenWindow) CastWindowMode.FULLSCREEN else CastWindowMode.STANDARD,
            serviceProvider = { castService },
            onFailure = {
                syncFullscreenControlState()
                renderState(lastState)
            },
        )
        configureMediaControls()
        configureCommercialWaitingUi()
        configureActions()
        configureSurfaces()
        if (settingsVisible) {
            ensureCommercialSettingsUi()
            if (selectedSettingsSection == SettingsSection.ABOUT) ensureAboutUi()
        }
        binding.root.addOnLayoutChangeListener { _, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                refitVisibleSurfaces()
            }
        }
        configureBack()
        renderSettingsSection(selectedSettingsSection)
        renderDrivingGuard(isDrivingPlaybackGuardEnabled())
        renderDrivingState(DrivingState.UNAVAILABLE)
        commercialWaitingRenderer.render(commercialState)
        refreshThemePalette()
        renderState(lastState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (synchronizeThemeConfiguration(newConfig)) return
        refreshThemePalette()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        syncFullscreenControlState()
        castService?.let(::attachSurfaces)
        completeHandoffWhenOutputReady()
        commercialController?.let { CommercialVariantUi.handleDebugIntent(this, intent, it) }
    }

    override fun onStart() {
        super.onStart()
        registerThemeColorObserver()
        if (synchronizeThemeConfiguration()) return
        refreshThemePalette()
        syncFullscreenControlState()
        // Settings live inside this Activity rather than in a separate
        // window. The standard window's start boundary is therefore the one
        // UI entitlement check; page navigation below stays side-effect free.
        if (!isFullscreenWindow) {
            ensureCommercialController().start()
        }
        if (!bindingRequested) {
            bindingRequested = bindService(
                Intent(this, CastService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (synchronizeThemeConfiguration()) return
        refreshThemePalette()
    }

    override fun onStop() {
        unregisterThemeColorObserver()
        mainHandler.removeCallbacks(hideControls)
        controlsOverlayRequested = false
        controlsOverlay?.dismiss()
        binding.mediaControlView.player = null
        collectors?.cancel()
        collectors = null
        stopObservingCommercialSnapshots()
        if (bindingRequested) {
            val outgoingHandoff = windowNavigator.consumeOutgoingHandoff()
            if (!isChangingConfigurations && !outgoingHandoff) {
                castService?.stopCasting()
                if (serviceRetainedForConfiguration) {
                    stopService(Intent(this, CastService::class.java))
                    serviceRetainedForConfiguration = false
                }
            }
            unbindService(connection)
            bindingRequested = false
            bound = false
            castService = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterThemeColorObserver()
        stopObservingCommercialSnapshots()
        commercialController?.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SETTINGS_VISIBLE, settingsVisible)
        outState.putString(STATE_SETTINGS_SECTION, selectedSettingsSection.name)
        outState.putBoolean(STATE_SERVICE_RETAINED, serviceRetainedForConfiguration)
        super.onSaveInstanceState(outState)
    }

    private fun configureCommercialWaitingUi() {
        commercialWaitingRenderer = CastCommercialWaitingRenderer(
            root = binding.root,
            actions = CastCommercialWaitingActions(
                onBuyPro = ::openCommercialEntitlement,
                onViewEntitlement = ::openCommercialEntitlement,
                onRetry = { ensureCommercialController().reloadEntitlement() },
            ),
        )
    }

    private fun ensureCommercialController(): CommercialController {
        commercialController?.let { return it }
        return CommercialController(
            gateway = CommercialRuntimeFactory.gateway(this),
            coordinator = CommercialRuntimeFactory.entitlementCoordinator(this),
            onStateChanged = ::renderCommercialState,
            onAccessMayHaveChanged = ::refreshCommercialAccess,
        ).also { controller ->
            commercialController = controller
            CommercialVariantUi.handleDebugIntent(this, intent, controller)
        }
    }

    private fun ensureCommercialSettingsUi() {
        if (commercialRenderer != null) return
        binding.root.findViewById<ViewStub>(R.id.commercial_summary_stub).inflate()
        settingsCommercialContent =
            binding.root.findViewById<ViewStub>(R.id.settings_commercial_stub).inflate()
        commercialRenderer = CommercialSettingsRenderer(
            root = binding.root,
            actions = CommercialViewActions(
                onOpenEntitlement = ::openCommercialEntitlement,
                onCheckout = ::openCommercialPurchase,
                onRetryEntitlement = { ensureCommercialController().reloadEntitlement() },
                onDiscountCodeChanged = { value ->
                    ensureCommercialController().changeDiscountCode(value)
                },
                onApplyDiscount = { ensureCommercialController().applyDiscountCode() },
                onPaymentMethodChanged = {
                    ensureCommercialController().selectPaymentMethod(it)
                },
                onPay = { ensureCommercialController().createPayment() },
                onRestore = { ensureCommercialController().restorePurchase() },
            ),
        ).also { renderer ->
            renderer.updateAccent(
                accentColor = themePalette.accentColor,
                accentTextColor = themePalette.accentTextColor,
                accentSurfaceColor = themePalette.accentSurfaceColor,
            )
            renderer.render(commercialState)
            renderer.setSummaryVisibleForSection(selectedSettingsSection != SettingsSection.COMMERCIAL)
        }
    }

    private fun ensureAboutUi() {
        if (settingsAboutContent != null) return
        val content = binding.root.findViewById<ViewStub>(R.id.settings_about_stub).inflate()
        settingsAboutContent = content
        content.findViewById<ImageView>(R.id.about_terms_qr).apply {
            setImageBitmap(TermsQrCodeFactory.create(BuildConfig.TERMS_URL, ABOUT_QR_BITMAP_SIZE_PX))
            contentDescription = getString(R.string.accessibility_about_terms_qr)
        }
    }

    private fun observeCommercialSnapshots() {
        if (removeCommercialSnapshotListener != null) return
        removeCommercialSnapshotListener = CommercialRuntimeFactory
            .entitlementCoordinator(applicationContext)
            .addListener { snapshot ->
                mainHandler.post {
                    if (!isDestroyed && commercialController == null) {
                        renderCommercialSnapshot(snapshot)
                    }
                }
            }
    }

    private fun stopObservingCommercialSnapshots() {
        removeCommercialSnapshotListener?.invoke()
        removeCommercialSnapshotListener = null
    }

    private fun renderCommercialSnapshot(snapshot: EntitlementSnapshot) {
        val nextState = CommercialStateMachine(commercialState).dispatch(
            CommercialAction.QueryCompleted(snapshot),
        )
        renderCommercialState(nextState)
    }


    private fun configureActions() {
        binding.closeButton.setOnClickListener { disconnectCurrentSession() }
        binding.waitingFullscreenExitButton.setOnClickListener {
            if (isFullscreenWindow) requestWindowSwitch()
        }
        binding.retryButton.setOnClickListener { castService?.retry() }
        binding.settingsButton.setOnClickListener { openSettings(SettingsSection.RECEIVER) }
        binding.settingsNavigationReceiver.setOnClickListener {
            renderSettingsSection(SettingsSection.RECEIVER)
        }
        binding.settingsNavigationSafety.setOnClickListener {
            renderSettingsSection(SettingsSection.SAFETY)
        }
        binding.settingsNavigationEntitlement.setOnClickListener {
            openCommercialEntitlement()
        }
        binding.settingsNavigationAbout.setOnClickListener {
            openSettings(SettingsSection.ABOUT)
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
                ensureDrivingAgreementQrCode()
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
        mediaFullscreenControl =
            binding.mediaControlView.findViewById(androidx.media3.ui.R.id.exo_fullscreen)
        binding.mediaControlView.setShowTimeoutMs(0)
        binding.mediaControlView.setAnimationEnabled(false)
        applyMediaControlColors()
        syncFullscreenControlState()
        binding.mediaControlView.setOnFullScreenModeChangedListener {
            requestWindowSwitch()
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

    private fun ensureDrivingAgreementQrCode() {
        if (drivingAgreementQrConfigured) return
        drivingAgreementQrConfigured = true
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
                    settingsVisible && selectedSettingsSection == SettingsSection.COMMERCIAL &&
                        commercialRenderer?.consumeBack() == true -> Unit
                    settingsVisible -> closeSettings()
                    isFullscreenWindow -> requestWindowSwitch()
                    else -> closeReceiver()
                }
            }
        })
    }

    private fun configureSurfaces() {
        binding.mirrorSurface.setZOrderOnTop(true)
        // Keep the window opaque and let the codec choose its implementation
        // buffer format. Forcing RGBX makes the Qualcomm decoder negotiate an
        // RGB producer buffer instead of its native YUV/HWC path.
        binding.mirrorSurface.holder.setFormat(PixelFormat.OPAQUE)
        binding.mirrorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mirrorSurfaceHolder = holder
                val frame = holder.surfaceFrame
                castService?.setMirrorSurface(holder, frame.width(), frame.height())
                completeHandoffWhenOutputReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // A fullscreen handoff can resize the same native window
                // without recreating it. Re-advertise the holder so a new
                // Activity surface can replace the old codec output target.
                if (holder.surface.isValid) castService?.setMirrorSurface(holder, width, height)
                completeHandoffWhenOutputReady()
                refitVisibleSurfaces()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                castService?.clearMirrorSurface(holder)
                if (mirrorSurfaceHolder === holder) mirrorSurfaceHolder = null
            }
        })
        binding.mediaSurface.setZOrderOnTop(true)
        binding.mediaSurface.holder.setFormat(PixelFormat.OPAQUE)
        binding.mediaSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mediaSurfaceHolder = holder
                castService?.setMediaSurface(holder)
                completeHandoffWhenOutputReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                completeHandoffWhenOutputReady()
                refitVisibleSurfaces()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                castService?.clearMediaSurface(holder)
                if (mediaSurfaceHolder === holder) mediaSurfaceHolder = null
            }
        })
    }

    private fun attachSurfaces(service: CastService) {
        mirrorSurfaceHolder
            ?.takeIf { it.surface.isValid }
            ?.let { holder ->
                val frame = holder.surfaceFrame
                service.setMirrorSurface(holder, frame.width(), frame.height())
            }
        mediaSurfaceHolder
            ?.takeIf { it.surface.isValid }
            ?.let(service::setMediaSurface)
    }

    private fun completeHandoffWhenOutputReady() {
        val service = castService ?: return
        val content = service.sessionState.value.content
        val targetHolder = when (content) {
            CastContentKind.MIRROR -> mirrorSurfaceHolder
            CastContentKind.NETWORK_VIDEO -> mediaSurfaceHolder
            CastContentKind.NONE,
            CastContentKind.AUDIO,
            CastContentKind.IMAGE,
            -> null
        }
        val outputReady = isWindowHandoffOutputReady(
            content = content,
            mirrorSurfaceReady = mirrorSurfaceHolder?.surface?.isValid == true,
            mediaSurfaceReady = mediaSurfaceHolder?.surface?.isValid == true,
        )
        if (outputReady && windowNavigator.completeHandoffIfRequested(intent, targetHolder)) {
            renderState(service.sessionState.value)
        }
    }

    private fun collectService(service: CastService) {
        collectors?.cancel()
        collectors = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { service.sessionState.collect(::renderState) }
                launch { service.drivingState.collect(::renderDrivingState) }
                launch { service.drivingSafetyAlert.collect(::renderDrivingSafetyAlert) }
                launch {
                    service.artwork.collect { bitmap ->
                        if (bitmap == null) {
                            audioArtworkIsFallback = true
                            binding.audioArtwork.imageTintList =
                                ColorStateList.valueOf(themePalette.accentSurfaceColor)
                            binding.audioArtwork.setImageResource(R.drawable.ic_cast)
                        } else {
                            audioArtworkIsFallback = false
                            binding.audioArtwork.imageTintList = null
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
        applyMediaControlColors()
        applyPlaybackTextColors()
        if (themeRecreatePending && !isVisualPlayback(state) &&
            !isChangingConfigurations &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            themeRecreatePending = false
            recreateForThemeChange()
            return
        }

        val active = state.phase in ACTIVE_PHASES
        val error = state.phase == CastPhase.RECOVERABLE_ERROR
        val waiting = !active && !error
        val safetyVisible = lastDrivingSafetyAlert != null
        if (settingsVisible && (active || error || state.phase == CastPhase.CONNECTING)) {
            settingsVisible = false
            binding.drivingGuardWarning.isVisible = false
        }
        renderRootBackground(state)
        applySystemBarColors()
        binding.settingsContent.isVisible = settingsVisible
        binding.waitingContent.isVisible = waiting && !settingsVisible && !safetyVisible
        binding.errorContent.isVisible = error && !settingsVisible && !safetyVisible
        binding.waitingFullscreenExitButton.isVisible = isFullscreenWindow &&
            state.phase == CastPhase.WAITING && !settingsVisible && !safetyVisible
        val canSwitchWindow = bound && !windowNavigator.isTransitionPending
        binding.waitingFullscreenExitButton.isEnabled = canSwitchWindow
        mediaFullscreenControl.isEnabled = canSwitchWindow
        binding.startProgress.visibility = if (!settingsVisible && !safetyVisible &&
            state.phase in setOf(CastPhase.STARTING, CastPhase.CONNECTING)
        ) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }
        renderWaitingTitle(state)
        binding.waitingDeviceName.text = getString(R.string.waiting_device_name)
        commercialWaitingRenderer.render(commercialState)
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

        refitVisibleSurfaces()
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

    private fun refitVisibleSurfaces() {
        if (binding.mirrorSurface.isVisible) fitSurface(binding.mirrorSurface, currentMirrorAspect)
        if (binding.mediaSurface.isVisible) fitSurface(binding.mediaSurface, currentMediaAspect)
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
            requestReturnToStandardWindow()
        }
    }

    private fun requestReturnToStandardWindow() {
        if (!isFullscreenWindow) return
        requestWindowSwitch()
    }

    private fun requestWindowSwitch() {
        windowNavigator.switchMode()
        renderState(lastState)
    }

    private fun openSettings(section: SettingsSection) {
        if (lastState.phase in ACTIVE_PHASES) return
        settingsVisible = true
        ensureCommercialSettingsUi()
        // The standard Activity already performed its lifecycle check in
        // onStart. Switching between embedded settings pages must only alter
        // presentation and never start another entitlement operation.
        ensureCommercialController()
        if (section == SettingsSection.ABOUT) ensureAboutUi()
        renderSettingsSection(section)
        renderState(lastState)
    }

    private fun openCommercialEntitlement() {
        ensureCommercialController().showEntitlementPage()
        openSettings(SettingsSection.COMMERCIAL)
    }

    private fun openCommercialPurchase() {
        val controller = ensureCommercialController()
        openSettings(SettingsSection.COMMERCIAL)
        controller.showCheckout()
    }

    private fun refreshCommercialAccess(update: CommercialAccessUpdate) {
        if (update == CommercialAccessUpdate.RECHECK || update == CommercialAccessUpdate.REVOKED) {
            castService?.refreshCommercialAccess()
        }
    }

    private fun renderCommercialState(state: CommercialUiState) {
        commercialState = state
        commercialRenderer?.render(state)
        commercialWaitingRenderer.render(state)
        renderWaitingTitle(lastState)
        if (state.checkout is CheckoutState.Paid || state.recovery is RecoveryState.Success) {
            // This is an automatic navigation caused by the completed
            // operation. The operation already persisted the new credential;
            // page presentation must not start a second lifecycle check.
            openSettings(SettingsSection.COMMERCIAL)
        }
    }

    private fun renderWaitingTitle(state: CastSessionState) {
        val expiredWaiting = state.phase == CastPhase.WAITING &&
            commercialState.entitlement == EntitlementState.Expired
        binding.waitingTitle.setText(
            when {
                expiredWaiting -> R.string.cast_commercial_waiting_unavailable
                state.phase == CastPhase.STARTING || state.phase == CastPhase.CONNECTING ->
                    R.string.connecting
                else -> R.string.waiting_title
            },
        )
        binding.waitingTitle.setTextColor(
            ContextCompat.getColor(
                this,
                if (expiredWaiting) R.color.cast_error else R.color.cast_text_secondary,
            ),
        )
    }

    private fun closeSettings() {
        binding.drivingGuardWarning.isVisible = false
        settingsVisible = false
        renderState(lastState)
    }

    private fun renderSettingsSection(section: SettingsSection) {
        selectedSettingsSection = section
        val receiverSelected = section == SettingsSection.RECEIVER
        val safetySelected = section == SettingsSection.SAFETY
        val commercialSelected = section == SettingsSection.COMMERCIAL
        val aboutSelected = section == SettingsSection.ABOUT
        binding.settingsReceiverContent.isVisible = receiverSelected
        binding.settingsSafetyContent.isVisible = safetySelected
        settingsCommercialContent?.isVisible = commercialSelected
        settingsAboutContent?.isVisible = aboutSelected
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
            safetySelected,
        )
        renderSettingsNavigation(
            binding.settingsNavigationEntitlement,
            binding.settingsNavigationEntitlementIcon,
            binding.settingsNavigationEntitlementLabel,
            commercialSelected,
        )
        renderSettingsNavigation(
            binding.settingsNavigationAbout,
            binding.settingsNavigationAboutIcon,
            binding.settingsNavigationAboutLabel,
            aboutSelected,
        )
        commercialRenderer?.setSummaryVisibleForSection(!commercialSelected)
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
                themePalette.accentColor,
            )
            icon.imageTintList = ColorStateList.valueOf(themePalette.accentTextColor)
            label.setTextColor(themePalette.accentTextColor)
        } else {
            container.background = null
            container.backgroundTintList = null
            icon.imageTintList = if (icon.id == R.id.settings_navigation_receiver_icon) {
                ColorStateList.valueOf(themePalette.accentSurfaceColor)
            } else {
                null
            }
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

    private fun renderRootBackground(state: CastSessionState) {
        val mode = when {
            settingsVisible -> RootBackgroundMode.SETTINGS
            isVisualPlayback(state) -> RootBackgroundMode.PLAYBACK_BLACK
            else -> RootBackgroundMode.DEFAULT
        }
        if (rootBackgroundMode == mode) return
        rootBackgroundMode = mode
        binding.root.background = when (mode) {
            RootBackgroundMode.SETTINGS -> ContextCompat.getDrawable(
                this,
                android.R.color.transparent,
            )
            RootBackgroundMode.PLAYBACK_BLACK -> android.graphics.drawable.ColorDrawable(Color.BLACK)
            RootBackgroundMode.DEFAULT -> ContextCompat.getDrawable(this, R.drawable.cast_background)
        }
    }

    private fun recreateForThemeChange() {
        themeRecreatePending = false
        castService
            ?.takeIf { lastState.phase != CastPhase.STOPPED }
            ?.let { service ->
                service.retainAcrossActivityRecreation()
                startService(Intent(this, CastService::class.java))
                serviceRetainedForConfiguration = true
            }
        recreate()
    }

    private fun refreshThemePalette() {
        if (!::binding.isInitialized || !::commercialWaitingRenderer.isInitialized) return
        themePalette = IcarThemeColorPalette.resolve(
            themeKey = runCatching {
                Settings.Global.getInt(contentResolver, IcarThemeColorPalette.GLOBAL_THEME_KEY)
            }.getOrNull(),
            nightMode = isNightMode(),
        )
        applySystemBarColors()
        applyMediaControlColors()
        if (audioArtworkIsFallback) {
            binding.audioArtwork.imageTintList =
                ColorStateList.valueOf(themePalette.accentSurfaceColor)
        }
        binding.drivingGuardSwitch.updateThemeColors(
            accentColor = themePalette.accentColor,
            offTrackColor = ContextCompat.getColor(this, R.color.cast_settings_switch_track_off),
            thumbColor = ContextCompat.getColor(this, R.color.cast_settings_switch_thumb),
        )
        commercialWaitingRenderer.updateAccent(
            accentColor = themePalette.accentColor,
            accentSurfaceColor = themePalette.accentSurfaceColor,
        )
        commercialRenderer?.updateAccent(
            accentColor = themePalette.accentColor,
            accentTextColor = themePalette.accentTextColor,
            accentSurfaceColor = themePalette.accentSurfaceColor,
        )
        if (::binding.isInitialized) {
            binding.startProgress.indeterminateTintList =
                ColorStateList.valueOf(themePalette.accentSurfaceColor)
            binding.settingsButton.setTextColor(themePalette.accentSurfaceColor)
            binding.waitingIcon.imageTintList =
                ColorStateList.valueOf(themePalette.accentSurfaceColor)
            binding.retryButton.backgroundTintList = ColorStateList.valueOf(themePalette.accentColor)
            binding.retryButton.setTextColor(themePalette.accentTextColor)
            binding.drivingGuardKeepButton.backgroundTintList =
                ColorStateList.valueOf(themePalette.accentColor)
            binding.drivingGuardKeepButton.setTextColor(themePalette.accentTextColor)
            applyPlaybackTextColors()
            renderSettingsNavigation(
                binding.settingsNavigationReceiver,
                binding.settingsNavigationReceiverIcon,
                binding.settingsNavigationReceiverLabel,
                selectedSettingsSection == SettingsSection.RECEIVER,
            )
            renderSettingsNavigation(
                binding.settingsNavigationSafety,
                binding.settingsNavigationSafetyIcon,
                binding.settingsNavigationSafetyLabel,
                selectedSettingsSection == SettingsSection.SAFETY,
            )
            renderSettingsNavigation(
                binding.settingsNavigationEntitlement,
                binding.settingsNavigationEntitlementIcon,
                binding.settingsNavigationEntitlementLabel,
                selectedSettingsSection == SettingsSection.COMMERCIAL,
            )
            renderSettingsNavigation(
                binding.settingsNavigationAbout,
                binding.settingsNavigationAboutIcon,
                binding.settingsNavigationAboutLabel,
                selectedSettingsSection == SettingsSection.ABOUT,
            )
            renderWaitingTitle(lastState)
            renderRootBackground(lastState)
        }
    }

    /**
     * uiMode is handled manually because recreating a visual SurfaceView would
     * interrupt the Android 9 decoder. This also covers a change made while
     * the Activity was stopped, where onConfigurationChanged may not be
     * delivered to the current instance.
     */
    private fun synchronizeThemeConfiguration(
        configuration: Configuration = resources.configuration,
    ): Boolean {
        val nextNightMode = isNightMode(configuration)
        if (nextNightMode == renderedNightMode) return false
        renderedNightMode = nextNightMode
        val preserveVisualOutput = isVisualPlayback(lastState) && lastDrivingSafetyAlert == null
        if (!preserveVisualOutput) {
            recreateForThemeChange()
            return true
        }
        themeRecreatePending = nextNightMode != inflatedNightMode
        return false
    }

    private fun applyMediaControlColors() {
        if (!::mediaProgressView.isInitialized) return
        val visualPlayback = isVisualPlayback(lastState)
        val controlForeground = if (visualPlayback) {
            Color.WHITE
        } else {
            ContextCompat.getColor(this, R.color.cast_text_primary)
        }
        (mediaProgressView as? DefaultTimeBar)?.apply {
            val progressAccent = if (visualPlayback) {
                themePalette.accentColor
            } else {
                themePalette.accentSurfaceColor
            }
            setPlayedColor(progressAccent)
            setScrubberColor(progressAccent)
            setBufferedColor(
                if (visualPlayback) 0x99FFFFFF.toInt()
                else ContextCompat.getColor(this@MainActivity, R.color.cast_control_buffered_audio),
            )
            setUnplayedColor(
                if (visualPlayback) 0x66FFFFFF
                else ContextCompat.getColor(this@MainActivity, R.color.cast_control_unplayed_audio),
            )
        }
        listOf(
            binding.mediaControlView.findViewById<ImageView>(androidx.media3.ui.R.id.exo_play_pause),
            binding.mediaControlView.findViewById<ImageView>(androidx.media3.ui.R.id.exo_fullscreen),
        ).forEach { control ->
            control?.let {
                it.imageTintList = ColorStateList.valueOf(controlForeground)
                val background = it.background?.mutate()
                if (background is RippleDrawable) {
                    background.setColor(ColorStateList.valueOf(
                        if (visualPlayback) Color.WHITE else themePalette.accentSurfaceColor,
                    ))
                    it.background = background
                }
            }
        }
        listOf(
            binding.mediaControlView.findViewById<android.widget.TextView>(androidx.media3.ui.R.id.exo_position),
            binding.mediaControlView.findViewById<android.widget.TextView>(androidx.media3.ui.R.id.exo_duration),
        ).forEach { time ->
            time?.setTextColor(controlForeground)
        }
    }

    private fun applyPlaybackTextColors() {
        if (!::binding.isInitialized) return
        val visualPlayback = isVisualPlayback(lastState)
        val foreground = if (visualPlayback) {
            Color.WHITE
        } else {
            ContextCompat.getColor(this, R.color.cast_text_primary)
        }
        val title = if (visualPlayback) {
            ContextCompat.getColor(this, R.color.cast_control_title)
        } else {
            ContextCompat.getColor(this, R.color.cast_text_secondary)
        }
        binding.protocolLabel.setTextColor(foreground)
        binding.mediaTitle.setTextColor(title)
        binding.closeButton.imageTintList = ColorStateList.valueOf(foreground)
        binding.waitingFullscreenExitButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.cast_text_primary),
        )
    }

    private fun applySystemBarColors() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            val lightSystemBars = !isVisualPlayback(lastState) &&
                lastDrivingSafetyAlert == null &&
                !isNightMode()
            if (lightSystemBars) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                if (lightSystemBars) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun registerThemeColorObserver() {
        if (themeObserverRegistered) return
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(IcarThemeColorPalette.GLOBAL_THEME_KEY),
                false,
                themeColorObserver,
            )
            themeObserverRegistered = true
        }
    }

    private fun unregisterThemeColorObserver() {
        if (!themeObserverRegistered) return
        runCatching { contentResolver.unregisterContentObserver(themeColorObserver) }
        themeObserverRegistered = false
    }

    private fun isNightMode(configuration: Configuration = resources.configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isVisualPlayback(state: CastSessionState): Boolean =
        state.phase in setOf(CastPhase.PLAYING, CastPhase.MIRRORING) &&
            state.content in setOf(CastContentKind.NETWORK_VIDEO, CastContentKind.MIRROR, CastContentKind.IMAGE)

    private fun protocolName(protocol: CastProtocol?): String = when (protocol) {
        CastProtocol.AIRPLAY -> getString(R.string.cast_protocol_airplay)
        CastProtocol.DLNA -> getString(R.string.cast_protocol_dlna)
        null -> ""
    }

    private companion object {
        const val CONTROLS_TIMEOUT_MS = 3_000L
        const val AGREEMENT_QR_BITMAP_SIZE_PX = 512
        const val ABOUT_QR_BITMAP_SIZE_PX = 512
        const val STATE_SETTINGS_VISIBLE = "settings_visible"
        const val STATE_SETTINGS_SECTION = "settings_section"
        const val STATE_SERVICE_RETAINED = "service_retained_for_configuration"
        val ACTIVE_PHASES = setOf(
            CastPhase.PLAYING,
            CastPhase.MIRRORING,
            CastPhase.AUDIO,
        )
    }
}
