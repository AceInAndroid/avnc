/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.ActivityVncBinding
import com.gaurav.avnc.databinding.NoVideoOverlayBinding
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.gaurav.avnc.util.DeviceAuthPrompt
import com.gaurav.avnc.util.EdgeToEdgeHelper
import com.gaurav.avnc.util.SamsungDex
import com.gaurav.avnc.util.debugCheck
import com.gaurav.avnc.util.enableChildLayoutTransitions
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.util.showKeyboard
import com.gaurav.avnc.util.hideKeyboard
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.viewmodel.VncViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

/**
 * This activity handles the connection to a VNC server.
 */
class VncActivity : AppCompatActivity() {

    @Parcelize
    private data class SavedState(
            val frameX: Float,
            val frameY: Float,
            val zoomScale1: Float,
            val zoomScale2: Float,
            val reconnectDelay: Int?) : Parcelable

    companion object {
        private const val TAG = "VncActivity"
        private const val SAVED_STATE_KEY = "com.gaurav.avnc.vnc_activity.saved_state"
    }

    val viewModel by viewModels<VncViewModel>()
    lateinit var binding: ActivityVncBinding
    private val inputHandler = InputHandler()
    val virtualKeys by lazy { VirtualKeys(this, inputHandler) }
    val computerKeyboard by lazy { ComputerKeyboard(this, inputHandler) }
    val inputPanels by lazy { ViewerInputPanels(this) }
    internal val selectedInputTab: InputTab? get() = if (computerKeyboard.isVisible) InputTab.Keyboard else inputPanels.selectedTab
    val floatingTouchpad by lazy { FloatingTouchpad(this, inputHandler) }
    private var restoreVirtualKeys = false
    private var restoreComputerKeyboard = false
    private var restoreInputTab: InputTab? = null
    private val keyboardBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideInputPanels()
    }
    val toolbar by lazy { Toolbar(this) }
    val clipboardControls by lazy { ClipboardControls(this) }
    // Activity-result launchers must be registered before the Activity starts.
    val fileTransferControls = FileTransferControls(this)
    private val serverUnlockPrompt = DeviceAuthPrompt(this)
    private val layoutManager by lazy { LayoutManager(this) }
    private var oldState: SavedState? = null
    private var hasActivityRestarted = false
    private var hasConnectedSuccessfully = false
    private var wasConnectedWhenActivityStopped = false
    private var wasKeyboardVisibleWhenActivityStopped = false
    private var onStartTime = 0L
    private lateinit var connectionEntry: ConnectionEntry
    private var reconnectJob: Job? = null
    private var autoRetryCancelled = false
    private var retryDeadline: Long? = null
    private var currentRetryDelay: Int? = null

    /**********************************************************************************************
     * Activity Lifecycle
     *********************************************************************************************/

    override fun onCreate(savedInstanceState: Bundle?) {
        DeviceAuthPrompt.applyFingerprintDialogFix(supportFragmentManager)

        super.onCreate(savedInstanceState)
        hasActivityRestarted = savedInstanceState != null
        oldState = (savedInstanceState ?: intent.extras)?.let {
            BundleCompat.getParcelable(it, SAVED_STATE_KEY, SavedState::class.java)
        }
        retryDeadline = savedInstanceState?.takeIf { it.containsKey("retry_deadline") }?.getLong("retry_deadline")
        currentRetryDelay = savedInstanceState?.takeIf { it.containsKey("retry_delay") }?.getInt("retry_delay")
        startup(savedInstanceState)

        //Main UI
        binding = EdgeToEdgeHelper.setDataBindingContentView(this, R.layout.activity_vnc)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        binding.frameView.initialize(viewModel)
        binding.inputView.initialize(viewModel, inputHandler)
        viewModel.frameViewRef = WeakReference(binding.frameView)

        setupLayout()
        setupInputControls(savedInstanceState)
        setupNoVideoOverlay()

        autoRetryCancelled = savedInstanceState?.getBoolean("auto_retry_cancelled") ?: false
        connectionEntry = ConnectionEntry(this, { reconnect() }, ::stopAutoReconnect)

        //Observers
        viewModel.loginInfoRequest.observe(this) { showLoginDialog() }
        viewModel.confirmationRequest.observe(this) { showConfirmationDialog() }
        viewModel.state.observe(this) { onClientStateChanged(it) }
        viewModel.profileLive.observe(this) { onProfileUpdated() }
        viewModel.capturePointer.observe(this) { updatePointerCapture(it) }
        viewModel.viewportWillChange.observe(this) { releaseInputControls() }

    }

    override fun onStart() {
        super.onStart()
        binding.frameView.onResume()
        onStartTime = SystemClock.uptimeMillis()
        if (viewModel.connected) {
            val old = viewModel.remoteInputConfig
            releaseInputControls()
            viewModel.refreshRemoteInputConfig()
            inputHandler.onRemoteConfigChanged()
            if (old != viewModel.remoteInputConfig) { computerKeyboard.onRemoteInputChanged(); inputPanels.refresh() }
        }

        // Refresh framebuffer on activity restart:
        // - It forces read/write on the socket. This allows us to verify the socket, which might have
        //   been closed by the server while app process was frozen in background
        // - It also attempts to fix some unusual cases of old updates requests being lost while AVNC
        //   was frozen by the system
        if (viewModel.pref.viewer.pauseUpdatesInBackground && !viewModel.videoDisabled)
            viewModel.setFrameBufferUpdatesPaused(false)
        else if (wasConnectedWhenActivityStopped)
            viewModel.refreshFrameBuffer()
    }

    override fun onResume() {
        super.onResume()
        clipboardControls.onResume()
    }

    override fun onPause() {
        clipboardControls.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        clipboardControls.close()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        releaseInputControls()
        binding.frameView.onPause()
        if (viewModel.pref.viewer.pauseUpdatesInBackground)
            viewModel.setFrameBufferUpdatesPaused(true)
        wasConnectedWhenActivityStopped = viewModel.connected
        wasKeyboardVisibleWhenActivityStopped = isKeyboardVisible(binding.inputView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        viewModel.hasWindowFocus.value = hasFocus
        if (::binding.isInitialized) clipboardControls.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreKeyboard()
        } else if (::binding.isInitialized) {
            releaseInputControls()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(SAVED_STATE_KEY, prepareSavedState(currentRetryDelay ?: oldState?.reconnectDelay))
        retryDeadline?.let { outState.putLong("retry_deadline", it) }
        currentRetryDelay?.let { outState.putInt("retry_delay", it) }
        outState.putBoolean("computer_keyboard_visible", computerKeyboard.isVisible)
        outState.putBoolean("auto_retry_cancelled", autoRetryCancelled)
        outState.putString("selected_input_tab", selectedInputTab?.name)
        viewModel.profileLive.value?.let { outState.putProfile(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        releaseInputControls()
        super.onConfigurationChanged(newConfig)
        computerKeyboard.onConfigurationChanged()
        toolbar.close()
        binding.viewerRoot.post { updateControlLayout(); toolbar.refresh() }
    }

    private fun prepareSavedState(reconnectDelay: Int? = null): SavedState {
        val fs = viewModel.frameState
        return SavedState(
                frameX = fs.frameX,
                frameY = fs.frameY,
                zoomScale1 = fs.zoomScale1,
                zoomScale2 = fs.zoomScale2,
                reconnectDelay = reconnectDelay
        )
    }

    private fun restoreKeyboard() {
        // API 31+ automatically restores the keyboard when app comes back from background
        // For older devices we manually track keyboard visibility
        if (wasKeyboardVisibleWhenActivityStopped && Build.VERSION.SDK_INT <= 30) {
            wasKeyboardVisibleWhenActivityStopped = false
            showKeyboard()
        }
    }


    /**********************************************************************************************
     * Session Control
     *********************************************************************************************/

    private fun startup(savedState: Bundle?) {
        if (viewModel.profileLive.value != null) // todo refactor
            return

        runCatching {
            val startupArg = parseStartupArg(intent, savedState)

            val isSavedServer = startupArg is StartupArg.ProfileId ||
                                (startupArg is StartupArg.Profile && startupArg.profile.isSaved())

            val isUnlockNeeded = isSavedServer && viewModel.pref.server.lockSavedServer
                                 && serverUnlockPrompt.canLaunch()

            if (isUnlockNeeded) {
                serverUnlockPrompt.init(
                        onSuccess = { viewModel.awaitingUser.value = false; startSession(startupArg) },
                        onFail = { viewModel.awaitingUser.value = false; finish() })

                viewModel.awaitingUser.value = true
                if (!serverUnlockPrompt.hasLaunched())
                    serverUnlockPrompt.launch(getString(R.string.title_unlock_dialog))
            } else {
                startSession(startupArg)
            }
        }.onFailure {
            handleStartupFailure(it)
        }
    }

    private fun startSession(startupArg: StartupArg) {
        lifecycleScope.launch {
            runCatching {
                startSession(startupArg, viewModel)
            }.onFailure {
                if (it is CancellationException) throw it
                handleStartupFailure(it)
            }
        }
    }

    private fun handleStartupFailure(cause: Throwable) {
        var msg = cause.message ?: "An error occurred during startup"
        when (cause) {
            is MissingStartupArgException -> {
                debugCheck(false) // Crash debug builds
                msg = "Error: Missing Server Info"
            }
            is InvalidProfileIdException -> {
                msg = "Error: Invalid Server ID"
            }
        }

        Log.e(TAG, msg, cause)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun reconnect(savedState: SavedState = prepareSavedState()) {
        //We simply create a new activity to force creation of new ViewModel
        //which effectively restarts the connection.
        if (!isFinishing) {
            startActivity(createVncIntent(this, viewModel.profile).also {
                it.putExtra(SAVED_STATE_KEY, savedState)
            })

            if (savedState.reconnectDelay == 0) {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            finish()
        }
    }

    private var autoReconnecting = false
    private fun autoReconnect() {
        if (autoReconnecting || autoRetryCancelled || isFinishing) return
        val reconnectDelay = currentRetryDelay ?: calculateAutoReconnectDelay() ?: return
        autoReconnecting = true
        currentRetryDelay = reconnectDelay
        val latestDeadline = SystemClock.elapsedRealtime() + reconnectDelay * 1000L
        val expiresAt = retryDeadline?.coerceAtMost(latestDeadline) ?: latestDeadline
        retryDeadline = expiresAt
        reconnectJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (!autoRetryCancelled && !isFinishing) {
                    val millis = (expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    val remaining = ((millis + 999) / 1000).toInt()
                    connectionEntry.setCountdown(remaining)
                    val progress = if (reconnectDelay <= 1) 100 else
                        (100 - millis * 100 / ((reconnectDelay - 1) * 1000L)).toInt().coerceIn(0, 100)
                    binding.autoReconnectProgress.setProgressCompat(progress, true)
                    if (remaining == 0) {
                        reconnect(prepareSavedState(reconnectDelay))
                        break
                    }
                    delay(minOf(millis, 250L))
                }
            }
        }
    }

    private fun stopAutoReconnect() {
        autoRetryCancelled = true
        autoReconnecting = false
        reconnectJob?.cancel()
        reconnectJob = null
        retryDeadline = null
        if (this::connectionEntry.isInitialized) connectionEntry.setCountdown(null)
    }

    private fun calculateAutoReconnectDelay(): Int? {
        // If disconnected when coming back from background, try to reconnect immediately
        if (wasConnectedWhenActivityStopped && (SystemClock.uptimeMillis() - onStartTime) in 0..2000) {
            Log.i(TAG, "Disconnected during activity restart, reconnecting ...")
            return 0
        }

        if (!viewModel.pref.server.autoReconnect && !viewModel.profile.enableWol)
            return null // Auto-reconnect is disabled

        // Automatic reconnect happens every 5 seconds.
        // But if session had reached Connected state, first attempt happens
        // after 1 second, second attempt after 3 seconds, and then every 5 seconds.
        val previousDelay = oldState?.reconnectDelay
        return when {
            hasConnectedSuccessfully -> 1   // Connection lost, try to reconnect early
            previousDelay == null -> 5
            previousDelay == 0 -> 1
            else -> (previousDelay + 2).coerceAtMost(5)
        }
    }


    /**********************************************************************************************
     * Session lifecycle
     *********************************************************************************************/

    private fun onProfileUpdated() {
        toolbar.initialize()
    }

    private fun onClientStateChanged(newState: VncViewModel.State) {
        val isConnected = newState == VncViewModel.State.Connected

        //todo: Cleanup these too
        layoutManager.onConnectionStateChanged()
        toolbar.onStateChange(isConnected)
        clipboardControls.onStateChanged()
        updateStatusContainerVisibility(isConnected)

        when (newState) {
            VncViewModel.State.Connected -> onSessionConnected()
            VncViewModel.State.Disconnected -> onSessionDisconnected()
            else -> return
        }
    }

    private fun onSessionConnected() {
        hasConnectedSuccessfully = true

        binding.frameView.isVisible = true
        binding.frameView.keepScreenOn = viewModel.pref.viewer.keepScreenOn
        inputHandler.onSessionConnected(this)
        virtualKeys.onConnected()
        if (selectedInputTab != null) virtualKeys.hide()
        floatingTouchpad.onConnected()
        if (restoreComputerKeyboard) {
            restoreComputerKeyboard = false
            showKeyboard()
        }
        restoreInputTab?.let { restoreInputTab = null; selectInputTab(it) }
        SamsungDex.setMetaKeyCapture(this, true)

        showViewerHelp()
        if (hasActivityRestarted)
            restoreFrameState()
        else
            incrementUseCount()
    }

    private fun onSessionDisconnected() {
        binding.frameView.isVisible = false
        binding.frameView.keepScreenOn = false
        hideInputPanels()
        computerKeyboard.onDisconnected()
        floatingTouchpad.onDisconnected()
        virtualKeys.releaseMetaKeys()
        inputHandler.onSessionDisconnected()
        SamsungDex.setMetaKeyCapture(this, false)
        autoReconnect()
    }

    private fun restoreFrameState() {
        oldState?.let {
            viewModel.setZoom(it.zoomScale1, it.zoomScale2)
            viewModel.panFrame(it.frameX, it.frameY)
        }
    }

    private fun incrementUseCount() {
        viewModel.profile.useCount += 1
        viewModel.saveProfile()
    }


    /************************************************************************************
     * Interface
     ************************************************************************************/
    private fun setupLayout() {
        layoutManager.initialize()

        viewModel.preferredScreenOrientation.observe(this) { requestedOrientation = it }

        if (Build.VERSION.SDK_INT >= 28 && viewModel.pref.viewer.drawBehindCutout) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
            viewModel.inPiPMode.value = true
        }
    }

    private fun setupNoVideoOverlay() {
        viewModel.activeViewMode.observe(this) {
            if (viewModel.videoDisabled) {
                inflateNoVideoOverlay()
                binding.noVideoOverlayStub.root?.isVisible = true
            } else {
                binding.noVideoOverlayStub.root?.isVisible = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun inflateNoVideoOverlay() {
        if (binding.noVideoOverlayStub.isInflated)
            return

        enableChildLayoutTransitions(binding.frameContainer)

        binding.noVideoOverlayStub.viewStub?.inflate()
        val stubBinding = binding.noVideoOverlayStub.binding as NoVideoOverlayBinding
        val rootView = stubBinding.overlayRoot
        val tapIndicator = stubBinding.tapIndicator

        enableChildLayoutTransitions(stubBinding.overlayRoot)

        // Tap indicator should appear immediately, but disappear with animation
        rootView.layoutTransition?.setDuration(LayoutTransition.APPEARING, 0)

        rootView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> tapIndicator.apply {
                    isVisible = true
                    translationX = event.x - (width / 2)
                    translationY = event.y - (height / 2)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> tapIndicator.isVisible = false
            }
            inputHandler.onTouchEvent(event)
        }
    }

    private fun showLoginDialog() {
        if (supportFragmentManager.findFragmentByTag("LoginDialog") == null)
            LoginFragment().show(supportFragmentManager, "LoginDialog")
    }

    private fun showConfirmationDialog() {
        if (supportFragmentManager.findFragmentByTag("ConfirmationDialog") == null)
            ConfirmationDialog().show(supportFragmentManager, "ConfirmationDialog")
    }

    fun showKeyboard() {
        if (!viewModel.connected || viewModel.inPiPMode.value == true ||
            viewModel.activeViewMode.value == ServerProfile.VIEW_MODE_NO_INPUT) return
        inputPanels.hide()
        if (viewModel.pref.input.useSystemKeyboard) {
            hideComputerKeyboard()
            binding.inputView.requestFocus()
            showKeyboard(binding.inputView)
            virtualKeys.onKeyboardOpen()
        } else {
            hideKeyboard(binding.inputView)
            binding.inputView.requestFocus()
            if (!computerKeyboard.isVisible) {
                restoreVirtualKeys = virtualKeys.container?.isVisible == true
                virtualKeys.releaseMetaKeys()
                virtualKeys.hide()
                inputHandler.cancelInput()
                computerKeyboard.show()
            }
        }
        keyboardBack.isEnabled = selectedInputTab != null
        updateControlLayout()
        toolbar.refresh()
    }

    fun hideComputerKeyboard() = computerKeyboard.hide()

    internal fun selectInputTab(tab: InputTab) {
        if (!viewModel.connected || viewModel.inPiPMode.value == true ||
            viewModel.activeViewMode.value == ServerProfile.VIEW_MODE_NO_INPUT) return
        toolbar.close()
        if (selectedInputTab == tab) return
        releaseInputControls()
        if (tab == InputTab.Keyboard) showKeyboard() else {
            computerKeyboard.hide()
            hideKeyboard(binding.inputView)
            virtualKeys.hide()
            inputPanels.show(tab)
        }
        keyboardBack.isEnabled = selectedInputTab != null
        updateControlLayout()
        toolbar.refresh()
    }

    fun hideInputPanels() {
        releaseInputControls()
        inputPanels.hide()
        computerKeyboard.hide()
        keyboardBack.isEnabled = false
        updateControlLayout()
        toolbar.refresh()
    }

    fun disconnectSession() {
        stopAutoReconnect()
        releaseInputControls()
        fileTransferControls.cancel()
        hideInputPanels()
        finish()
    }

    private fun setupInputControls(savedState: Bundle?) {
        binding.keyboardContentHost.addView(computerKeyboard.container,
                android.widget.FrameLayout.LayoutParams(-1, -2))
        binding.keyboardContentHost.addView(inputPanels.container,
                android.widget.FrameLayout.LayoutParams(-1, -2))
        computerKeyboard.onVisibilityChanged = { visible ->
            keyboardBack.isEnabled = visible || inputPanels.isVisible
            updateControlLayout()
            toolbar.refresh()
            if (!visible) {
                releaseInputControls()
                if (restoreVirtualKeys && viewModel.connected && viewModel.inPiPMode.value != true)
                    virtualKeys.show()
                restoreVirtualKeys = false
            }
        }
        onBackPressedDispatcher.addCallback(this, keyboardBack)
        floatingTouchpad.onStateChanged = { updateControlLayout(); toolbar.refresh() }
        binding.keyboardHost.background = ControlStyle(this).background(R.color.viewer_solid)
        binding.keyboardHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> computerKeyboard.updateWidth() }
        binding.viewerRoot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateControlLayout() }
        binding.inputView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (or > ol && ob > ot && (l != ol || t != ot || r != or || b != ob))
                releaseInputControls()
        }
        viewModel.inPiPMode.observe(this) { inPiP ->
            clipboardControls.onPiPChanged()
            if (inPiP) { hideInputPanels() }
            floatingTouchpad.setSuppressed(inPiP || viewModel.activeViewMode.value == ServerProfile.VIEW_MODE_NO_INPUT)
        }
        viewModel.activeViewMode.observe(this) { mode ->
            clipboardControls.onStateChanged()
            if (mode == ServerProfile.VIEW_MODE_NO_INPUT) { hideInputPanels() }
            floatingTouchpad.setSuppressed(mode == ServerProfile.VIEW_MODE_NO_INPUT || viewModel.inPiPMode.value == true)
        }
        restoreComputerKeyboard = savedState?.getBoolean("computer_keyboard_visible") == true
        restoreInputTab = savedState?.getString("selected_input_tab")?.let { name -> InputTab.entries.find { it.name == name && it != InputTab.Keyboard } }
    }

    internal fun updateControlLayout() {
        val availableWidth = binding.viewerRoot.width - binding.viewerRoot.paddingLeft - binding.viewerRoot.paddingRight
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val padWidth = floatingTouchpad.preferredWidthPx
        val minimumDockWidth = maxOf(ControlStyle(this).dp(800), padWidth + ControlStyle(this).dp(580))
        val dock = !floatingTouchpad.isTabbed && computerKeyboard.isVisible && landscape && availableWidth >= minimumDockWidth && floatingTouchpad.isExpanded
        if (binding.touchpadDock.layoutParams.width != padWidth)
            binding.touchpadDock.layoutParams = binding.touchpadDock.layoutParams.apply { width = padWidth }
        binding.touchpadDock.isVisible = dock
        floatingTouchpad.setDocked(dock)
        binding.keyboardHost.isVisible = computerKeyboard.isVisible || inputPanels.isVisible
        inputPanels.updateHeight()
        computerKeyboard.updateWidth()
        floatingTouchpad.reposition()
    }

    internal fun releaseInputControls() {
        if (!::binding.isInitialized) return
        computerKeyboard.releaseKeys()
        virtualKeys.releaseMetaKeys()
        floatingTouchpad.cancel()
        inputHandler.cancelInput()
    }

    fun applyRemoteInputSettings() {
        releaseInputControls()
        viewModel.refreshRemoteInputConfig()
        viewModel.saveProfile()
        inputHandler.onRemoteConfigChanged()
        computerKeyboard.onRemoteInputChanged()
        inputPanels.refresh()
        toolbar.refresh()
    }

    fun selectDisplay(id: String) {
        releaseInputControls()
        viewModel.selectDisplay(id)?.let { inputHandler.onAbsolutePointerMove(it) }
    }

    fun setDisplayViewMode(mode: com.gaurav.avnc.model.DisplayViewMode) {
        releaseInputControls()
        viewModel.setDisplayViewMode(mode)
    }

    fun saveManualDisplayLayout(layout: com.gaurav.avnc.model.RemoteDisplayLayout) {
        releaseInputControls()
        viewModel.saveManualDisplayLayout(layout)
    }

    fun setStreamingPreset(preset: com.gaurav.avnc.model.StreamingPreset) {
        releaseInputControls()
        viewModel.setStreamingPreset(preset)
    }

    fun setDockActivation(method: String) = viewModel.setDockActivation(method)
    fun performMacDock() = inputHandler.onMacDock()

    fun performRemoteAction(action: com.gaurav.avnc.model.RemoteAction) = inputHandler.onRemoteAction(action)
    fun performThreeFingerAction(direction: String) = inputHandler.onThreeFingerAction(direction)

    private fun updatePointerCapture(capturePointer: Boolean) {
        if (Build.VERSION.SDK_INT < 26)
            return

        if (capturePointer) {
            binding.inputView.requestFocus()
            binding.inputView.requestPointerCapture()
        } else
            binding.inputView.releasePointerCapture()
    }

    private fun updateStatusContainerVisibility(isConnected: Boolean) {
        val status = binding.statusContainer
        status.animate().cancel()
        // Session state must release the overlay even if an entrance animation is interrupted.
        status.isVisible = !isConnected
        status.alpha = 1f
        if (!isConnected && (android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled())) {
            status.alpha = 0f
            status.animate().alpha(1f).setDuration(180).start()
        }
    }

    /************************************************************************************
     * Picture-in-Picture support
     ************************************************************************************/

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPiPMode()
    }

    @RequiresApi(26)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.inPiPMode.value = isInPictureInPictureMode

        if (!isInPictureInPictureMode) {
            // If user taps the Close button on PiP window, Android will stop the Activity
            // but won't destroy it. This is not a problem for singleTask activities since those
            // are still shown in Recents screen. But AVNC doesn't use singleTask. So VncActivity
            // in PiP mode gets detached into a separate task, which for some reason isn't shown
            // in Recents screen. Hence the activity is effectively leaked.
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                Log.i(TAG, "Finishing activity on PiP Close button click")
                finish()
            }
        }
    }

    private fun enterPiPMode() {
        val canEnter = viewModel.pref.viewer.pipEnabled && viewModel.connected && !viewModel.videoDisabled

        if (canEnter && Build.VERSION.SDK_INT >= 26) {
            releaseInputControls()
            hideInputPanels()

            var w = viewModel.frameState.fbWidth
            var h = viewModel.frameState.fbHeight
            if (w <= 0 || h <= 0)
                return

            // Android require aspect ratio to be less than 2.39
            w = w.coerceIn(1f, 2.3f * h)
            h = h.coerceIn(1f, 2.3f * w)

            val aspectRatio = Rational(w.toInt(), h.toInt())
            val param = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()

            try {
                enterPictureInPictureMode(param)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot enter PiP mode", e)
            }
        }
    }

    /************************************************************************************
     * Input
     ************************************************************************************/

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && selectedInputTab != null && !event.isFromSource(InputDevice.SOURCE_MOUSE))
            return super.onKeyDown(keyCode, event)
        return inputHandler.onKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && selectedInputTab != null && !event.isFromSource(InputDevice.SOURCE_MOUSE))
            return super.onKeyUp(keyCode, event)
        return inputHandler.onKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        return inputHandler.onKeyEvent(event) || super.onKeyMultiple(keyCode, repeatCount, event)
    }


    /************************************************************************************
     * Help for new users.
     * Two of the most common question asked by new users are:
     * - Where is the toolbar, or how to open it
     * - How to cleanly exit a session
     *
     * When user starts a session for the first time, this help is shown.
     * It consists of two pages: one shows how to open the toolbar drawer,
     * other tells about the Back navigation button.
     ***********************************************************************************/
    fun showViewerHelp() {
        if (viewModel.pref.runInfo.hasShownViewerHelp) return
        viewModel.pref.runInfo.hasShownViewerHelp = true
        toolbar.showHelp()
    }
}
