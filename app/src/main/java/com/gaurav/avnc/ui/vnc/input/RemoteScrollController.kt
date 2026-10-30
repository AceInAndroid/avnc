/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.model.RemoteInputConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/** One remote wheel session, anchored in framebuffer coordinates and driven at display rate. */
internal class RemoteScrollController(
    private val view: View,
    private val enabled: () -> Boolean,
    private val interactive: () -> Boolean,
    private val emit: (PointerButton, PointF) -> Unit,
    private val settings: () -> RemoteInputConfig = { RemoteInputConfig(axisLock = false) },
) : Runnable {
    private companion object {
        // Shared by touchscreen gestures, touchpad gestures and its scroll strip.
        const val DP_PER_TICK = 8f
    }
    private val minimumVelocity = ViewConfiguration.get(view.context).scaledMinimumFlingVelocity /
            view.resources.displayMetrics.density
    private var anchor: PointF? = null
    private var pendingX = 0f
    private var pendingY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var ending = false
    private var scheduled = false
    private var lastTime = 0L
    private var inertiaStart = 0L
    private var tickBudget = 4f
    private var config = RemoteInputConfig()
    private var axis = 0 // 0 undecided, 1 horizontal, 2 vertical, 3 free diagonal
    private var travelX = 0f
    private var travelY = 0f
    private val directionSlop = ViewConfiguration.get(view.context).scaledTouchSlop /
            view.resources.displayMetrics.density

    fun begin(point: PointF) {
        cancel()
        config = settings()
        axis = if (config.axisLock) 0 else 3
        anchor = PointF(point.x, point.y)
        lastTime = SystemClock.uptimeMillis()
    }

    fun scroll(dx: Float, dy: Float) {
        if (anchor == null || ending) return
        travelX += dx; travelY += dy
        if (axis == 0 && hypot(travelX, travelY) >= directionSlop) {
            axis = when {
                abs(travelX) >= 2 * abs(travelY) -> 1
                abs(travelY) >= 2 * abs(travelX) -> 2
                else -> 3
            }
        }
        pendingX += dx * config.effectiveHorizontalSpeed * if (config.invertHorizontal) -1 else 1
        pendingY += dy * config.scrollSpeed * if (config.invertVertical) -1 else 1
        if (axis == 1) pendingY = 0f
        if (axis == 2) pendingX = 0f
        schedule()
    }

    fun end(vx: Float, vy: Float, cancelled: Boolean) {
        if (cancelled) { cancel(); return }
        if (anchor == null || ending) return
        ending = true
        if (enabled() && config.inertia != "off" && axis != 0 && hypot(vx, vy) >= minimumVelocity) {
            velocityX = if (axis == 2) 0f else vx * config.effectiveHorizontalSpeed * if (config.invertHorizontal) -1 else 1
            velocityY = if (axis == 1) 0f else vy * config.scrollSpeed * if (config.invertVertical) -1 else 1
            inertiaStart = SystemClock.uptimeMillis()
            lastTime = inertiaStart
        }
        schedule()
    }

    fun cancel() {
        view.removeCallbacks(this)
        scheduled = false
        anchor = null
        pendingX = 0f; pendingY = 0f
        velocityX = 0f; velocityY = 0f
        ending = false
        inertiaStart = 0L
        tickBudget = 4f
        axis = 0; travelX = 0f; travelY = 0f
    }

    private fun schedule() {
        if (!scheduled) { scheduled = true; view.postOnAnimation(this) }
    }

    override fun run() {
        scheduled = false
        val point = anchor ?: return
        if (!interactive()) { cancel(); return }
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - lastTime).coerceAtLeast(0)
        lastTime = now
        // Never accumulate delayed animation time into a large burst after a stalled UI frame.
        val dt = min(elapsed, 50L) / 1000f
        tickBudget = min(4f, tickBudget + elapsed * .120f)
        if (inertiaStart != 0L) {
            val decay = exp(-dt / config.inertiaDecay)
            pendingX += velocityX * config.inertiaDecay * (1 - decay)
            pendingY += velocityY * config.inertiaDecay * (1 - decay)
            velocityX *= decay; velocityY *= decay
            if (now - inertiaStart >= config.inertiaDuration || hypot(velocityX, velocityY) < minimumVelocity) {
                velocityX = 0f; velocityY = 0f; inertiaStart = 0L
            }
        }
        if (axis == 0) {
            if (ending) cancel()
            return
        }
        var ticksX = (pendingX / DP_PER_TICK).toInt()
        var ticksY = (pendingY / DP_PER_TICK).toInt()
        // Retain fractions only. Excess whole ticks are deliberately dropped, never queued.
        pendingX %= DP_PER_TICK; pendingY %= DP_PER_TICK
        var count = min(4, tickBudget.toInt())
        var horizontal = abs(ticksX) >= abs(ticksY)
        while (count > 0 && (ticksX != 0 || ticksY != 0)) {
            if ((horizontal && ticksX != 0) || ticksY == 0) {
                emit(if (ticksX > 0) PointerButton.WheelLeft else PointerButton.WheelRight, point)
                ticksX += if (ticksX > 0) -1 else 1
            } else {
                emit(if (ticksY > 0) PointerButton.WheelUp else PointerButton.WheelDown, point)
                ticksY += if (ticksY > 0) -1 else 1
            }
            horizontal = !horizontal
            tickBudget -= 1f
            count--
        }
        if (ending && inertiaStart == 0L) cancel()
        else if (inertiaStart != 0L) schedule()
    }
}
