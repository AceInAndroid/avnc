/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel

import android.app.Application
import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.graphics.PointF
import com.gaurav.avnc.model.RemoteDisplay
import com.gaurav.avnc.model.RemoteDisplayLayout
import com.gaurav.avnc.model.DisplayViewMode
import com.gaurav.avnc.model.StreamingPreset
import android.media.ToneGenerator
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gaurav.avnc.R
import com.gaurav.avnc.model.LoginInfo
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.session.RemoteSession
import com.gaurav.avnc.ui.vnc.FrameScroller
import com.gaurav.avnc.ui.vnc.FrameState
import com.gaurav.avnc.ui.vnc.FrameView
import com.gaurav.avnc.util.LiveRequest
import com.gaurav.avnc.util.Tones
import com.gaurav.avnc.util.debugCheck
import com.gaurav.avnc.util.getClipboardText
import com.gaurav.avnc.util.getKnownHostsFile
import com.gaurav.avnc.util.getUnknownCertificateMessage
import com.gaurav.avnc.util.isCertificateTrusted
import com.gaurav.avnc.util.isTrue
import com.gaurav.avnc.util.monitor
import com.gaurav.avnc.util.setClipboardText
import com.gaurav.avnc.util.trustCertificate
import com.gaurav.avnc.viewmodel.service.SshClient
import com.gaurav.avnc.vnc.UserCredential
import com.gaurav.avnc.vnc.VncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.cert.X509Certificate

/**
 * ViewModel for VncActivity
 *
 * Connection
 * ==========
 *
 * At construction, we instantiate a [VncClient] referenced by [client]. Then
 * activity starts the connection by calling [initConnection] which starts a coroutine to
 * handle connection setup.
 *
 * After successful connection, we continue to operate normally until the remote
 * server closes the connection, or an error occurs. Once disconnected, we
 * wait for the activity to finish and then cleanup any acquired resources.
 *
 * Currently, lifecycle of [client] is tied to this view model. So one [VncViewModel]
 * manages only one [VncClient].
 *
 *
 * Threading
 * =========
 *
 * Receiver thread :- This thread is started in [launchConnection].
 * It handles the protocol initialization, and after that processes incoming messages.
 * Most of the callbacks of [VncClient.Observer] are invoked on this thread. In most
 * cases it is stopped when activity is finished and this view model is cleaned up.
 *
 * Sender thread :- This thread is created (as an executor) by [messenger]. It is
 * used to send messages to remote server. We use this dedicated thread instead
 * of coroutines to preserve the order of sent messages.
 *
 * UI thread :- Main thread of the app. Used for updating UI and controlling other
 * Threads. This is where [frameState] is updated.
 *
 * Renderer thread :- This is managed by [FrameView] and used for rendering frame
 * via OpenGL ES. [frameState] is read from this thread to decide how/where frame
 * should be drawn.
 */
class VncViewModel(app: Application) : BaseViewModel(app) {

    /**
     * Connection lifecycle:
     *
     *            Created
     *               |
     *               v
     *          Connecting ----------+
     *               |               |
     *               v               |
     *           Connected           |
     *               |               |
     *               v               |
     *          Disconnected <-------+
     *
     */
    enum class State {
        Created,
        Connecting,
        Connected,
        Disconnected;
    }

    lateinit var profile: ServerProfile

    val remoteInputPreferences by lazy { com.gaurav.avnc.util.RemoteInputPreferences(app) }
    var remoteInputConfig = com.gaurav.avnc.model.RemoteInputConfig()
        private set

    fun refreshRemoteInputConfig() {
        remoteInputConfig = remoteInputPreferences.resolve(profile, client?.isAppleServer() == true)
        applyDisplayViewport()
    }

    val displayLayout = MutableLiveData(RemoteDisplayLayout())
    val streamingPreset = MutableLiveData(StreamingPreset.LEGACY)
    val streamingPresetPending = MutableLiveData(false)
    val streamingPresetFailed = MutableLiveData(false)
    val viewerSettingsVersion = MutableLiveData(0)
    // Observed synchronously on the UI thread before changing coordinate spaces.
    val viewportWillChange = MutableLiveData(0)
    val displayViewMode get() = if (::profile.isInitialized) DisplayViewMode.from(profile.displayViewMode) else DisplayViewMode.SINGLE
    val selectedDisplay: RemoteDisplay? get() = if (remoteInputConfig.isMac && displayViewMode == DisplayViewMode.SINGLE)
        displayLayout.value?.displays?.firstOrNull { it.id == profile.selectedDisplayId } else null
    private data class DisplayView(val zoom1: Float, val zoom2: Float, val x: Float, val y: Float)
    private val displayViews = mutableMapOf<String, DisplayView>()
    private val displayPointers = mutableMapOf<String, PointF>()
    private var activeDisplayKey = "all"

    private fun settingsChanged() { viewerSettingsVersion.value = (viewerSettingsVersion.value ?: 0) + 1 }
    private fun prepareViewportChange() {
        viewportWillChange.value = (viewportWillChange.value ?: 0) + 1
        frameScroller.stop()
        frameState.let { displayViews[activeDisplayKey] = DisplayView(it.zoomScale1, it.zoomScale2, it.frameX, it.frameY) }
        client?.let { displayPointers[activeDisplayKey] = PointF(it.pointerX.toFloat(), it.pointerY.toFloat()) }
    }

    private fun applyDisplayViewport() {
        if (!::profile.isInitialized) return
        prepareViewportChange()
        val display = selectedDisplay
        val key = display?.id ?: "all"
        val changed = key != activeDisplayKey || frameState.contentRect != (display?.bounds ?: RectF(0f, 0f, frameState.fbWidth, frameState.fbHeight))
        if (changed) {
            frameState.setZoom(profile.zoom1, profile.zoom2)
            frameState.setContentRect(display?.bounds)
            displayViews[key]?.let {
                frameState.setZoom(it.zoom1, it.zoom2)
                frameState.moveTo(it.x, it.y)
            }
            activeDisplayKey = key
        }
        if (display == null) messenger?.setDesktopRegion(0, 0, 0, 0)
        else messenger?.setDesktopRegion(display.x, display.y, display.width, display.height)
        settingsChanged()
        refreshFrameView()
    }

    fun selectDisplay(id: String): PointF? {
        val display = displayLayout.value?.displays?.firstOrNull { it.id == id } ?: return null
        if (!remoteInputConfig.isMac) return null
        profile.displayViewMode = DisplayViewMode.SINGLE.id
        profile.selectedDisplayId = id
        applyDisplayViewport()
        saveProfile()
        val previous = displayPointers[id]
        val rect = display.bounds
        return PointF((previous?.x ?: rect.centerX()).coerceIn(rect.left, rect.right - 1),
                      (previous?.y ?: rect.centerY()).coerceIn(rect.top, rect.bottom - 1))
    }

    fun setDisplayViewMode(mode: DisplayViewMode) {
        profile.displayViewMode = mode.id
        ensureDisplaySelection()
        applyDisplayViewport()
        saveProfile()
    }

    private fun ensureDisplaySelection() {
        val displays = displayLayout.value?.displays.orEmpty()
        if (displays.isEmpty()) return // EDS often arrives after the initial framebuffer-size callback.
        if (displays.none { it.id == profile.selectedDisplayId }) {
            profile.selectedDisplayId = displays.firstOrNull { it.contains(client?.pointerX ?: -1, client?.pointerY ?: -1) }?.id
                    ?: displays.firstOrNull()?.id.orEmpty()
        }
    }

    fun saveManualDisplayLayout(layout: RemoteDisplayLayout) {
        if (!layout.isValid() || layout.framebufferWidth != frameState.fbWidth.toInt() || layout.framebufferHeight != frameState.fbHeight.toInt()) return
        val manual = layout.copy(source = "manual")
        profile.manualDisplayLayout = manual.encode()
        displayViews.clear()
        displayPointers.clear()
        displayLayout.value = manual
        ensureDisplaySelection()
        applyDisplayViewport()
        saveProfile()
    }

    private fun updateDisplayLayout(protocol: RemoteDisplayLayout) {
        val saved = RemoteDisplayLayout.decode(profile.manualDisplayLayout)
        val manualExists = saved.isValid()
        val manualFits = manualExists && saved.source != "manual_invalid" && saved.framebufferWidth == protocol.framebufferWidth && saved.framebufferHeight == protocol.framebufferHeight
        if (manualExists && !manualFits && saved.source != "manual_invalid")
            profile.manualDisplayLayout = saved.copy(source = "manual_invalid").encode()
        displayLayout.value = when {
            protocol.isValid() -> protocol
            manualFits -> saved.copy(source = "manual")
            manualExists -> protocol.copy(source = "manual_invalid")
            else -> protocol
        }
        ensureDisplaySelection()
        applyDisplayViewport()
        saveProfile()
    }

    fun setStreamingPreset(preset: StreamingPreset) {
        profile.streamingPreset = preset.id
        streamingPresetPending.value = connected && messenger != null
        streamingPresetFailed.value = false
        messenger?.setStreamingPreset(preset.id)
        saveProfile()
        settingsChanged()
    }

    fun setDockActivation(method: String) {
        if (method !in listOf("shortcut", "bottom", "left", "right")) return
        profile.dockActivation = method
        saveProfile()
        settingsChanged()
    }

    /**
     * Live version of [profile], allows easier access from UI layer
     */
    val profileLive = MutableLiveData<ServerProfile>()

    var client: VncClient? = null

    private val session = RemoteSession(RemoteSessionObserver())

    /**
     * We have two places for connection state (both are synced):
     *
     * [VncClient.connected] - Simple boolean state, used most of the time
     * [state]               - More granular, used by observers & data binding
     */
    val state = MutableLiveData(State.Created)

    val connected get() = state.value == State.Connected

    /**
     * Reason for disconnecting.
     */
    val disconnectReason = MutableLiveData("")

    /**
     * Fired when we need some credentials from user.
     * It will trigger the Login dialog.
     */
    val loginInfoRequest = LiveRequest<LoginInfo.Type, LoginInfo>()

    /**
     * List of saved profiles.
     * Used by login-autocompletion.
     */
    val savedProfiles by lazy { serverProfileDao.getLiveList() }

    /**
     * Holds a weak reference to [FrameView] instance.
     *
     * This is used to tell [FrameView] to re-render its content when VncClient's
     * framebuffer is updated. Instead of using LiveData/LiveEvent, we keep a
     * weak reference because:
     *
     *      1. It avoids a context-switch to UI thread. Rendering request to
     *         a GlSurfaceView can be sent from any thread.
     *
     *      2. We don't have to invoke the whole ViewModel machinery just for
     *         a single call to FrameView.
     */
    var frameViewRef = WeakReference<FrameView>(null)

    /**
     * Holds information about scaling, translation etc.
     */
    val frameState = with(pref.viewer) { FrameState(zoomMin, zoomMax, perOrientationZoom) }

    /**
     * Used for scrolling/animating the frame.
     */
    val frameScroller = FrameScroller(this)

    /**
     * Currently active gesture style
     */
    val activeGestureStyle = MutableLiveData<String>()

    val activeViewMode = MutableLiveData<Int>()
    val videoDisabled get() = (activeViewMode.value == ServerProfile.VIEW_MODE_NO_VIDEO)

    /**
     * Used for sending events to remote server.
     */
    var messenger: Messenger? = null

    /**
     * Used to confirm something with user before continuing.
     * This is mostly used to warn about unknown SSH host, x509 certificates etc.
     * This request accepts two strings: First is used as title, second contains the message.
     */
    val confirmationRequest = LiveRequest<Pair<String, String>, Boolean>()

    /**
     * If user has asked to remember credentials in login dialog, we need to
     * save them to database. But we don't want to save them immediately
     * because user might have mistyped them. So, we wait until successful
     * connection before saving them.
     */
    var loginInfoToBeRemembered = mutableListOf<LoginInfo>()

    /**
     * Whether activity window is currently focused
     */
    val hasWindowFocus = MutableLiveData<Boolean>()

    /**
     * Whether viewer help is being shown
     */
    val viewerHelpIsVisible = MutableLiveData<Boolean>()

    /**
     * Whether activity is in Picture-in-Picture mode
     */
    val inPiPMode = MutableLiveData<Boolean>()

    val preferredScreenOrientation = monitor(profileLive) { resolveScreenOrientation() }

    val capturePointer = monitor(state, hasWindowFocus, viewerHelpIsVisible) { resolveCapturePointer() }

    override fun onCleared() {
        super.onCleared()
        session.stop()
    }


    /**************************************************************************
     * Connection management
     **************************************************************************/

    /**
     * Initialize VNC connection.
     * It can be called multiple times due to activity restarts.
     */
    fun initConnection(profile: ServerProfile) {
        if (state.value == State.Created) {
            this.profile = profile
            streamingPreset.value = StreamingPreset.from(profile.streamingPreset)
            streamingPresetPending.value = true
            profileLive.value = profile
            state.value = State.Connecting
            frameState.setZoom(profile.zoom1, profile.zoom2)
            setViewMode(profile.viewMode)
            resolveGestureStyle()
            inPiPMode.observeForever { if (it) resetZoom() }
            session.start(profile)
        }
    }

    /**
     * Can be used to persist any changes made to [profile]
     */
    fun saveProfile() {
        if (profile.isSaved())
            launchMain { serverProfileDao.update(profile) }
    }

    suspend fun getProfileById(id: Long) = serverProfileDao.getByID(id)

    /**************************************************************************
     * Frame management
     **************************************************************************/

    fun updateZoom(scaleFactor: Float, fx: Float, fy: Float) {
        if (profile.fZoomLocked || videoDisabled) return

        frameState.updateZoom(scaleFactor, fx, fy)
        refreshFrameView()
    }

    fun resetZoom() {
        frameState.setZoom(1f, 1f)
        refreshFrameView()
    }

    fun resetZoomToDefault() {
        frameState.setZoom(profile.zoom1, profile.zoom2)
        refreshFrameView()
    }

    fun setZoom(zoom1: Float, zoom2: Float) {
        frameState.setZoom(zoom1, zoom2)
        refreshFrameView()
    }

    fun panFrame(deltaX: Float, deltaY: Float) {
        frameState.pan(deltaX, deltaY)
        refreshFrameView()
    }

    fun moveFrameTo(x: Float, y: Float) {
        frameState.moveTo(x, y)
        refreshFrameView()
    }

    fun toggleZoomLock(enabled: Boolean) {
        profile.fZoomLocked = enabled
        saveProfile()
    }

    fun saveZoom() {
        profile.zoom1 = frameState.zoomScale1
        profile.zoom2 = frameState.zoomScale2
        saveProfile()
    }

    fun setSafeArea(safeArea: RectF) {
        frameState.setSafeArea(safeArea)
        refreshFrameView()
    }

    /**************************************************************************
     * Miscellaneous
     **************************************************************************/

    fun sendClipboardText() {
        if (pref.server.clipboardSync && connected) launchIO {
            getClipboardText(app)?.let { messenger?.sendClipboardText(it) }
        }
    }

    private var clipReceiverJob: Job? = null
    private fun receiveClipboardText(text: String) {
        if (!pref.server.clipboardSync)
            return

        // This is a protective measure against servers which send every 'selection' made on the server.
        // Setting clip text involves IPC, so these events can exhaust Binder resources, leading to ANRs.
        if (clipReceiverJob?.isActive == true) {
            Log.w(javaClass.simpleName, "Dropping clip text received from server, previous text is still pending")
            return
        }

        clipReceiverJob = launchIO {
            setClipboardText(app, text)
        }
    }

    fun getLoginInfo(type: LoginInfo.Type): LoginInfo {
        val li = LoginInfo.fromProfile(profile, type)

        // If info is already available, return it
        if ((type == LoginInfo.Type.VNC_PASSWORD && li.password.isNotBlank()) ||
            (type == LoginInfo.Type.VNC_CREDENTIAL && li.username.isNotBlank() && li.password.isNotBlank()) ||
            (type == LoginInfo.Type.SSH_PASSWORD && li.password.isNotBlank()))
            return li

        // Something is missing, so we have to ask the user
        return loginInfoRequest.getResponseFor(type, viewModelScope)  // Blocking call
    }

    /**
     * Resize remote desktop to match with local window size (if requested by user).
     * In portrait mode, safe area is used instead of window to exclude the keyboard.
     */
    fun resizeRemoteDesktop() {
        if (connected && profile.resizeRemoteDesktop && !videoDisabled && !remoteInputConfig.isMac)
            frameState.let {
                if (it.windowWidth > it.windowHeight)
                    messenger?.setDesktopSize(it.windowWidth.toInt(), it.windowHeight.toInt())
                else
                    messenger?.setDesktopSize(it.safeArea.width().toInt(), it.safeArea.height().toInt())
            }
    }

    fun setFrameBufferUpdatesPaused(paused: Boolean) {
        messenger?.setFrameBufferUpdatesPaused(paused)
    }

    fun refreshFrameBuffer() {
        messenger?.refreshFrameBuffer()
    }

    /**
     * Sets gesture style of profile to given value.
     * Any change will be reflected in [activeGestureStyle].
     */
    fun setGestureStyle(newStyle: String) {
        if (profile.gestureStyle != newStyle) {
            profile.gestureStyle = newStyle
            saveProfile()
        }

        resolveGestureStyle()
    }

    private fun resolveGestureStyle() {
        var gestureStyle = profile.gestureStyle

        if (gestureStyle == "auto")
            gestureStyle = pref.input.gesture.style

        if (videoDisabled)
            gestureStyle = "touchpad"

        check(gestureStyle.isNotBlank())
        if (activeGestureStyle.value != gestureStyle)
            activeGestureStyle.value = gestureStyle
    }

    fun setViewMode(newMode: Int) {
        if (profile.viewMode != newMode) {
            profile.viewMode = newMode
            saveProfile()
        }

        if (activeViewMode.value != newMode) {
            activeViewMode.value = newMode
            messenger?.setInputDisabled(newMode == ServerProfile.VIEW_MODE_NO_INPUT)
            setFrameBufferUpdatesPaused(newMode == ServerProfile.VIEW_MODE_NO_VIDEO)
            resolveGestureStyle()
        }
    }

    private fun refreshFrameView() {
        frameViewRef.get()?.requestRender()
    }

    private fun rememberLoginInfo() {
        if (loginInfoToBeRemembered.isNotEmpty()) {
            debugCheck(profile.isSaved())
            loginInfoToBeRemembered.forEach { it.applyTo(profile) }
            loginInfoToBeRemembered.clear()
            saveProfile()
        }
    }

    private fun resolveScreenOrientation(): Int {
        var choice = profileLive.value?.screenOrientation
        if (choice == null || choice == "auto")
            choice = pref.viewer.orientation


        return when (choice) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun resolveCapturePointer(): Boolean {
        return connected && pref.input.capturePointer &&
               hasWindowFocus.isTrue && !viewerHelpIsVisible.isTrue
    }


    /**************************************************************************
     * Session observer
     **************************************************************************/
    private inner class RemoteSessionObserver : RemoteSession.Observer {

        override fun onConnecting() {
            state.postValue(State.Connecting)
        }

        override fun onConnected(vncClient: VncClient, messenger: Messenger) {
            launchMain {
                this@VncViewModel.client = vncClient
                this@VncViewModel.messenger = messenger
                refreshRemoteInputConfig()
                ensureDisplaySelection()
                applyDisplayViewport()
                state.value = State.Connected

                rememberLoginInfo()

                // Initial clipboard sync, slightly delayed to allow extended clipboard negotiations
                delay(1000L)
                sendClipboardText()
            }
        }

        override fun onDisconnected() {
            // Block until main thread is synchronised with disconnected state
            runBlocking(Dispatchers.Main) {
                state.value = State.Disconnected
                displayPointers.clear()
                displayViews.clear()
                streamingPresetPending.value = false
                client = null
                messenger = null
            }
        }

        override fun onConnectionError(error: Throwable) {
            when (error) {
                is IOException -> disconnectReason.postValue(error.message)
                is UnsatisfiedLinkError -> disconnectReason.postValue("Native library is missing! Please install correct" +
                                                                      " version of AVNC from F-Droid or Google Play")
            }
        }

        override fun onWakeOnLanBroadcastError(e: Throwable) {
            launchMain { Toast.makeText(app, "Wake-on-LAN: ${e.message}", Toast.LENGTH_LONG).show() }
        }


        /**************************** [VncClient.Observer] *******************/

        override fun getVncPassword(): String {
            return getLoginInfo(LoginInfo.Type.VNC_PASSWORD).password
        }

        override fun getVncCredentials(): UserCredential {
            return getLoginInfo(LoginInfo.Type.VNC_CREDENTIAL).let { UserCredential(it.username, it.password) }
        }

        override fun verifyVncServerCertificate(certificate: X509Certificate): Boolean {
            if (isCertificateTrusted(app, certificate))
                return true

            val title = "Unknown server certificate"
            val message = getUnknownCertificateMessage(certificate)
            if (confirmationRequest.getResponseFor(Pair(title, message), viewModelScope)) {
                trustCertificate(app, certificate)
                return true
            }

            return false
        }

        override fun onFramebufferUpdated() {
            refreshFrameView()
        }

        override fun onCutTextReceived(text: String) {
            receiveClipboardText(text)
        }

        override fun onFramebufferSizeChanged(width: Int, height: Int) {
            launchMain {
                prepareViewportChange()
                frameState.setFramebufferSize(width.toFloat(), height.toFloat())
                val current = displayLayout.value
                if (current?.framebufferWidth != width || current.framebufferHeight != height)
                    updateDisplayLayout(RemoteDisplayLayout(width, height))
                refreshFrameView()
            }
        }

        override fun onDisplayLayoutChanged(width: Int, height: Int, screens: IntArray) {
            val layout = RemoteDisplayLayout.fromProtocol(width, height, screens)
            launchMain { updateDisplayLayout(layout) }
        }

        override fun onStreamingPresetApplied(preset: String, success: Boolean) {
            launchMain {
                if (preset == profile.streamingPreset) {
                    streamingPresetPending.value = false
                    streamingPresetFailed.value = !success
                    if (success) streamingPreset.value = StreamingPreset.from(preset)
                    settingsChanged()
                }
            }
        }

        override fun onPointerMoved(x: Int, y: Int) {
            refreshFrameView()
        }

        override fun onBell() {
            if (pref.ui.bell) {
                Tones.notify(ToneGenerator.TONE_PROP_BEEP)
            }
        }


        /**************************** [SshClient.Observer] *******************/

        override fun getKnownSshHostsFile() = getKnownHostsFile(app)

        override fun getSshPassword(): String {
            return getLoginInfo(LoginInfo.Type.SSH_PASSWORD).password
        }

        override fun getSshKeyPassword(): String {
            return getLoginInfo(LoginInfo.Type.SSH_KEY_PASSWORD).password
        }

        override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
            val titleRes = if (isNewHost) R.string.title_unknown_ssh_host else R.string.title_ssh_host_key_changed
            val title = app.getString(titleRes)
            return confirmationRequest.getResponseFor(Pair(title, message), viewModelScope)
        }
    }
}
