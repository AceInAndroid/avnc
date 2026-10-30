/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.RemoteAction
import java.lang.ref.WeakReference
import com.gaurav.avnc.vnc.PointerButton

/**
 * Input handler for remote session.
 * All input events are passed through here.
 *
 * This is a simple hub, used for logging, filtering, notifications etc.
 * Main input processing happens in [TouchHandler] & [KeyHandler].
 */
class InputHandler {
    private var dispatcher: Dispatcher? = null
    private var touchHandler: TouchHandler? = null
    private var keyHandler: KeyHandler? = null
    private var interceptMouseBack = false
    private var viewModel: VncViewModel? = null
    private var activityRef = WeakReference<VncActivity>(null)
    val systemGesturesEnabled get() = interactive && viewModel?.remoteInputConfig?.systemGestures == true
    private val interactive: Boolean get() = viewModel?.let {
        it.connected && it.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT && it.inPiPMode.value != true
    } == true

    /**
     * List of listeners to be notified after a [KeyEvent] is handled
     */
    val onAfterKeyEventListeners = mutableListOf<(KeyEvent) -> Unit>()

    fun onSessionConnected(activity: VncActivity) {
        val viewModel = activity.viewModel
        this.viewModel = viewModel
        activityRef = WeakReference(activity)
        dispatcher = Dispatcher(activity)
        touchHandler = TouchHandler(activity.binding.inputView, dispatcher!!, viewModel.pref)
        keyHandler = KeyHandler(dispatcher!!, viewModel.pref) { viewModel.remoteInputConfig }
        interceptMouseBack = viewModel.pref.input.interceptMouseBack
    }

    fun onSessionDisconnected() {
        cancelInput()
        dispatcher = null
        touchHandler = null
        keyHandler = null
        interceptMouseBack = false
        viewModel = null
        activityRef.clear()
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        dispatcher?.stopRemoteScroll()
        val handled = (interactive && keyHandler?.onKeyEvent(keyEvent) == true) || interceptBackPressFromMouse(keyEvent)
        onAfterKeyEventListeners.forEach { it(keyEvent) }
        return handled
    }

    fun onVkKeyEvent(keyEvent: KeyEvent): Boolean {
        dispatcher?.stopRemoteScroll()
        val handled = interactive && keyHandler?.onVkKeyEvent(keyEvent) == true
        onAfterKeyEventListeners.forEach { it(keyEvent) }
        return handled
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return touchHandler?.onTouchEvent(event) == true
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return touchHandler?.onGenericMotionEvent(event) == true
    }

    fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        return touchHandler?.onCapturedPointerEvent(event) == true
    }

    fun onHoverEvent(event: MotionEvent): Boolean {
        return touchHandler?.onHoverEvent(event) == true
    }

    fun onVirtualPointerStart() { if (interactive) dispatcher?.onVirtualPointerStart() }
    fun onVirtualPointerMotion(event: MotionEvent) {
        if (!interactive) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) dispatcher?.accelerator?.clear()
        dispatcher?.accelerator?.addMovement(event)
    }

    fun onRemoteConfigChanged() { cancelInput(); dispatcher?.reloadConfig() }

    fun onRemoteAction(action: RemoteAction): Boolean {
        if (!interactive) return false
        val shortcut = viewModel?.remoteInputConfig?.shortcut(action) ?: return false
        activityRef.get()?.releaseInputControls()
        return keyHandler?.sendShortcut(shortcut) == true
    }

    fun onAbsolutePointerMove(point: PointF) {
        if (interactive) dispatcher?.onAbsolutePointerMove(point)
    }

    fun onMacDock() {
        if (!interactive || viewModel?.remoteInputConfig?.isMac != true) return
        val method = viewModel?.profile?.dockActivation ?: "shortcut"
        if (method == "shortcut") onRemoteAction(RemoteAction.DOCK)
        else {
            activityRef.get()?.releaseInputControls()
            dispatcher?.onMacDockEdge(method)
        }
    }

    fun onThreeFingerAction(direction: String) {
        if (!systemGesturesEnabled) return
        val action = when (direction) {
            "left" -> RemoteAction.NEXT_DESKTOP
            "right" -> RemoteAction.PREVIOUS_DESKTOP
            "up" -> RemoteAction.OVERVIEW
            "down" -> RemoteAction.SHOW_DESKTOP
            else -> return
        }
        onRemoteAction(action)
    }
    fun onVirtualPointerMove(dx: Float, dy: Float) { if (interactive) dispatcher?.onVirtualPointerMove(dx, dy) }
    fun onVirtualPointerButton(button: PointerButton, down: Boolean) {
        if (interactive) dispatcher?.onVirtualPointerButton(button, down)
    }
    fun onVirtualPointerClick(button: PointerButton = PointerButton.Left, count: Int = 1) {
        if (interactive) dispatcher?.onVirtualPointerClick(button, count)
    }
    fun onVirtualPointerScroll(dx: Float, dy: Float) { if (interactive) dispatcher?.onVirtualPointerScroll(dx, dy) }
    fun onVirtualPointerScrollEnd(vx: Float, vy: Float, cancelled: Boolean = false) {
        if (interactive) dispatcher?.onVirtualPointerScrollEnd(vx, vy, cancelled)
    }
    fun onVirtualPointerStop(cancelled: Boolean = false) { dispatcher?.onVirtualPointerStop(cancelled) }

    fun cancelInput() {
        touchHandler?.cancel()
        keyHandler?.releasePressedKeys()
        dispatcher?.cancelInput()
    }

    /*********************************************************************************************/

    private fun interceptBackPressFromMouse(keyEvent: KeyEvent): Boolean {
        //It seems that some device manufacturers are hell-bent on making developers'
        //life miserable. In their infinite wisdom, they decided that Android apps don't
        //need Mouse right-click events. It is hardcoded to act as back-press, without
        //giving apps a chance to handle it. For better or worse, they set the 'source'
        //for such key events to Mouse, enabling the following workarounds.
        if (touchHandler != null && interceptMouseBack &&
            keyEvent.keyCode == KeyEvent.KEYCODE_BACK &&
            keyEvent.scanCode == 0 &&
            keyEvent.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY == 0 &&
            InputDevice.getDevice(keyEvent.deviceId)?.supportsSource(InputDevice.SOURCE_MOUSE) == true) {
            if (keyEvent.action == KeyEvent.ACTION_DOWN)
                touchHandler!!.onMouseBack()
            return true
        }
        return false
    }
}
