/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

/** Owns the rest of a touch stream after the third finger arrives, even after cancellation. */
internal class ThreeFingerGesture(
    density: Float,
    touchSlop: Float,
    private val onCapture: () -> Unit,
    private val onAction: (String) -> Unit,
) {
    private val threshold = max(32f * density, 2f * touchSlop)
    private val starts = linkedMapOf<Int, PointF>()
    private var captured = false
    private var finished = false

    /** Used by lifecycle/input cancellation: remaining fingers cannot become a new gesture. */
    fun cancel() {
        finished = true
        starts.clear()
    }

    fun onTouchEvent(event: MotionEvent, enabled: Boolean, blocked: Boolean): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            captured = false
            finished = false
            starts.clear()
            return false
        }
        if (!captured) {
            if (!enabled || event.actionMasked != MotionEvent.ACTION_POINTER_DOWN || event.pointerCount < 3)
                return false
            captured = true
            finished = blocked || event.pointerCount != 3
            if (!finished) repeat(3) { index ->
                starts[event.getPointerId(index)] = PointF(event.getX(index), event.getY(index))
            }
            onCapture()
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                captured = false
                finished = false
                starts.clear()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> finished = true
            MotionEvent.ACTION_MOVE -> if (!finished) {
                if (event.pointerCount != 3) {
                    finished = true
                    return true
                }
                var dx = 0f
                var dy = 0f
                for ((id, start) in starts) {
                    val index = event.findPointerIndex(id)
                    if (index < 0) { finished = true; return true }
                    dx += event.getX(index) - start.x
                    dy += event.getY(index) - start.y
                }
                dx /= 3
                dy /= 3
                val direction = when {
                    abs(dx) >= threshold && abs(dx) >= abs(dy) * 1.5f -> if (dx < 0) "left" else "right"
                    abs(dy) >= threshold && abs(dy) >= abs(dx) * 1.5f -> if (dy < 0) "up" else "down"
                    else -> null
                }
                if (direction != null) {
                    finished = true
                    onAction(direction)
                }
            }
        }
        return true
    }
}
