/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.session.Messenger
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.XKeySym

/**
 * We allow users to customize the actions for different events.
 * This class reads those preferences and invokes proper handlers.
 *
 * Input handling overview:
 *
 *-     +----------------+     +--------------------+     +--------------+
 *-     |  Touch events  |     |     Key events     |     | Virtual keys |
 *-     +----------------+     +--------------------+     +--------------+
 *-             |                        |                        |
 *-             v                        v                        |
 *-     +----------------+     +--------------------+             |
 *-     | [TouchHandler] |     |    [KeyHandler]    |<------------+
 *-     +----------------+     +--------------------+
 *-             |                        |
 *-             |                        v
 *-             |              +--------------------+
 *-             +------------->+    [Dispatcher]    +
 *-                            +--------------------+
 *-                                      |
 *-                                      |
 *-                 +--------------------+---------------------+
 *-                 |                    |                     |
 *-                 v                    v                     v
 *-         +---------------+    +----------------+    +---------------+
 *-         |  [Messenger]  |    | [VncViewModel] |    | [VncActivity] |
 *-         +---------------+    +----------------+    +---------------+
 *-
 *-
 *
 * 1. First we identify which gesture/key was input by the user.
 * 2. Then we select an action based on user preferences. This is done here in [Dispatcher].
 * 3. Then that action is executed. Some actions change local app state (e.g. zoom in/out),
 *    while others send events to remote server (e.g. mouse click).
 */
class Dispatcher(private val activity: VncActivity) {

    private companion object {
        // Panel deltas arrive in dp; 1:1 remote pixels made a desktop take many swipes.
        const val TOUCHPAD_POINTER_GAIN = 3f
    }

    private val viewModel = activity.viewModel
    private val gesturePref = viewModel.pref.input.gesture
    val accelerator = PointerAcceleration(viewModel)


    /**************************************************************************
     * Action configuration
     **************************************************************************/
    private val directMode = DirectPointerMode(viewModel)
    private val relativeMode = RelativePointerMode(viewModel, accelerator)
    private val virtualMode = RelativePointerMode(viewModel, accelerator)
    private var config = Config()
    private val density = activity.resources.displayMetrics.density
    private val drag = PointerDragController(activity.binding.inputView, viewModel)
    private val remoteScroll = RemoteScrollController(activity.binding.inputView,
            { gesturePref.remoteScrollInertia },
            { viewModel.connected && viewModel.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT && viewModel.inPiPMode.value != true },
            { button, point ->
                viewModel.messenger?.sendPointerButtonDown(button, point)
                viewModel.messenger?.sendPointerButtonUp(button, point)
            })
    private var scrollMode: BasePointerMode? = null

    private fun scroll(mode: BasePointerMode, point: PointF, dxDp: Float, dyDp: Float) {
        if (scrollMode !== mode) {
            val remote = mode.transformPoint(point) ?: return
            remoteScroll.begin(remote)
            scrollMode = mode
        }
        remoteScroll.scroll(dxDp, dyDp * if (gesturePref.invertVerticalScrolling) -1 else 1)
    }

    fun stopRemoteScroll() {
        remoteScroll.cancel()
        scrollMode = null
    }

    private fun dragTo(mode: BasePointerMode, button: PointerButton, sp: PointF, cp: PointF, dx: Float, dy: Float) {
        if (!drag.active) { stopRemoteScroll(); drag.begin(mode, button, sp) }
        drag.move(cp, dx, dy)
    }

    private inner class Config {
        val gestureStyle = viewModel.activeGestureStyle.value ?: gesturePref.style
        val defaultMode = if (gestureStyle == "touchscreen") directMode else relativeMode

        val tap1Action = selectPointAction(gesturePref.tap1)
        val tap2Action = selectPointAction(gesturePref.tap2)
        val tap3Action = selectPointAction(gesturePref.tap3)
        val doubleTapAction = selectPointAction(gesturePref.doubleTap)
        val longPressAction = selectPointAction(gesturePref.longPress)

        val swipe1Pref = if (gestureStyle == "touchpad") "move-pointer" else gesturePref.swipe1
        val swipe1Action = selectSwipeAction(swipe1Pref)
        val swipe2Action = selectSwipeAction(gesturePref.swipe2)
        val swipe3Action = selectSwipeAction(gesturePref.swipe3)
        val doubleTapSwipeAction = selectSwipeAction(gesturePref.doubleTapSwipe)
        val longPressSwipeAction = selectSwipeAction(gesturePref.longPressSwipe)
        val flingAction = selectFlingAction()

        val mouseBackAction = selectPointAction(viewModel.pref.input.mouseBack)

        private fun selectPointAction(actionName: String): (PointF) -> Unit {
            return when (actionName) {
                "left-press" -> { p -> defaultMode.doButtonDown(PointerButton.Left, p) }
                "left-drag" -> { p -> stopRemoteScroll(); drag.begin(defaultMode, PointerButton.Left, p) }
                "left-click" -> { p -> defaultMode.doClick(PointerButton.Left, p) }
                "double-click" -> { p -> defaultMode.doDoubleClick(PointerButton.Left, p) }
                "middle-click" -> { p -> defaultMode.doClick(PointerButton.Middle, p) }
                "right-click" -> { p -> defaultMode.doClick(PointerButton.Right, p) }
                "remote-back-press" -> { _ -> doRemoteBackPress() }
                "open-keyboard" -> { _ -> doOpenKeyboard() }
                else -> { _ -> } //Nothing
            }
        }

        /**
         * Returns a lambda which accepts four arguments:
         *
         * sp: Start point of the gesture
         * cp: Current point of the gesture
         * dx: Change along x-axis since last event
         * dy: Change along y-axis since last event
         */
        private fun selectSwipeAction(actionName: String): (PointF, PointF, Float, Float) -> Unit {
            return when (actionName) {
                "pan" -> { _, _, dx, dy -> doPan(dx, dy) }
                "move-pointer" -> { _, cp, dx, dy -> defaultMode.doMovePointer(cp, dx, dy) }
                "remote-scroll" -> { sp, _, dx, dy -> scroll(defaultMode, sp, dx / density, dy / density) }
                "remote-drag" -> { sp, cp, dx, dy -> dragTo(defaultMode, PointerButton.Left, sp, cp, dx, dy) }
                "remote-drag-middle" -> { sp, cp, dx, dy -> dragTo(defaultMode, PointerButton.Middle, sp, cp, dx, dy) }
                else -> { _, _, _, _ -> } //Nothing
            }
        }

        /**
         * Fling is only used for smooth-scrolling the frame.
         * So it only makes sense when 1-finger-swipe is set to "pan".
         */
        private fun selectFlingAction(): (Float, Float) -> Unit {
            return if (swipe1Pref == "pan") { vx, vy -> startFrameFling(vx, vy) }
            else { _, _ -> }
        }
    }

    init {
        viewModel.activeGestureStyle.observe(activity) { cancelInput(); config = Config() }
    }

    /**************************************************************************
     * Event receivers
     **************************************************************************/

    fun onGestureStart() {
        drag.finish()
        stopRemoteScroll()
        config.defaultMode.onGestureStart()
        stopFrameFling()
    }

    fun onGestureStop(p: PointF) {
        val wasDragging = drag.active
        drag.finish()
        // Use the actual client cursor: p may have left the framebuffer during a drag.
        releasePointerButtons()
        config.defaultMode.resetScroll()
        if (!wasDragging) onRemoteScrollEnd(0f, 0f)
        scrollMode = null
        viewModel.frameState.onGestureStop()
    }

    fun onGestureCancel() {
        drag.finish()
        stopRemoteScroll()
        releasePointerButtons()
        viewModel.frameScroller.stop()
        viewModel.frameState.onGestureStop()
    }

    fun onDragMove(p: PointF, dx: Float, dy: Float) = drag.move(p, dx, dy)

    fun onRemoteScrollEnd(vx: Float, vy: Float, cancelled: Boolean = false) {
        remoteScroll.end(vx / density * gesturePref.swipeSensitivity,
                vy / density * gesturePref.swipeSensitivity * if (gesturePref.invertVerticalScrolling) -1 else 1,
                cancelled)
    }

    fun onTap1(p: PointF) = config.tap1Action(p)
    fun onTap2(p: PointF) = config.tap2Action(p)
    fun onTap3(p: PointF) = config.tap3Action(p)
    fun onDoubleTap(p: PointF) = config.doubleTapAction(p)
    fun onLongPress(p: PointF) = config.longPressAction(p)

    fun onSwipe1(sp: PointF, cp: PointF, dx: Float, dy: Float) = config.swipe1Action(sp, cp, dx, dy)
    fun onSwipe2(sp: PointF, cp: PointF, dx: Float, dy: Float) = config.swipe2Action(sp, cp, dx, dy)
    fun onSwipe3(sp: PointF, cp: PointF, dx: Float, dy: Float) = config.swipe3Action(sp, cp, dx, dy)
    fun onDoubleTapSwipe(sp: PointF, cp: PointF, dx: Float, dy: Float) = config.doubleTapSwipeAction(sp, cp, dx, dy)
    fun onLongPressSwipe(sp: PointF, cp: PointF, dx: Float, dy: Float) {
        if (drag.active) drag.move(cp, dx, dy)
        else config.longPressSwipeAction(sp, cp, dx, dy)
    }

    fun onScale(scaleFactor: Float, fx: Float, fy: Float) { stopRemoteScroll(); doScale(scaleFactor, fx, fy) }
    fun onFling(vx: Float, vy: Float) { if (!drag.active) config.flingAction(vx, vy) }

    fun onMouseButtonDown(button: PointerButton, p: PointF) = directMode.doButtonDown(button, p)
    fun onMouseButtonUp(button: PointerButton, p: PointF) = directMode.doButtonUp(button, p)
    fun onMouseMove(p: PointF) = directMode.doMovePointer(p, 0f, 0f)
    fun onMouseScroll(p: PointF, hs: Float, vs: Float) = directMode.doRemoteScrollFromMouse(p, hs, vs)
    fun onMouseBack(p: PointF) = config.mouseBackAction(p)

    fun onCapturedMouseButtonDown(button: PointerButton) = relativeMode.doButtonDown(button, PointF())
    fun onCapturedMouseButtonUp(button: PointerButton) = relativeMode.doButtonUp(button, PointF())
    fun onCapturedMouseMove(dx: Float, dy: Float) = relativeMode.doMovePointer(dx, dy, false)
    fun onCapturedMouseScroll(hs: Float, vs: Float) = relativeMode.doRemoteScrollFromMouse(PointF(), hs, vs)

    // App-owned touchpad uses its own relative state, independent of screen gesture style.
    fun onVirtualPointerStart() {
        drag.finish()
        stopRemoteScroll()
        stopFrameFling()
        virtualMode.onGestureStart()
    }
    fun onVirtualPointerMove(dx: Float, dy: Float) =
            virtualMode.doMovePointer(dx * TOUCHPAD_POINTER_GAIN, dy * TOUCHPAD_POINTER_GAIN, false)
    fun onVirtualPointerButton(button: PointerButton, down: Boolean) {
        if (down) virtualMode.doButtonDown(button, PointF())
        else virtualMode.doButtonUp(button, PointF())
    }
    fun onVirtualPointerClick(button: PointerButton = PointerButton.Left, count: Int = 1) {
        stopRemoteScroll()
        repeat(count.coerceIn(1, 2)) { virtualMode.doClick(button, PointF()) }
    }
    fun onVirtualPointerScroll(dx: Float, dy: Float) =
            scroll(virtualMode, PointF(), dx * gesturePref.swipeSensitivity, dy * gesturePref.swipeSensitivity)
    fun onVirtualPointerScrollEnd(vx: Float, vy: Float, cancelled: Boolean = false) =
            onRemoteScrollEnd(vx * density, vy * density, cancelled)
    fun onVirtualPointerStop(cancelled: Boolean = false) {
        if (cancelled) stopRemoteScroll()
        else remoteScroll.end(0f, 0f, false)
        scrollMode = null
        virtualMode.resetScroll()
        releasePointerButtons()
    }

    private fun releasePointerButtons() {
        viewModel.client?.let { client ->
            client.ignorePointerMovesByServer = false
            viewModel.messenger?.sendPointerButtonRelease(PointF(client.pointerX.toFloat(), client.pointerY.toFloat()))
        }
    }

    fun cancelInput() {
        drag.finish()
        stopRemoteScroll()
        directMode.resetScroll()
        relativeMode.resetScroll()
        virtualMode.resetScroll()
        releasePointerButtons()
        viewModel.frameScroller.stop()
    }

    fun onStylusTap(p: PointF) = directMode.doClick(PointerButton.Left, p)
    fun onStylusDoubleTap(p: PointF) = directMode.doDoubleClick(PointerButton.Left, p)
    fun onStylusLongPress(p: PointF) = directMode.doClick(PointerButton.Right, p)
    fun onStylusScroll(p: PointF) = directMode.doButtonDown(PointerButton.Left, p)

    fun onXKey(keySym: Int, xtCode: Int, isDown: Boolean): Boolean {
        return viewModel.messenger?.sendKey(keySym, xtCode, isDown) ?: false
    }

    /**************************************************************************
     * Available actions
     **************************************************************************/

    private fun doOpenKeyboard() = activity.showKeyboard()
    private fun doScale(scaleFactor: Float, fx: Float, fy: Float) = viewModel.updateZoom(scaleFactor, fx, fy)
    private fun doPan(dx: Float, dy: Float) = viewModel.panFrame(dx, dy)
    private fun startFrameFling(vx: Float, vy: Float) = viewModel.frameScroller.fling(vx, vy)
    private fun stopFrameFling() = viewModel.frameScroller.stop()

    private fun doRemoteBackPress() {
        // RFB doesn't support pressing Back mouse button
        // So we press the Back key on keyboard
        onXKey(XKeySym.XF86XK_Back, 0, true)
        onXKey(XKeySym.XF86XK_Back, 0, false)
    }
}
