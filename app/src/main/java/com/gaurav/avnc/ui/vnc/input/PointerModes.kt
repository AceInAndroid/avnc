/*
 * Copyright (c) 2025  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import com.gaurav.avnc.util.debugCheckNotNull
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.PointerButton
import kotlin.math.abs

/**
 * Most actions have the same implementation in both modes, only difference being
 * the point where event is sent. [transformPoint] is used for this mode-specific
 * point selection.
 */
abstract class BasePointerMode(val viewModel: VncViewModel) {

    //Used for remote scrolling
    private var accumulatedDx = 0F
    private var accumulatedDy = 0F
    private val deltaPerScroll = 20F //For how much dx/dy, one scroll event will be sent

    abstract fun transformPoint(p: PointF): PointF?
    abstract fun doMovePointer(p: PointF, dx: Float, dy: Float)
    abstract fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float)

    open fun onGestureStart() = resetScroll()
    open fun onGestureStop(p: PointF) {
        doButtonRelease(p)
        resetScroll()
    }

    fun resetScroll() {
        accumulatedDx = 0f
        accumulatedDy = 0f
    }

    fun doButtonDown(button: PointerButton, p: PointF) {
        transformPoint(p)?.let { viewModel.messenger?.sendPointerButtonDown(button, it) }
    }

    fun doButtonUp(button: PointerButton, p: PointF) {
        transformPoint(p)?.let { viewModel.messenger?.sendPointerButtonUp(button, it) }
    }

    fun doButtonRelease(p: PointF) {
        transformPoint(p)?.let { viewModel.messenger?.sendPointerButtonRelease(it) }
    }

    open fun doClick(button: PointerButton, p: PointF) {
        doButtonDown(button, p)
        // Some apps (mostly games) seems to ignore click event if button-up is received too early
        if ((button == PointerButton.Left || button == PointerButton.Middle || button == PointerButton.Right)
            && viewModel.profile.fButtonUpDelay)
            viewModel.messenger?.insertButtonUpDelay()
        doButtonUp(button, p)
    }

    fun doDoubleClick(button: PointerButton, p: PointF) {
        doClick(button, p)
        doClick(button, p)
    }

    fun doRemoteScroll(focus: PointF, dx: Float, dy: Float) {
        val config = viewModel.remoteInputConfig
        accumulatedDx += dx * config.effectiveHorizontalSpeed * if (config.invertHorizontal) -1 else 1
        accumulatedDy += dy * config.scrollSpeed * if (config.invertVertical) -1 else 1

        //Drain horizontal change
        while (abs(accumulatedDx) >= deltaPerScroll) {
            if (accumulatedDx > 0) {
                doClick(PointerButton.WheelLeft, focus)
                accumulatedDx -= deltaPerScroll
            } else {
                doClick(PointerButton.WheelRight, focus)
                accumulatedDx += deltaPerScroll
            }
        }

        //Drain vertical change
        while (abs(accumulatedDy) >= deltaPerScroll) {
            if (accumulatedDy > 0) {
                doClick(PointerButton.WheelUp, focus)
                accumulatedDy -= deltaPerScroll
            } else {
                doClick(PointerButton.WheelDown, focus)
                accumulatedDy += deltaPerScroll
            }
        }
    }

    /**
     * [hs] Movement of horizontal scroll wheel
     * [vs] Movement of vertical scroll wheel
     */
    fun doRemoteScrollFromMouse(p: PointF, hs: Float, vs: Float) {
        // hs is -ve for for left and +ve for right. But doRemoteScroll() works
        // in terms of finger movement where -ve is right and +ve is left
        // So we have to invert the sign of hs for doRemoteScroll()
        doRemoteScroll(p, -1 * hs * deltaPerScroll, vs * deltaPerScroll)
    }
}

/**
 * Actions happen at touch-point, which is simply transformed from
 * viewport coordinates into corresponding position in framebuffer.
 */
class DirectPointerMode(viewModel: VncViewModel) : BasePointerMode(viewModel) {
    override fun transformPoint(p: PointF) = viewModel.frameState.toFb(p)
    override fun doMovePointer(p: PointF, dx: Float, dy: Float) = doButtonDown(PointerButton.None, p)
    override fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float) = doButtonDown(button, p)
    override fun doClick(button: PointerButton, p: PointF) {
        if (transformPoint(p) != null)
            super.doClick(button, p)
        else if (button == PointerButton.Left)
            coerceToFbEdge(p)?.let { doMovePointer(it, 0f, 0f) }
    }

    // When user taps outside the frame, move the pointer to edge of the frame
    // It allows opening of taskbar/panels when they are set to auto-hide.
    // It can also be used for previewing taskbar items.
    private fun coerceToFbEdge(p: PointF): PointF? {
        val fs = viewModel.frameState
        if (fs.fbWidth < 1 || fs.fbHeight < 1)
            return null

        return fs.toVP(
                fs.toFbUnchecked(p).apply {
                    x = x.coerceIn(fs.contentRect.left, fs.contentRect.right - 1)
                    y = y.coerceIn(fs.contentRect.top, fs.contentRect.bottom - 1)
                }
        )
    }
}

/**
 * Actions happen at [pointerPosition], which is updated by [doMovePointer].
 */
class RelativePointerMode(viewModel: VncViewModel, private val accelerator: PointerAcceleration) : BasePointerMode(viewModel) {
    private val pointerPosition = PointF(0f, 0f)

    override fun onGestureStart() {
        super.onGestureStart()
        debugCheckNotNull(viewModel.client)

        //Initialize with the latest pointer position
        viewModel.client?.let {
            pointerPosition.x = it.pointerX.toFloat()
            pointerPosition.y = it.pointerY.toFloat()
            it.ignorePointerMovesByServer = true
        }
    }

    override fun onGestureStop(p: PointF) {
        super.onGestureStop(p)
        viewModel.client?.ignorePointerMovesByServer = false
    }

    override fun transformPoint(p: PointF): PointF? {
        val bounds = viewModel.frameState.contentRect
        if (bounds.width() < 1 || bounds.height() < 1) return null
        pointerPosition.x = pointerPosition.x.coerceIn(bounds.left, bounds.right - 1)
        pointerPosition.y = pointerPosition.y.coerceIn(bounds.top, bounds.bottom - 1)
        return pointerPosition
    }

    override fun doMovePointer(p: PointF, dx: Float, dy: Float) {
        val density = viewModel.getApplication<android.app.Application>().resources.displayMetrics.density
        doTouchMove(dx / density, dy / density)
    }

    fun doTouchMove(dxDp: Float, dyDp: Float) {
        val config = viewModel.remoteInputConfig
        val shouldAccelerate = config.acceleration && viewModel.messenger?.hasPressedPointerButtons != true
        doMovePointer(dxDp * 3f * config.pointerSpeed, dyDp * 3f * config.pointerSpeed, shouldAccelerate)
    }

    fun doMovePointer(dx: Float, dy: Float, accelerate: Boolean) {
        val bounds = viewModel.frameState.contentRect
        val xLimit = bounds.right - 1
        val yLimit = bounds.bottom - 1
        if (bounds.width() < 1 || bounds.height() < 1)
            return

        var adx = dx
        var ady = dy
        if (accelerate) {
            accelerator.compute()
            if (viewModel.remoteInputConfig.isMac) {
                val gain = accelerator.vectorFactor()
                adx *= gain; ady *= gain
            } else {
                adx = accelerator.updateDx(dx)
                ady = accelerator.updateDy(dy)
            }
        }

        pointerPosition.apply {
            offset(adx, ady)
            x = x.coerceIn(bounds.left, xLimit)
            y = y.coerceIn(bounds.top, yLimit)
        }
        doButtonDown(PointerButton.None, pointerPosition)

        // Pan only enough to keep the cursor visible, preserving the user's working area.
        val fs = viewModel.frameState
        val vp = fs.toVP(pointerPosition)
        val safe = fs.safeArea
        val margin = 32f * viewModel.getApplication<android.app.Application>().resources.displayMetrics.density
        fun correction(p: Float, low: Float, high: Float): Float {
            val inset = margin.coerceAtMost((high - low).coerceAtLeast(0f) / 2f)
            return p.coerceIn(low + inset, high - inset) - p
        }
        viewModel.panFrame(correction(vp.x, safe.left, safe.right), correction(vp.y, safe.top, safe.bottom))
    }

    override fun doRemoteDrag(button: PointerButton, p: PointF, dx: Float, dy: Float) {
        doButtonDown(button, p)
        doMovePointer(p, dx, dy)
    }
}
