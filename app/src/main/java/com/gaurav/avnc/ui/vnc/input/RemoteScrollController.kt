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
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
) : Runnable {
    private companion object {
        // Shared by touchscreen gestures, touchpad gestures and its scroll strip.
        const val DP_PER_TICK = 2f
        const val FRAME_TICKS = 16f
        const val TICKS_PER_MS = .480f
        const val BACKLOG_TICKS = 48f
        const val DRAIN_MS = 100L
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
    private var tickBudget = FRAME_TICKS
    private var drainDeadline = 0L
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
        lastTime = nowMillis()
    }

    fun scroll(dx: Float, dy: Float) {
        if (anchor == null || ending || !dx.isFinite() || !dy.isFinite()) return
        val deltaX = dx.coerceIn(-10_000f, 10_000f)
        val deltaY = dy.coerceIn(-10_000f, 10_000f)
        travelX += deltaX; travelY += deltaY
        if (axis == 0 && hypot(travelX, travelY) >= directionSlop) {
            axis = when {
                abs(travelX) >= 2 * abs(travelY) -> 1
                abs(travelY) >= 2 * abs(travelX) -> 2
                else -> 3
            }
        }
        addMotion(deltaX * config.effectiveHorizontalSpeed * if (config.invertHorizontal) -1 else 1,
                deltaY * config.scrollSpeed * if (config.invertVertical) -1 else 1, reverse = true)
        if (axis == 1) pendingY = 0f
        if (axis == 2) pendingX = 0f
        schedule()
    }

    fun end(vx: Float, vy: Float, cancelled: Boolean) {
        if (cancelled) { cancel(); return }
        if (anchor == null || ending) return
        ending = true
        drainDeadline = nowMillis() + DRAIN_MS
        if (enabled() && config.inertia != "off" && axis != 0 && vx.isFinite() && vy.isFinite() && hypot(vx, vy) >= minimumVelocity) {
            velocityX = if (axis == 2) 0f else vx * config.effectiveHorizontalSpeed * if (config.invertHorizontal) -1 else 1
            velocityY = if (axis == 1) 0f else vy * config.scrollSpeed * if (config.invertVertical) -1 else 1
            inertiaStart = nowMillis()
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
        tickBudget = FRAME_TICKS
        drainDeadline = 0L
        axis = 0; travelX = 0f; travelY = 0f
    }

    private fun addMotion(dx: Float, dy: Float, reverse: Boolean = false) {
        // A reversal must not first play back movement left over from the old direction.
        if (reverse && pendingX * dx < 0f) pendingX = 0f
        if (reverse && pendingY * dy < 0f) pendingY = 0f
        pendingX += dx; pendingY += dy
        val total = abs(pendingX) + abs(pendingY)
        val limit = BACKLOG_TICKS * DP_PER_TICK
        if (total > limit) {
            pendingX *= limit / total
            pendingY *= limit / total
        }
    }

    private fun hasWholeTicks() = abs(pendingX) >= DP_PER_TICK || abs(pendingY) >= DP_PER_TICK

    private fun schedule() {
        if (!scheduled) { scheduled = true; view.postOnAnimation(this) }
    }

    override fun run() {
        scheduled = false
        val point = anchor ?: return
        if (!interactive()) { cancel(); return }
        val now = nowMillis()
        val elapsed = (now - lastTime).coerceAtLeast(0)
        lastTime = now
        // Never accumulate delayed animation time into a large burst after a stalled UI frame.
        val dt = min(elapsed, 50L) / 1000f
        tickBudget = min(FRAME_TICKS, tickBudget + min(elapsed, 50L) * TICKS_PER_MS)
        if (inertiaStart != 0L) {
            val decay = exp(-dt / config.inertiaDecay)
            addMotion(velocityX * config.inertiaDecay * (1 - decay),
                    velocityY * config.inertiaDecay * (1 - decay))
            velocityX *= decay; velocityY *= decay
            if (now - inertiaStart >= config.inertiaDuration || hypot(velocityX, velocityY) < minimumVelocity) {
                velocityX = 0f; velocityY = 0f; inertiaStart = 0L
                drainDeadline = now + DRAIN_MS
            }
        }
        if (axis == 0) {
            if (ending) cancel()
            return
        }
        if (ending && inertiaStart == 0L && now >= drainDeadline) { cancel(); return }
        var count = min(FRAME_TICKS.toInt(), tickBudget.toInt())
        var horizontal = abs(pendingX) >= abs(pendingY)
        while (count > 0 && hasWholeTicks()) {
            if ((horizontal && abs(pendingX) >= DP_PER_TICK) || abs(pendingY) < DP_PER_TICK) {
                val positive = pendingX > 0
                emit(if (positive) PointerButton.WheelLeft else PointerButton.WheelRight, point)
                pendingX -= if (positive) DP_PER_TICK else -DP_PER_TICK
            } else {
                val positive = pendingY > 0
                emit(if (positive) PointerButton.WheelUp else PointerButton.WheelDown, point)
                pendingY -= if (positive) DP_PER_TICK else -DP_PER_TICK
            }
            horizontal = !horizontal
            tickBudget -= 1f
            count--
        }
        if (ending && inertiaStart == 0L && !hasWholeTicks()) cancel()
        else if (inertiaStart != 0L || hasWholeTicks()) schedule()
    }
}
