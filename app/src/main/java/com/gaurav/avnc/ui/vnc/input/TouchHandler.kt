/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.input

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import com.gaurav.avnc.util.AppPreferences
import com.gaurav.avnc.vnc.PointerButton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Handler for touch events. It detects various gestures and notifies [dispatcher].
 */
class TouchHandler(private val inputView: View, private val dispatcher: Dispatcher, private val pref: AppPreferences)
    : ScaleGestureDetector.OnScaleGestureListener {

    //Extension to easily access touch position
    private fun MotionEvent.point() = PointF(x, y)

    /****************************************************************************************
     * Touch Event receivers
     *
     * Note: On some devices, 'Source' property for Stylus events is set to both
     * [InputDevice.SOURCE_STYLUS] & [InputDevice.SOURCE_MOUSE]. Hence, we should
     * pass the events to [handleStylusEvent] before [handleMouseEvent].
     ****************************************************************************************/

    private var gestureActive = false
    private val lastTouchPoint = PointF()
    private var suppressUntilDown = false
    private var ownedDrag = false
    private var fingerMotionCommitted = false

    /** Cancel detectors as well as their delayed tap/long-press callbacks. */
    fun cancel() {
        suppressUntilDown = true
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL,
                                       lastTouchPoint.x, lastTouchPoint.y, 0)
        try {
            stylusGestureDetector.onTouchEvent(event)
            handleGestureEvent(event)
            if (gestureActive)
                handleGestureStartStop(event)
        } finally {
            event.recycle()
        }
        threeFinger.cancel()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchPoint.set(event.x, event.y)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressUntilDown = false
            ownedDrag = false
            fingerMotionCommitted = false
            handleGestureStartStop(event)
        } else if (suppressUntilDown) return true
        if (ownedDrag && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancel()
            return true
        }
        val handled = handleStylusEvent(event) || handleMouseEvent(event) || handleGestureEvent(event)
        if (event.actionMasked != MotionEvent.ACTION_DOWN) handleGestureStartStop(event)
        return handled
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return onHoverEvent(event) || handleStylusEvent(event) || handleMouseEvent(event)
    }

    fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        return handleCapturedPointerEvent(event)
    }

    fun onHoverEvent(event: MotionEvent): Boolean {
        dispatcher.stopRemoteScroll()
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            lastHoverPoint = event.point()
            dispatcher.onMouseMove(lastHoverPoint)
            return true
        }
        return false
    }

    private fun handleGestureStartStop(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                dispatcher.onGestureStart()
            }
            MotionEvent.ACTION_UP -> {
                gestureActive = false
                dispatcher.onGestureStop(event.point())
                ownedDrag = false
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                dispatcher.onGestureCancel()
                ownedDrag = false
            }
        }
    }

    // Used for back-press interception
    private var lastHoverPoint = PointF()
    fun onMouseBack() {
        dispatcher.onMouseBack(lastHoverPoint)
    }

    /****************************************************************************************
     * Mouse
     ****************************************************************************************/
    private val mousePassthrough = pref.input.mousePassthrough

    private fun handleMouseEvent(e: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < 23 || !mousePassthrough || !e.isFromSource(InputDevice.SOURCE_MOUSE))
            return false

        dispatcher.stopRemoteScroll()
        val p = e.point()

        when (e.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> dispatcher.onMouseButtonDown(convertButton(e.actionButton), p)
            MotionEvent.ACTION_BUTTON_RELEASE -> dispatcher.onMouseButtonUp(convertButton(e.actionButton), p)
            MotionEvent.ACTION_MOVE -> dispatcher.onMouseMove(p)

            MotionEvent.ACTION_SCROLL -> {
                val hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                dispatcher.onMouseScroll(p, hs, vs)
            }
        }

        // Allow touchpad gestures to be passed on to GestureDetector
        return !(e.buttonState == 0 && e.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE)
    }

    private fun handleCapturedPointerEvent(e: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < 26)
            return false

        dispatcher.stopRemoteScroll()
        val screenDensity = inputView.context.resources.displayMetrics.density
        val dx: Float
        val dy: Float

        if (e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            dx = getTotalAxisValue(e, MotionEvent.AXIS_X) * screenDensity
            dy = getTotalAxisValue(e, MotionEvent.AXIS_Y) * screenDensity
        } else if (e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            dx = getTotalAxisValue(e, MotionEvent.AXIS_RELATIVE_X)
            dy = getTotalAxisValue(e, MotionEvent.AXIS_RELATIVE_Y)
        } else
            return false

        when (e.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> dispatcher.onCapturedMouseButtonDown(convertButton(e.actionButton))
            MotionEvent.ACTION_BUTTON_RELEASE -> dispatcher.onCapturedMouseButtonUp(convertButton(e.actionButton))
            MotionEvent.ACTION_MOVE -> dispatcher.onCapturedMouseMove(dx, dy)
            MotionEvent.ACTION_SCROLL -> {
                val hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                dispatcher.onCapturedMouseScroll(hs, vs)
            }
            else -> return false
        }
        return true
    }

    /**
     * Convert from [MotionEvent] button to [PointerButton]
     */
    private fun convertButton(button: Int) = when (button) {
        MotionEvent.BUTTON_PRIMARY -> PointerButton.Left
        MotionEvent.BUTTON_SECONDARY -> PointerButton.Right
        MotionEvent.BUTTON_TERTIARY -> PointerButton.Middle
        else -> PointerButton.None
    }

    /**
     * Returns value of given axis, added with historical values of the axis
     */
    private fun getTotalAxisValue(e: MotionEvent, axis: Int): Float {
        var v = e.getAxisValue(axis)
        repeat(e.historySize) {
            v += e.getHistoricalAxisValue(axis, it)
        }
        return v
    }


    /****************************************************************************************
     * Stylus
     ****************************************************************************************/
    private val stylusGestureDetector = GestureDetector(inputView.context, StylusGestureListener())

    private fun handleStylusEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_STYLUS) &&
            event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            stylusGestureDetector.onTouchEvent(event)
            return true
        }
        return false
    }

    inner class StylusGestureListener : SimpleOnGestureListener() {
        private var scrolling = false

        override fun onDown(e: MotionEvent): Boolean {
            scrolling = false
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            dispatcher.onStylusTap(e.point())
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            dispatcher.onStylusDoubleTap(e.point())
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            inputView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dispatcher.onStylusLongPress(e.point())
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // Scrolling with stylus button pressed is currently used for scale gesture
            if (e1 != null && e2.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY == 0) {

                // When scrolling starts, we need to send the first event at initial touch-point.
                // Otherwise, we will loose the small distance (touch-slope) required by onScroll().
                if (!scrolling) {
                    scrolling = true
                    dispatcher.onStylusScroll(e1.point())
                }
                dispatcher.onStylusScroll(e2.point())
            }
            return true
        }
    }


    /****************************************************************************************
     * Finger Gestures (and everything else beside mouse & stylus)
     ****************************************************************************************/
    private val scaleDetector = ScaleGestureDetector(inputView.context, this).apply { isQuickScaleEnabled = false }
    private val gestureDetector = GestureDetectorEx(inputView.context, FingerGestureListener(), pref)
    private val swipeVsScale = SwipeVsScale()
    private val longPressSwipeEnabled = pref.input.gesture.longPressSwipeEnabled
    private val twoFingerScroll = TwoFingerScroll()
    private val threeFinger = ThreeFingerGesture(inputView.resources.displayMetrics.density,
            ViewConfiguration.get(inputView.context).scaledTouchSlop.toFloat(),
            onCapture = {
                cancelFingerDetectors()
                dispatcher.onGestureCancel()
            },
            onAction = { dispatcher.onThreeFingerAction(it) })

    private fun cancelFingerDetectors() {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, lastTouchPoint.x, lastTouchPoint.y, 0).let {
            twoFingerScroll.onTouchEvent(it)
            swipeVsScale.onTouchEvent(it)
            scaleDetector.onTouchEvent(it)
            gestureDetector.onTouchEvent(it)
            it.recycle()
        }
    }

    private fun handleGestureEvent(event: MotionEvent): Boolean {
        if (threeFinger.onTouchEvent(event, dispatcher.systemGesturesEnabled, fingerMotionCommitted || ownedDrag))
            return true
        dispatcher.accelerator.addMovement(event)
        twoFingerScroll.onTouchEvent(event)
        swipeVsScale.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        return gestureDetector.onTouchEvent(event)
    }

    override fun onScaleBegin(detector: ScaleGestureDetector) = true
    override fun onScaleEnd(detector: ScaleGestureDetector) {}
    override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (swipeVsScale.shouldScale()) {
            fingerMotionCommitted = true
            dispatcher.onScale(detector.scaleFactor, detector.focusX, detector.focusY)
        }
        return true
    }

    /** Stable IDs and a synthetic centroid velocity stream, independent of pointer lift order. */
    private inner class TwoFingerScroll {
        private var id1 = -1
        private var id2 = -1
        private var tracker: VelocityTracker? = null
        private val center = PointF()
        private val consumed = PointF()
        private var started = false
        private var downTime = 0L

        fun consumeDelta(): PointF {
            if (tracker == null) return PointF()
            started = true
            val delta = PointF(center.x - consumed.x, center.y - consumed.y)
            consumed.set(center)
            return delta
        }

        fun onTouchEvent(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL -> {
                    tracker?.recycle(); tracker = null; started = false
                    if (e.actionMasked == MotionEvent.ACTION_CANCEL) dispatcher.onRemoteScrollEnd(0f, 0f, true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount == 2 && tracker == null) {
                        id1 = e.getPointerId(0); id2 = e.getPointerId(1)
                        tracker = VelocityTracker.obtain()
                        downTime = e.eventTime
                        center.set((e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2)
                        consumed.set(center)
                        sample(e.eventTime, MotionEvent.ACTION_DOWN)
                    } else {
                        tracker?.recycle(); tracker = null; started = false
                        dispatcher.onRemoteScrollEnd(0f, 0f, true)
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP -> {
                    val i1 = e.findPointerIndex(id1); val i2 = e.findPointerIndex(id2)
                    if (tracker != null && i1 >= 0 && i2 >= 0) {
                        center.set((e.getX(i1) + e.getX(i2)) / 2, (e.getY(i1) + e.getY(i2)) / 2)
                        sample(e.eventTime, MotionEvent.ACTION_MOVE)
                        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                            if (started) {
                                val dx = center.x - consumed.x
                                val dy = center.y - consumed.y
                                if (dx != 0f || dy != 0f)
                                    dispatcher.onSwipe2(center, center, dx, dy)
                                tracker?.computeCurrentVelocity(1000,
                                        ViewConfiguration.get(inputView.context).scaledMaximumFlingVelocity.toFloat())
                                dispatcher.onRemoteScrollEnd(tracker?.xVelocity ?: 0f, tracker?.yVelocity ?: 0f)
                            }
                            tracker?.recycle(); tracker = null; started = false
                        }
                    }
                }
                MotionEvent.ACTION_UP -> { tracker?.recycle(); tracker = null; started = false }
            }
        }

        private fun sample(time: Long, action: Int) {
            MotionEvent.obtain(downTime, time, action, center.x, center.y, 0).let {
                tracker?.addMovement(it)
                it.recycle()
            }
        }
    }

    private inner class FingerGestureListener : GestureDetectorEx.GestureListenerEx {

        override fun onSingleTapConfirmed(e: MotionEvent) = dispatcher.onTap1(e.point())
        override fun onDoubleTapConfirmed(e: MotionEvent) = dispatcher.onDoubleTap(e.point())

        override fun onMultiFingerTap(e: MotionEvent, fingerCount: Int) {
            when (fingerCount) {
                2 -> dispatcher.onTap2(e.point())
                3 -> dispatcher.onTap3(e.point())
            }
        }

        override fun onLongPress(e: MotionEvent) {
            inputView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            // If long-press-swipe is disabled, we can dispatch long-press immediately
            if (!longPressSwipeEnabled) {
                dispatcher.onLongPress(e.point())
                ownedDrag = pref.input.gesture.longPress in listOf("left-drag", "left-press")
            }
        }

        override fun onLongPressConfirmed(e: MotionEvent) {
            if (longPressSwipeEnabled) dispatcher.onLongPress(e.point())
        }


        override fun onScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float) {
            if (dx != 0f || dy != 0f) fingerMotionCommitted = true
            val startPoint = e1.point()
            val currentPoint = e2.point()

            when (e2.pointerCount) {
                1 -> {
                    if (pref.input.gesture.swipe1.startsWith("remote-drag")) ownedDrag = true
                    dispatcher.onSwipe1(startPoint, currentPoint, dx, dy)
                }
                2 -> if (swipeVsScale.shouldSwipe()) {
                    if (pref.input.gesture.swipe2 == "remote-scroll") {
                        val delta = twoFingerScroll.consumeDelta()
                        dispatcher.onSwipe2(startPoint, currentPoint, delta.x, delta.y)
                    } else dispatcher.onSwipe2(startPoint, currentPoint, dx, dy)
                }
                3 -> dispatcher.onSwipe3(startPoint, currentPoint, dx, dy)
            }
        }

        override fun onScrollAfterLongPress(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float) {
            fingerMotionCommitted = true
            if (pref.input.gesture.longPressSwipe.startsWith("remote-drag")) ownedDrag = true
            dispatcher.onLongPressSwipe(e1.point(), e2.point(), dx, dy)
        }

        override fun onScrollAfterDoubleTap(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float) {
            fingerMotionCommitted = true
            if (pref.input.gesture.doubleTapSwipe.startsWith("remote-drag")) ownedDrag = true
            dispatcher.onDoubleTapSwipe(e1.point(), e2.point(), dx, dy)
        }

        override fun onFling(velocityX: Float, velocityY: Float) {
            if (!ownedDrag) dispatcher.onFling(velocityX, velocityY)
        }
    }

    /**
     * Stock [GestureDetector] only detects the most common gestures. But we need to
     * detect some more gestures to provide maximum flexibility to the user.
     *
     * [GestureDetectorEx] is used to for this purpose. It internally uses stock
     * [GestureDetector], and some custom event processing to detect more gestures.
     */
    private class GestureDetectorEx(context: Context, val listener: GestureListenerEx, prefs: AppPreferences) {

        /**
         * Detected gestures. Some of these come directly from stock [GestureDetector],
         * while the following are custom detected:
         *
         * [onDoubleTapConfirmed]
         * To support double-tap-swipe gesture, double-tap is not immediately triggered on
         * ACTION_DOWN of second tap. Instead, [doubleTapDetected] flag is set, and when
         * final ACTION_UP is received (within timeout), [onDoubleTapConfirmed] is called.
         *
         * [onMultiFingerTap]
         * Maximum number of fingers that went down is tracked in [maxFingerDown]. If
         * ACTION_UP is received within a timeout, and more than one finger went down
         * without any scrolling, [onMultiFingerTap] is called.
         *
         * [onLongPressConfirmed]
         * Similar to [onDoubleTapConfirmed], to support long-press-swipe, we wait for
         * ACTION_UP to confirm long-press.
         * Note: [onLongPress] is always called immediately. It enables haptic feedback
         * and supports cases where waiting for [onLongPressConfirmed] is not necessary.
         *
         * [onScrollAfterDoubleTap]
         * This is the double-tap-swipe gesture. If scrolling after [doubleTapDetected] flag
         * is set, [onScrollAfterDoubleTap] is called.
         *
         * [onScrollAfterLongPress]
         * This is the long-press-swipe gesture. If scrolling after [longPressDetected] flag
         * is set, [onScrollAfterLongPress] is called.
         *
         * Quick Tap
         * ---------
         * Usually, we have to wait ~300ms inside [GestureDetector] to rule out double-tap
         * before [onSingleTapConfirmed] can be called. But in some scenarios this delay
         * can be avoided. When Quick Tap is enabled ([quickTap1Enabled]), [onSingleTapConfirmed]
         * can be called immediately from [GestureDetector.OnGestureListener.onSingleTapUp].
         *
         * When Quick Tap is enabled, [onSingleTapConfirmed] is called twice instead of
         * [onDoubleTapConfirmed] for double-tap gestures.
         */
        interface GestureListenerEx {
            fun onSingleTapConfirmed(e: MotionEvent)
            fun onDoubleTapConfirmed(e: MotionEvent)
            fun onMultiFingerTap(e: MotionEvent, fingerCount: Int)

            fun onLongPress(e: MotionEvent)
            fun onLongPressConfirmed(e: MotionEvent)

            fun onScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float)
            fun onScrollAfterLongPress(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float)
            fun onScrollAfterDoubleTap(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float)

            fun onFling(velocityX: Float, velocityY: Float)
        }


        /**
         * Stock [GestureDetector] has two unwanted behaviours:
         * - If long-press or double-tap is detected, scroll events will not be reported anymore.
         * - If you don't lift the finger after double-tap, a long-press will be triggered.
         *
         * Fortunately, [GestureDetector] lets us disable long-press detection, which allows us
         * to use a combination of multiple [GestureDetector]s to overcome the restrictions:
         *
         * -                                 +------------------+
         * -                              +->| [innerDetector1] |
         * -                              |  +------------------+
         * -                              |   (tap, long-press)
         * -                              |
         * -                              |
         * -   +----------------+  event  |  +------------------+  double-tap event   +------------------+
         * -   | [onTouchEvent] |---------+->| [innerDetector2] |-------------------->| [innerDetector3] |
         * -   +----------------+         |  +------------------+                     +------------------+
         * -                              |      (double-tap)                          (double-tap-swipe)
         * -                              |
         * -                              |
         * -                              |   +------------------+
         * -                              +-> | [innerDetector4] |
         *                                    +------------------+
         *                                        (quick-tap)
         *
         */
        private val innerDetector1 = GestureDetector(context, InnerListener1())
        private val innerDetector2 = GestureDetector(context, InnerListener2()).apply { setIsLongpressEnabled(false) }
        private val innerDetector3 = GestureDetector(context, InnerListener3()).apply { setIsLongpressEnabled(false) }
        private val innerDetector4 = GestureDetector(context, InnerListener4()).apply { setOnDoubleTapListener(null) }

        private val quickTap1Enabled = prefs.input.gesture.quickTap1Enabled
        private val longPressEnabled = prefs.input.gesture.longPressDetectionEnabled
        private var longPressDetected = false
        private var doubleTapDetected = false
        private var scrolling = false
        private var maxFingerDown = 0
        private var currentDownEvent: MotionEvent? = null
        private val multiFingerStarts = mutableMapOf<Int, PointF>()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var multiFingerMoved = false
        private var multiFingerEnding = false


        private inner class InnerListener1 : SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!quickTap1Enabled && maxFingerDown <= 1)
                    listener.onSingleTapConfirmed(e)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!longPressEnabled || maxFingerDown > 1)
                    return

                if (doubleTapDetected)
                    return // Ignore long-press triggered during double-tap-swipe

                longPressDetected = true
                listener.onLongPress(e)
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (maxFingerDown <= 1)
                    listener.onFling(velocityX, velocityY)
                return true
            }
        }

        private inner class InnerListener2 : SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                doubleTapDetected = true
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent) = innerDetector3.onTouchEvent(e)

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float) = handleScroll(e1, e2, dx, dy)
        }

        private inner class InnerListener3 : SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float) = handleScroll(e1, e2, dx, dy)
        }

        private inner class InnerListener4 : SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (quickTap1Enabled && maxFingerDown <= 1)
                    listener.onSingleTapConfirmed(e)
                return true
            }
        }

        private fun handleScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            e1 ?: return false
            // A lifted finger must not turn a scroll/pinch into a viewport pan or drag.
            if (multiFingerEnding || (e2.pointerCount == 1 && maxFingerDown > 1))
                return true
            // GestureDetector exits its tap region on POINTER_DOWN and may report onScroll
            // for sub-slop jitter. Use our per-finger threshold before committing to a swipe.
            if (maxFingerDown > 1 && !multiFingerMoved)
                return true
            if (!scrolling) {
                scrolling = true
                // Send first scroll event on initial touch-down point, because GestureDetector
                // requires certain amount of finger movement before scroll is triggered, and
                // we don't want to 'loose' that small movement.
                if (e1.pointerCount == e2.pointerCount)
                    callOnScroll(e1, e1, 0f, 0f)
            }

            callOnScroll(e1, e2, -dx, -dy)
            return true
        }

        private fun callOnScroll(e1: MotionEvent, e2: MotionEvent, dx: Float, dy: Float) {
            if (doubleTapDetected && maxFingerDown <= 1)
                listener.onScrollAfterDoubleTap(e1, e2, dx, dy)
            else if (longPressDetected && maxFingerDown <= 1)
                listener.onScrollAfterLongPress(e1, e2, dx, dy)
            else
                listener.onScroll(e1, e2, dx, dy)
        }

        /**
         * Event receiver
         */
        fun onTouchEvent(e: MotionEvent): Boolean {
            // Update these guards before the detectors invoke their synchronous callbacks.
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    reset()
                    maxFingerDown = 1
                    currentDownEvent = MotionEvent.obtain(e)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxFingerDown = max(maxFingerDown, e.pointerCount)
                    repeat(e.pointerCount) { index ->
                        multiFingerStarts.getOrPut(e.getPointerId(index)) { PointF(e.getX(index), e.getY(index)) }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> multiFingerEnding = true
                MotionEvent.ACTION_MOVE -> {
                    if (maxFingerDown > 1) repeat(e.pointerCount) { index ->
                        multiFingerStarts[e.getPointerId(index)]?.let { start ->
                            if (hypot(e.getX(index) - start.x, e.getY(index) - start.y) > touchSlop)
                                multiFingerMoved = true
                        }
                    }
                }
            }

            innerDetector1.onTouchEvent(e)
            innerDetector2.onTouchEvent(e)
            if (quickTap1Enabled)
                innerDetector4.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_CANCEL)
                innerDetector3.onTouchEvent(e)

            when (e.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    currentDownEvent?.let { downEvent ->
                        if (longPressDetected && !doubleTapDetected && !scrolling && maxFingerDown <= 1)
                            listener.onLongPressConfirmed(downEvent)

                        if (doubleTapDetected && !longPressDetected && !scrolling && !quickTap1Enabled && maxFingerDown <= 1)
                            listener.onDoubleTapConfirmed(downEvent)

                        val gestureDuration = (e.eventTime - downEvent.eventTime)
                        if (maxFingerDown > 1 && !scrolling && !multiFingerMoved && gestureDuration < ViewConfiguration.getDoubleTapTimeout())
                            listener.onMultiFingerTap(downEvent, maxFingerDown)
                    }

                    reset()
                }

                MotionEvent.ACTION_CANCEL -> reset()
            }

            return true
        }

        private fun reset() {
            longPressDetected = false
            doubleTapDetected = false
            scrolling = false
            maxFingerDown = 0
            currentDownEvent?.recycle()
            currentDownEvent = null
            multiFingerStarts.clear()
            multiFingerMoved = false
            multiFingerEnding = false
        }
    }


    /**
     * Swipe vs Scale detector.
     *
     * Many two-finger gestures are detected as both swipe & scale gestures because
     * [GestureDetector] & [ScaleGestureDetector] work independently. This works
     * very well when two-finger swipe pref is set to 'pan'.
     * But when the pref is set to 'remote-scroll', this independent detection
     * becomes an issue. When user tries to scale, it frequently triggers remote
     * scrolling. And when user tries to scroll, it triggers scaling.
     *
     * This class tries to clearly differentiate between these two gestures.
     * It works by tracking the fingers, and calculating the angle between two paths.
     * Then [decide] between two gestures by comparing the angle to some thresholds.
     *
     * This class can mis-detect some gestures because fingers don't always move
     * perfectly, but it does provide huge improvement over existing situation.
     */
    private inner class SwipeVsScale {
        private val enabled = pref.input.gesture.swipe2 == "remote-scroll"
        private var detecting = false
        private var scaleDetected = false
        private var swipeDetected = false
        private var finished = false
        private val touchSlop = ViewConfiguration.get(inputView.context).scaledTouchSlop.toFloat()

        private var f1Id = 0 // Finger 1
        private var f2Id = 0 // Finger 2
        private val f1Start = PointF()
        private val f2Start = PointF()
        private val f1Current = PointF()
        private val f2Current = PointF()


        fun onTouchEvent(e: MotionEvent) {
            if (!enabled)
                return

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    detecting = false
                    scaleDetected = false
                    swipeDetected = false
                    finished = false
                }
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> {
                    detecting = false
                    finished = true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    detecting = !finished && e.pointerCount == 2
                    if (detecting) {
                        f1Id = e.getPointerId(0)
                        f2Id = e.getPointerId(1)
                        f1Start.set(e.getX(0), e.getY(0))
                        f2Start.set(e.getX(1), e.getY(1))
                        f1Current.set(f1Start)
                        f2Current.set(f2Start)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (detecting) {
                        val i1 = e.findPointerIndex(f1Id)
                        val i2 = e.findPointerIndex(f2Id)
                        if (i1 != -1 && i2 != -1) {
                            f1Current.set(e.getX(i1), e.getY(i1))
                            f2Current.set(e.getX(i2), e.getY(i2))
                        }
                    }
                }
            }
        }

        fun shouldScale(): Boolean {
            decide()
            return !enabled || (detecting && scaleDetected)
        }

        fun shouldSwipe(): Boolean {
            decide()
            return !enabled || (detecting && swipeDetected)
        }

        /**
         * Decides if gesture can be considered a swipe/scale
         */
        private fun decide() {
            if (!detecting || scaleDetected || swipeDetected)
                return

            val d1 = hypot(f1Current.x - f1Start.x, f1Current.y - f1Start.y)
            val d2 = hypot(f2Current.x - f2Start.x, f2Current.y - f2Start.y)
            if (d1 < touchSlop || d2 < touchSlop) {
                // An anchored-finger pinch has no meaningful angle for its stationary finger.
                val startSpan = hypot(f2Start.x - f1Start.x, f2Start.y - f1Start.y)
                val span = hypot(f2Current.x - f1Current.x, f2Current.y - f1Current.y)
                if (max(d1, d2) >= touchSlop * 2 && abs(span - startSpan) >= touchSlop)
                    scaleDetected = true
                return
            }

            val t1 = theta(f1Start, f1Current)
            val t2 = theta(f2Start, f2Current)
            val rawDiff = abs(t1 - t2)
            val diff = min(rawDiff, 360 - rawDiff)

            // Latch a decision so normal finger drift cannot switch scroll into pinch.
            scaleDetected = diff > 45
            swipeDetected = diff < 30
        }

        /**
         * Returns the angle made by line [p1]->[p2] with the positive x-axis.
         * Returned angle will be in range [0, 360]
         */
        private fun theta(p1: PointF, p2: PointF): Double {
            val theta = atan2(p2.y - p1.y, p2.x - p1.x)
            val degree = (theta / PI) * 180
            return (degree + 360) % 360 // Map [-180, 180] to [0, 360]
        }
    }
}
