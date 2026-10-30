/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import com.gaurav.avnc.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.PointerButton
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/** Owns a held mouse button, including stationary-finger edge pan in direct mode. */
internal class PointerDragController(private val view: View, private val model: VncViewModel) : Runnable {
    private var mode: BasePointerMode? = null
    private var button = PointerButton.Left
    private val point = PointF()
    private var lastRemote: PointF? = null
    private var lastTime = 0L
    private var scheduled = false
    private var feedback: GradientDrawable? = null
    private val hideFeedback = Runnable { feedback?.let { view.overlay.remove(it) }; feedback = null }
    val active get() = mode != null
    private val interactive get() = model.connected &&
            model.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT && model.inPiPMode.value != true

    fun begin(pointerMode: BasePointerMode, heldButton: PointerButton, start: PointF) {
        finish()
        if (!interactive) return
        val remote = pointerMode.transformPoint(start) ?: return
        mode = pointerMode
        button = heldButton
        point.set(start)
        lastRemote = PointF(remote.x, remote.y)
        pointerMode.doButtonDown(button, start)
        lastTime = SystemClock.uptimeMillis()
        showFeedback(start)
        schedule()
    }

    fun move(current: PointF, dx: Float, dy: Float) {
        val pointerMode = mode ?: return
        if (!interactive) { finish(); return }
        point.set(current)
        if (pointerMode is DirectPointerMode) sendDirect()
        else {
            pointerMode.doRemoteDrag(button, current, dx, dy)
            pointerMode.transformPoint(current)?.let { lastRemote = PointF(it.x, it.y) }
        }
        schedule()
    }

    fun finish() {
        view.removeCallbacks(this)
        view.removeCallbacks(hideFeedback)
        hideFeedback.run()
        scheduled = false
        if (mode != null) lastRemote?.let { model.messenger?.sendPointerButtonUp(button, it) }
        mode = null
        lastRemote = null
    }

    private fun sendDirect() {
        val fs = model.frameState
        if (fs.fbWidth < 1 || fs.fbHeight < 1) return
        val remote = fs.toFbUnchecked(point).apply {
            x = x.coerceIn(fs.contentRect.left, fs.contentRect.right - 1)
            y = y.coerceIn(fs.contentRect.top, fs.contentRect.bottom - 1)
        }
        lastRemote = remote
        model.messenger?.sendPointerButtonDown(button, remote)
    }

    private fun schedule() {
        if (mode is DirectPointerMode && !scheduled) {
            scheduled = true
            view.postOnAnimation(this)
        }
    }

    override fun run() {
        scheduled = false
        if (mode !is DirectPointerMode) return
        if (!interactive) { finish(); return }
        val fs = model.frameState
        val safe = fs.safeArea
        val now = SystemClock.uptimeMillis()
        val dt = min((now - lastTime).coerceAtLeast(0), 50L) / 1000f
        lastTime = now
        val density = view.resources.displayMetrics.density
        fun speed(p: Float, low: Float, high: Float, content: Float): Float {
            if (content <= high - low) return 0f
            val band = min(32f * density, (high - low) / 2f)
            if (band <= 0) return 0f
            return when {
                p < low + band -> ((low + band - p) / band).coerceIn(0f, 1f) * 480f * density
                p > high - band -> -((p - high + band) / band).coerceIn(0f, 1f) * 480f * density
                else -> 0f
            }
        }
        val dx = speed(point.x, safe.left, safe.right, fs.contentRect.width() * fs.scale) * dt
        val dy = speed(point.y, safe.top, safe.bottom, fs.contentRect.height() * fs.scale) * dt
        if (dx != 0f || dy != 0f) {
            val oldX = fs.frameX; val oldY = fs.frameY
            model.panFrame(dx, dy)
            if (oldX != fs.frameX || oldY != fs.frameY) sendDirect()
        }
        schedule()
    }

    private fun showFeedback(start: PointF) {
        val radius = (20 * view.resources.displayMetrics.density).toInt()
        val color = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        feedback = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((color and 0xffffff) or 0x22000000)
            setStroke((2 * view.resources.displayMetrics.density).toInt(), color)
            setBounds(start.x.toInt() - radius, start.y.toInt() - radius,
                      start.x.toInt() + radius, start.y.toInt() + radius)
            view.overlay.add(this)
        }
        @Suppress("DEPRECATION")
        view.announceForAccessibility(view.context.getString(R.string.mouse_drag_active))
        view.postDelayed(hideFeedback, 500)
    }
}
