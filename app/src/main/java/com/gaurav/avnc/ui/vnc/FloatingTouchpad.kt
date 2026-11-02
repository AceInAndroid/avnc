/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import com.gaurav.avnc.R
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.gaurav.avnc.ui.vnc.input.ThreeFingerGesture
import com.gaurav.avnc.vnc.PointerButton
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** App-local overlay. Its header moves the panel; its surface moves the remote pointer. */
@SuppressLint("ClickableViewAccessibility")
class FloatingTouchpad(private val activity: VncActivity, private val input: InputHandler) {
    private val pref = activity.viewModel.pref.input
    private val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
    private val parent = activity.binding.touchpadHost
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val style = ControlStyle(activity)
    private val ink get() = style.ink
    private val primary get() = style.blue
    var onStateChanged: (() -> Unit)? = null
    private var docked = false
    private var tabHost: FrameLayout? = null
    val isTabbed get() = tabHost != null
    val isDocked get() = docked
    val isExpanded get() = active && !suppressed && (isTabbed || (pref.showTouchpad && !pref.touchpadCollapsed))
    private var active = false
    private var suppressed = false
    private var fractionX = pref.touchpadX
    private var fractionY = pref.touchpadY
    private var draggingPanel = false
    private var compactForBounds = false
    private var pointerGestureActive = false
    private val heldButtons = mutableSetOf<PointerButton>()
    private var cancelSurface: () -> Unit = {}
    private var cancelScrollStrip: () -> Unit = {}
    private var surfaceMovementOnly: () -> Unit = {}
    private val panel = object : LinearLayout(activity) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !suppressed) {
                pointerGestureActive = true
                input.onVirtualPointerStart()
            }
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelSurface()
            val handled = super.dispatchTouchEvent(event)
            if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && pointerGestureActive) {
                pointerGestureActive = false
                input.onVirtualPointerStop(event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
            return handled
        }
    }
    private val expanded = LinearLayout(activity)
    private val header = LinearLayout(activity)
    private val middle = LinearLayout(activity)
    private lateinit var touchSurface: View
    private lateinit var scrollSurface: View
    private var appliedSize = -1
    private var appliedOpacity = -1
    /** Requested width for the landscape keyboard dock; the host reserves this space. */
    val preferredWidthPx get() = dp((220f * pref.touchpadSizePercent / 100f).roundToInt())
    private val appearanceChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "touchpad_size" || key == "touchpad_opacity") refreshAppearance()
    }

    init {
        panel.id = R.id.floating_touchpad
        panel.orientation = LinearLayout.VERTICAL
        panel.elevation = dp(8).toFloat()
        panel.background = style.background()
        panel.setPadding(dp(6), dp(4), dp(6), dp(4))
        panel.isClickable = true // Consume empty spaces as well as controls.
        panel.isMotionEventSplittingEnabled = true
        expanded.orientation = LinearLayout.VERTICAL
        panel.addView(expanded, LinearLayout.LayoutParams(-1, -2))
        val handle = TextView(activity).apply {
            id = R.id.touchpad_handle
            text = activity.getString(R.string.touchpad_title)
            contentDescription = activity.getString(R.string.touchpad_move)
            setTextColor(ink)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            val grip = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.ic_control_grip)?.mutate()
            grip?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it, style.secondary) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(grip, null, null, null)
            compoundDrawablePadding = dp(4)
        }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(style.icon(R.drawable.ic_control_chevron, R.string.touchpad_collapse).apply {
            id = R.id.touchpad_collapse
            setOnClickListener { setCollapsed(true) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        expanded.addView(header)
        installPanelDrag(handle)

        val surface = TextView(activity).apply {
            id = R.id.touchpad_surface
            text = "·  ·  ·"
            contentDescription = activity.getString(R.string.touchpad_surface)
            textSize = 12f
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(style.color(R.color.viewer_key_secondary))
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                // A second finger may tap between drag movements while the first still
                // holds Left. Its synthetic click must not release that held button.
                if (heldButtons.isNotEmpty()) return@setOnClickListener
                val accessibilityClick = !pointerGestureActive
                if (accessibilityClick) input.onVirtualPointerStart()
                input.onVirtualPointerClick()
                if (accessibilityClick) input.onVirtualPointerStop()
            }
        }
        touchSurface = surface
        installSurface(surface)
        middle.addView(surface, LinearLayout.LayoutParams(0, dp(96), 1f))
        val scroll = TextView(activity).apply {
            id = R.id.touchpad_scroll
            text = "↕"
            textSize = 22f
            setTextColor(style.secondary)
            contentDescription = activity.getString(R.string.touchpad_scroll)
            gravity = Gravity.CENTER
        }
        scrollSurface = scroll
        var scrollY = 0f
        var stripOwnsSession = false
        fun stopStrip(cancelled: Boolean) {
            if (stripOwnsSession) {
                stripOwnsSession = false
                input.onVirtualPointerStop(cancelled)
            }
        }
        cancelScrollStrip = { stopStrip(true) }
        scroll.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSurface()
                    stopStrip(true)
                    if (!pointerGestureActive) { input.onVirtualPointerStart(); stripOwnsSession = true }
                    scrollY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (heldButtons.isEmpty()) input.onVirtualPointerScroll(0f, (e.y - scrollY) / density)
                    scrollY = e.y
                }
                MotionEvent.ACTION_UP -> stopStrip(false)
                MotionEvent.ACTION_CANCEL -> stopStrip(true)
            }
            true
        }
        middle.addView(scroll, LinearLayout.LayoutParams(dp(48), dp(96)))
        expanded.addView(middle)
        val buttons = LinearLayout(activity).apply { isMotionEventSplittingEnabled = true }
        listOf(Triple(R.string.touchpad_left, R.id.touchpad_left, PointerButton.Left),
               Triple(R.string.touchpad_right, R.id.touchpad_right, PointerButton.Right)).forEach { (label, id, pointerButton) ->
            buttons.addView(button(label, activity.getString(label), id).apply {
                setOnClickListener {
                    // Accessibility clicks have no MotionEvent.
                    cancelSurface()
                    input.onVirtualPointerStart()
                    input.onVirtualPointerButton(pointerButton, true)
                    input.onVirtualPointerButton(pointerButton, false)
                    input.onVirtualPointerStop()
                }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            surfaceMovementOnly()
                            heldButtons.add(pointerButton)
                            input.onVirtualPointerButton(pointerButton, true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            heldButtons.remove(pointerButton)
                            input.onVirtualPointerButton(pointerButton, false)
                        }
                    }
                    true
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(3), dp(2), 0) })
        }
        expanded.addView(buttons)
        parent.addView(panel, FrameLayout.LayoutParams(preferredWidthPx, -2))
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
        refreshAppearance()
        applyCollapsed()
        updateVisibility()
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                preferences.registerOnSharedPreferenceChangeListener(appearanceChanges)
                refreshAppearance()
            }
            override fun onStop(owner: LifecycleOwner) {
                preferences.unregisterOnSharedPreferenceChangeListener(appearanceChanges)
            }
        })
    }

    /** Read persisted settings on return from Settings, without restoring any held input. */
    fun refreshAppearance() {
        val size = pref.touchpadSizePercent
        val opacity = pref.touchpadOpacityPercent
        if (size == appliedSize && opacity == appliedOpacity) return
        if (appliedSize != -1) activity.releaseInputControls()
        appliedSize = size; appliedOpacity = opacity
        panel.alpha = opacity / 100f
        updateDimensions()
        panel.post { reposition() }
        onStateChanged?.invoke()
    }

    private fun updateDimensions() {
        if (!::touchSurface.isInitialized || isTabbed) return
        val obstruction = max(0, activity.toolbar.bottomObstruction - parent.paddingBottom)
        val availableHeight = parent.height - parent.paddingTop - parent.paddingBottom - obstruction - dp(16)
        val availableWidth = parent.width - parent.paddingLeft - parent.paddingRight - dp(16)
        val desiredSurface = dp((96f * pref.touchpadSizePercent / 100f).roundToInt())
        // Header/buttons keep their 48dp targets; shrink only the pointing surface to fit.
        val surfaceHeight = if (!docked && availableHeight > 0)
            desiredSurface.coerceAtMost(max(dp(72), availableHeight - dp(107))) else desiredSurface
        listOf(touchSurface, scrollSurface).forEach { child ->
            if (child.layoutParams.height != surfaceHeight)
                child.layoutParams = child.layoutParams.apply { height = surfaceHeight }
        }
        val width = if (docked) -1 else if (availableWidth > 0)
            preferredWidthPx.coerceAtMost(max(dp(165), availableWidth)) else preferredWidthPx
        if (panel.layoutParams.width != width)
            panel.layoutParams = panel.layoutParams.apply { this.width = width }
    }

    private fun button(description: Int, label: String, viewId: Int) = style.button(label, activity.getString(description)).apply {
        id = viewId
        setPadding(0, 0, 0, 0)
        textSize = 13f
    }

    fun onConnected() { active = true; updateVisibility(); onStateChanged?.invoke() }
    fun onDisconnected() { cancel(); active = false; updateVisibility(); onStateChanged?.invoke() }
    fun setSuppressed(value: Boolean) {
        if (value) cancel()
        suppressed = value
        updateVisibility()
        onStateChanged?.invoke()
    }
    fun toggle() {
        activity.releaseInputControls()
        if (!pref.showTouchpad || pref.touchpadCollapsed) {
            pref.showTouchpad = true
            pref.touchpadCollapsed = false
        } else pref.showTouchpad = false
        applyCollapsed()
        updateVisibility()
        onStateChanged?.invoke()
        if (isExpanded && compactForBounds && !docked)
            Toast.makeText(activity, R.string.touchpad_needs_space, Toast.LENGTH_SHORT).show()
    }

    /** Reparent the same input surface; moving controls never restores held buttons. */
    fun setDocked(value: Boolean) {
        if (isTabbed || docked == value) return
        activity.releaseInputControls()
        docked = value
        (panel.parent as android.view.ViewGroup).removeView(panel)
        panel.translationX = 0f; panel.translationY = 0f
        val target = if (value) activity.binding.touchpadDock else parent
        target.addView(panel, FrameLayout.LayoutParams(if (value) -1 else preferredWidthPx, if (value) -1 else -2))
        compactForBounds = false
        applyCollapsed()
        updateVisibility()
    }

    /** The main Touchpad tab uses the same surface and input ownership as the overlay. */
    fun setTabHost(target: FrameLayout?) {
        if (tabHost === target) return
        activity.releaseInputControls()
        docked = false
        tabHost = target
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        panel.translationX = 0f; panel.translationY = 0f
        (target ?: parent).addView(panel, FrameLayout.LayoutParams(if (target != null) -1 else preferredWidthPx,
                if (target != null) -1 else -2))
        header.isVisible = target == null
        expanded.layoutParams = LinearLayout.LayoutParams(-1, if (target != null) -1 else -2)
        middle.layoutParams = LinearLayout.LayoutParams(-1, if (target != null) 0 else -2,
                if (target != null) 1f else 0f)
        listOf(touchSurface, scrollSurface).forEach { child ->
            child.layoutParams = child.layoutParams.apply { height = if (target != null) -1 else dp(96) }
        }
        compactForBounds = false
        applyCollapsed()
        updateVisibility()
    }

    fun cancel() {
        cancelSurface()
        cancelScrollStrip()
        heldButtons.clear()
        // Deliver CANCEL to children too: no held-button visuals or in-progress tap survive.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).let {
            panel.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    fun collapse(): Boolean {
        if (!panel.isVisible || pref.touchpadCollapsed) return false
        setCollapsed(true)
        return true
    }

    private fun updateVisibility() {
        panel.isVisible = isExpanded && !compactForBounds
    }
    private fun setCollapsed(value: Boolean) {
        activity.releaseInputControls(); pref.touchpadCollapsed = value; applyCollapsed(); updateVisibility(); onStateChanged?.invoke()
    }
    private fun applyCollapsed() {
        val collapsed = !isTabbed && (pref.touchpadCollapsed || compactForBounds)
        expanded.isVisible = !collapsed
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (docked || isTabbed) -1 else preferredWidthPx
        }
        panel.post { reposition() }
    }

    fun reposition() {
        updateDimensions()
        if (draggingPanel || docked || isTabbed) return
        val margin = dp(8)
        if (parent.width <= 0 || parent.height <= 0) return
        val obstruction = max(0, activity.toolbar.bottomObstruction - parent.paddingBottom)
        val compact = parent.height - parent.paddingTop - parent.paddingBottom - obstruction < dp(195) ||
                      parent.width - parent.paddingLeft - parent.paddingRight < dp(181)
        if (compact != compactForBounds) { compactForBounds = compact; applyCollapsed(); updateVisibility() }
        val minX = parent.paddingLeft + margin
        val minY = parent.paddingTop + margin
        val maxX = max(minX, parent.width - parent.paddingRight - panel.width - margin)
        val maxY = max(minY, parent.height - parent.paddingBottom - obstruction - panel.height - margin)
        panel.x = minX + fractionX.coerceIn(0f, 1f) * (maxX - minX)
        panel.y = minY + fractionY.coerceIn(0f, 1f) * (maxY - minY)
    }

    private fun installPanelDrag(handle: View) {
        var startX = 0f
        var startY = 0f
        var panelX = 0f
        var panelY = 0f
        handle.setOnTouchListener { _, e ->
            if (docked) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (docked) return@setOnTouchListener true
                    cancelSurface()
                    draggingPanel = true
                    startX = e.rawX; startY = e.rawY; panelX = panel.x; panelY = panel.y
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val minX = (parent.paddingLeft + dp(8)).toFloat()
                    val minY = (parent.paddingTop + dp(8)).toFloat()
                    val maxX = max(minX, parent.width - parent.paddingRight - panel.width - dp(8).toFloat())
                    val maxY = max(minY, parent.height - max(parent.paddingBottom, activity.toolbar.bottomObstruction) - panel.height - dp(8).toFloat())
                    panel.x = (panelX + e.rawX - startX).coerceIn(minX, maxX)
                    panel.y = (panelY + e.rawY - startY).coerceIn(minY, maxY)
                    fractionX = if (maxX > minX) (panel.x - minX) / (maxX - minX) else 0f
                    fractionY = if (maxY > minY) (panel.y - minY) / (maxY - minY) else 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingPanel = false
                    pref.touchpadX = fractionX; pref.touchpadY = fractionY
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }

    private fun installSurface(surface: View) {
        val config = ViewConfiguration.get(activity)
        val slop = config.scaledTouchSlop.toFloat()
        val pointerSlop = minOf(slop, density)
        val doubleSlop = config.scaledDoubleTapSlop.toFloat()
        val handler = Handler(Looper.getMainLooper())
        var pendingTap: Runnable? = null
        var longPress: Runnable? = null
        var lastTapTime = 0L
        val lastTapPoint = PointF()
        val start = PointF()
        val last = PointF()
        val multiStarts = mutableMapOf<Int, PointF>()
        val multiLast = PointF()
        var velocity: VelocityTracker? = null
        var activeSurface = false
        var ownsSession = false
        var moved = false
        var dragging = false
        var secondTap = false
        var multi = false
        var scrolling = false
        var endedMulti = false
        var movementOnly = false
        lateinit var threeFinger: ThreeFingerGesture

        fun clearLongPress() { longPress?.let { handler.removeCallbacks(it) }; longPress = null }
        fun clearTap() { pendingTap?.let { handler.removeCallbacks(it) }; pendingTap = null }
        fun releaseDrag() {
            if (dragging) input.onVirtualPointerButton(PointerButton.Left, false)
            dragging = false
            surface.isSelected = false
            surface.isPressed = false
            surface.alpha = 1f
        }
        fun stopOwnedSession(cancelled: Boolean) {
            if (ownsSession) { ownsSession = false; input.onVirtualPointerStop(cancelled) }
        }
        fun click(button: PointerButton, count: Int = 1) {
            // Confirmed taps run after the panel's touch stream has already ended.
            if (!active || suppressed || !panel.isShown || heldButtons.isNotEmpty()) return
            val separateSession = !pointerGestureActive && !ownsSession
            if (separateSession) input.onVirtualPointerStart()
            input.onVirtualPointerClick(button, count)
            if (separateSession) input.onVirtualPointerStop()
        }
        fun startDrag() {
            clearLongPress()
            clearTap()
            dragging = true
            moved = true
            surface.isSelected = true
            surface.isPressed = true
            surface.alpha = .65f
            surface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            input.onVirtualPointerButton(PointerButton.Left, true)
        }
        fun centroid(e: MotionEvent): PointF? {
            if (multiStarts.size != 2) return null
            var x = 0f; var y = 0f
            for (id in multiStarts.keys) {
                val i = e.findPointerIndex(id)
                if (i < 0) return null
                x += e.getX(i); y += e.getY(i)
            }
            return PointF(x / 2, y / 2)
        }
        fun cancel(cancelled: Boolean = true) {
            threeFinger.cancel()
            clearTap(); clearLongPress(); releaseDrag()
            if (scrolling) input.onVirtualPointerScrollEnd(0f, 0f, true)
            velocity?.recycle(); velocity = null
            activeSurface = false; endedMulti = true; scrolling = false
            stopOwnedSession(cancelled)
            parent.requestDisallowInterceptTouchEvent(false)
        }
        cancelSurface = { cancel() }
        surfaceMovementOnly = {
            threeFinger.cancel()
            clearTap(); clearLongPress(); releaseDrag()
            if (scrolling) input.onVirtualPointerScrollEnd(0f, 0f, true)
            scrolling = false
            movementOnly = true
        }

        threeFinger = ThreeFingerGesture(density, slop,
                onCapture = {
                    clearTap(); clearLongPress(); releaseDrag()
                    input.onVirtualPointerScrollEnd(0f, 0f, true)
                    velocity?.recycle(); velocity = null
                    scrolling = false; endedMulti = true
                },
                onAction = { input.onThreeFingerAction(it) })

        surface.setOnTouchListener { _, e ->
            input.onVirtualPointerMotion(e)
            if (threeFinger.onTouchEvent(e, input.systemGesturesEnabled,
                            scrolling || dragging || moved || endedMulti || movementOnly || heldButtons.isNotEmpty())) {
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    activeSurface = false
                    stopOwnedSession(e.actionMasked == MotionEvent.ACTION_CANCEL)
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = e.eventTime
                    val isSecond = pendingTap != null && now - lastTapTime <= ViewConfiguration.getDoubleTapTimeout() &&
                            hypot(e.x - lastTapPoint.x, e.y - lastTapPoint.y) <= doubleSlop
                    // A distant second touch commits the preceding independent tap first.
                    if (pendingTap != null && !isSecond) { clearTap(); click(PointerButton.Left) }
                    clearTap(); clearLongPress()
                    velocity?.recycle(); velocity = null
                    if (!pointerGestureActive) { input.onVirtualPointerStart(); ownsSession = true }
                    activeSurface = true; moved = false; dragging = false; secondTap = isSecond
                    multi = false; scrolling = false; endedMulti = false
                    movementOnly = heldButtons.isNotEmpty()
                    multiStarts.clear()
                    start.set(e.x, e.y); last.set(start)
                    parent.requestDisallowInterceptTouchEvent(true)
                    if (!movementOnly && !secondTap) {
                        longPress = Runnable {
                            if (activeSurface && !moved && !multi && !endedMulti && heldButtons.isEmpty()) startDrag()
                        }.also { handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    clearLongPress(); clearTap()
                    if (movementOnly || heldButtons.isNotEmpty()) {
                        movementOnly = true
                    } else if (dragging || endedMulti || e.pointerCount != 2 || multi) {
                        releaseDrag()
                        if (scrolling) input.onVirtualPointerScrollEnd(0f, 0f, true)
                        scrolling = false; endedMulti = true
                    } else {
                        multi = true
                        secondTap = false
                        repeat(2) { i -> multiStarts[e.getPointerId(i)] = PointF(e.getX(i), e.getY(i)) }
                        centroid(e)?.let { multiLast.set(it) }
                        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (activeSurface && !endedMulti) {
                    if (movementOnly || heldButtons.isNotEmpty()) {
                        // The button and surface receive split streams. Never release a button
                        // merely because the surface's finger lifts or starts a second tap.
                        movementOnly = true
                        val dx = e.x - last.x; val dy = e.y - last.y
                        last.set(e.x, e.y)
                        moved = moved || dx != 0f || dy != 0f
                        input.onVirtualPointerMove(dx / density, dy / density)
                    } else if (multi) {
                        val focus = centroid(e)
                        if (focus == null || e.pointerCount != 2) {
                            if (scrolling) input.onVirtualPointerScrollEnd(0f, 0f, true)
                            scrolling = false; endedMulti = true
                        } else {
                            velocity?.addMovement(e)
                            val crossedSlop = multiStarts.any { (id, p) ->
                                val i = e.findPointerIndex(id)
                                hypot(e.getX(i) - p.x, e.getY(i) - p.y) > slop
                            }
                            if (scrolling || crossedSlop) {
                                scrolling = true
                                input.onVirtualPointerScroll((focus.x - multiLast.x) / density, (focus.y - multiLast.y) / density)
                                multiLast.set(focus)
                            }
                        }
                    } else {
                        val dx = e.x - last.x; val dy = e.y - last.y
                        last.set(e.x, e.y)
                        if (dragging || moved) input.onVirtualPointerMove(dx / density, dy / density)
                        else if (hypot(e.x - start.x, e.y - start.y) > if (secondTap) slop else pointerSlop) {
                            clearLongPress()
                            if (secondTap) startDrag() else moved = true
                            input.onVirtualPointerMove((e.x - start.x) / density, (e.y - start.y) / density)
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    clearLongPress()
                    if (!movementOnly && multi && !endedMulti) {
                        if (scrolling) {
                            centroid(e)?.let { focus ->
                                val dx = (focus.x - multiLast.x) / density
                                val dy = (focus.y - multiLast.y) / density
                                if (dx != 0f || dy != 0f) input.onVirtualPointerScroll(dx, dy)
                                multiLast.set(focus)
                            }
                            velocity?.addMovement(e)
                            velocity?.computeCurrentVelocity(1000, config.scaledMaximumFlingVelocity.toFloat())
                            val vx = multiStarts.keys.sumOf { (velocity?.getXVelocity(it) ?: 0f).toDouble() }.toFloat() / (2 * density)
                            val vy = multiStarts.keys.sumOf { (velocity?.getYVelocity(it) ?: 0f).toDouble() }.toFloat() / (2 * density)
                            input.onVirtualPointerScrollEnd(vx, vy)
                        } else if (e.eventTime - e.downTime <= ViewConfiguration.getTapTimeout() + ViewConfiguration.getDoubleTapTimeout()) {
                            click(PointerButton.Right)
                        }
                    }
                    if (!movementOnly) endedMulti = true
                    velocity?.recycle(); velocity = null
                    scrolling = false
                }
                MotionEvent.ACTION_UP -> {
                    clearLongPress()
                    if (activeSurface && !movementOnly && heldButtons.isEmpty() && !endedMulti && !multi) {
                        if (dragging) releaseDrag()
                        else if (!moved) {
                            if (secondTap) click(PointerButton.Left, 2)
                            else {
                                lastTapTime = e.eventTime
                                lastTapPoint.set(e.x, e.y)
                                pendingTap = Runnable { pendingTap = null; click(PointerButton.Left) }
                                        .also { handler.postDelayed(it, ViewConfiguration.getDoubleTapTimeout().toLong()) }
                            }
                        }
                    }
                    activeSurface = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    stopOwnedSession(false)
                }
                MotionEvent.ACTION_CANCEL -> cancel()
            }
            true
        }
    }
}
