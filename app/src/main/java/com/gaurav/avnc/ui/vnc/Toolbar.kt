/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.gaurav.avnc.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.ui.prefs.RemoteInputEditor
import com.google.android.material.button.MaterialButton

/** A single control entry, shared by the viewer capsule and keyboard header. */
class Toolbar(private val activity: VncActivity) {
    private val vm = activity.viewModel
    val macControls = MacViewerControls(activity)
    private lateinit var macRow: View
    private var legacyControls = emptyList<View>()
    private val style = ControlStyle(activity)
    private val host = activity.binding.frameContainer
    private val capsule = LinearLayout(activity)
    private val opener = style.icon(R.drawable.ic_control_controls, R.string.control_open)
    private val menuOverlay = FrameLayout(activity)
    private val menuPanel = LinearLayout(activity)
    private val menuScroll = ScrollView(activity)
    private lateinit var touchpadButton: MaterialButton
    private var initialized = false
    private var connected = false
    private var menuHelp = false
    private var menuActions = false
    private var bottomInset = 0
    private var compactLabels: Boolean? = null
    private var edgeCandidate = false
    private var edgeCaptured = false
    private var edgeX = 0f
    private var edgeY = 0f
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }
    val isOpen get() = menuOverlay.isVisible
    private val legacyInset get() = activity.virtualKeys.container?.takeIf { it.isVisible }?.height ?: 0
    val bottomObstruction get() = if (capsule.isVisible || opener.isVisible) style.dp(76) + maxOf(bottomInset, legacyInset) else bottomInset

    fun initialize() {
        if (initialized) return
        initialized = true
        capsule.id = R.id.controls_capsule
        capsule.gravity = Gravity.CENTER
        capsule.setPadding(style.dp(6), style.dp(4), style.dp(6), style.dp(4))
        capsule.background = style.background(radius = 32)
        capsule.elevation = style.dp(8).toFloat()
        capsule.isClickable = true
        fun action(label: Int, icon: Int, id: Int, run: () -> Unit): MaterialButton {
            val b = style.button(activity.getString(label)).apply {
                this.id = id
                this.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, icon)
                iconSize = style.dp(19); iconPadding = style.dp(6)
                iconTint = ColorStateList.valueOf(style.ink)
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                cornerRadius = style.dp(24)
                minimumWidth = style.dp(48)
                setOnClickListener { run() }
            }
            capsule.addView(b, LinearLayout.LayoutParams(-2, style.dp(48)))
            return b
        }
        action(R.string.control_keyboard, R.drawable.ic_control_keyboard, R.id.keyboard_btn) { activity.showKeyboard() }
        touchpadButton = action(R.string.control_touchpad, R.drawable.ic_control_touchpad, R.id.touchpad_btn) { toggleTouchpad() }
        action(R.string.control_more, R.drawable.ic_control_more, R.id.controls_more) { open() }
        capsule.addView(style.icon(R.drawable.ic_control_chevron, R.string.control_collapse).apply {
            id = R.id.controls_collapse
            setOnClickListener { vm.pref.input.controlsCollapsed = true; refresh() }
        }, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
        legacyControls = (0 until capsule.childCount).map { capsule.getChildAt(it) }
        macRow = macControls.createRow()
        host.addView(capsule, floatingParams())
        opener.id = R.id.open_toolbar_btn
        opener.elevation = style.dp(6).toFloat()
        opener.background = style.background(radius = 24)
        opener.setOnClickListener { vm.pref.input.controlsCollapsed = false; refresh() }
        host.addView(opener, floatingParams().apply { width = style.dp(48); height = style.dp(48) })

        menuOverlay.id = R.id.controls_menu
        menuOverlay.visibility = View.GONE
        menuOverlay.isClickable = true
        menuOverlay.setBackgroundColor(0x24000000)
        menuOverlay.setOnClickListener { close() }
        menuPanel.orientation = LinearLayout.VERTICAL
        menuPanel.background = style.background()
        menuPanel.elevation = style.dp(12).toFloat()
        menuPanel.isClickable = true
        menuPanel.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(12))
        menuScroll.isFillViewport = false
        menuScroll.addView(menuPanel)
        menuScroll.isClickable = true
        menuOverlay.addView(menuScroll)
        activity.binding.viewerRoot.addView(menuOverlay, FrameLayout.LayoutParams(-1, -1))
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.binding.inputView.controlGestureInterceptor = ::onEdgeTouch
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position(); activity.floatingTouchpad.reposition() }
        macControls.initialize()
        vm.viewerSettingsVersion.observe(activity) { refresh(); if (isOpen) renderMenu() }
        vm.inPiPMode.observe(activity) { if (it) close(); refresh() }
        vm.activeViewMode.observe(activity) { refresh() }
        vm.activeGestureStyle.observe(activity) { if (isOpen) renderMenu() }
        refresh()
    }

    private fun floatingParams() = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        bottomMargin = style.dp(12) + bottomInset
    }

    fun refresh() {
        if (!initialized) return
        val show = connected && vm.inPiPMode.value != true && !activity.computerKeyboard.isVisible
        capsule.isVisible = show && !vm.pref.input.controlsCollapsed
        opener.isVisible = show && vm.pref.input.controlsCollapsed
        if (macControls.isMac && macRow.parent == null) {
            capsule.removeAllViews()
            capsule.addView(macRow, LinearLayout.LayoutParams(-1, style.dp(48)))
        } else if (!macControls.isMac && macRow.parent != null) {
            capsule.removeAllViews()
            legacyControls.forEach { capsule.addView(it) }
        }
        macControls.refresh()
        val canInput = vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT
        capsule.findViewById<View>(R.id.keyboard_btn)?.apply { isEnabled = canInput; alpha = if (canInput) 1f else .4f }
        touchpadButton.isEnabled = canInput
        touchpadButton.alpha = if (canInput) 1f else .4f
        val selected = activity.floatingTouchpad.isExpanded
        touchpadButton.isSelected = selected
        touchpadButton.setTextColor(if (selected) style.blue else style.ink)
        touchpadButton.iconTint = ColorStateList.valueOf(if (selected) style.blue else style.ink)
        activity.computerKeyboard.updateTouchpadSelection(selected)
        position()
    }

    fun toggleTouchpad() {
        activity.floatingTouchpad.toggle()
        refresh()
        if (activity.floatingTouchpad.isExpanded && !vm.pref.input.hasShownControlHelp) showHelp()
    }

    fun open() {
        if (!connected || vm.inPiPMode.value == true) return
        activity.releaseInputControls()
        menuHelp = false
        menuActions = false
        renderMenu()
        menuOverlay.isVisible = true
        back.isEnabled = true
        menuOverlay.bringToFront()
        position()
        if (Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
            menuPanel.alpha = 0f
            menuPanel.animate().alpha(1f).setDuration(150).start()
        }
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(menuPanel, activity.getString(R.string.control_title))
    }

    fun showHelp() {
        open()
        if (!isOpen) return
        vm.pref.input.hasShownControlHelp = true
        menuHelp = true
        renderMenu()
    }

    fun close() {
        menuOverlay.isVisible = false
        back.isEnabled = false
        menuHelp = false
        menuActions = false
    }

    fun onStateChange(isConnected: Boolean) {
        initialize()
        connected = isConnected
        if (!connected) { close(); macControls.dismiss() }
        refresh()
    }

    fun handleInsets(insets: WindowInsetsCompat) {
        val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
        bottomInset = safe.bottom
        menuOverlay.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        position()
    }

    private fun position() {
        if (!initialized || host.width == 0) return
        listOf(capsule, opener).forEach { view ->
            (view.layoutParams as FrameLayout.LayoutParams).let { params ->
                val margin = style.dp(12) + maxOf(bottomInset, legacyInset)
                if (params.bottomMargin != margin) { params.bottomMargin = margin; view.layoutParams = params }
            }
        }
        val capsuleWidth = if (macControls.isMac) minOf(host.width - style.dp(24), style.dp(1000)).coerceAtLeast(1) else -2
        if (capsule.layoutParams.width != capsuleWidth) capsule.layoutParams = capsule.layoutParams.apply { width = capsuleWidth }
        val menuHost = activity.binding.viewerRoot
        val landscape = menuHost.width > menuHost.height
        val width = minOf(menuHost.width - style.dp(24) - menuOverlay.paddingLeft - menuOverlay.paddingRight, style.dp(400))
        val params = FrameLayout.LayoutParams(width.coerceAtLeast(1), -2,
                Gravity.BOTTOM or if (landscape) Gravity.END else Gravity.CENTER_HORIZONTAL).apply {
            setMargins(style.dp(12), style.dp(12), style.dp(12), style.dp(if (landscape) 12 else 80))
        }
        val maxHeight = (menuHost.height - menuOverlay.paddingTop - menuOverlay.paddingBottom - params.bottomMargin - style.dp(12)).coerceAtLeast(1)
        menuScroll.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        params.height = minOf(menuScroll.measuredHeight, maxHeight)
        val old = menuScroll.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != params.width || old.height != params.height || old.gravity != params.gravity ||
            old.bottomMargin != params.bottomMargin || old.leftMargin != params.leftMargin || old.topMargin != params.topMargin) {
            menuScroll.layoutParams = params
        }
        val compact = host.width < style.dp(340) || (activity.resources.configuration.fontScale > 1.3f && host.width < style.dp(440))
        if (compactLabels != compact) {
            compactLabels = compact
            for (i in 0 until capsule.childCount) (capsule.getChildAt(i) as? MaterialButton)?.let {
                val label = activity.getString(when (it.id) {
                    R.id.keyboard_btn -> R.string.control_keyboard
                    R.id.touchpad_btn -> R.string.control_touchpad
                    else -> R.string.control_more
                })
                it.text = if (compact) "" else label
                it.contentDescription = label
                it.iconPadding = if (compact) 0 else style.dp(6)
            }
        }
    }

    private fun renderMenu() {
        val title = when {
            menuHelp -> R.string.control_help
            menuActions -> R.string.remote_input_shortcuts
            else -> R.string.control_title
        }
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(menuPanel, activity.getString(title))
        menuPanel.removeAllViews()
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(style.label(activity.getString(title), 20f, true),
                LinearLayout.LayoutParams(0, style.dp(48), 1f))
        heading.addView(style.icon(R.drawable.ic_control_close, R.string.control_done).apply {
            id = R.id.controls_menu_close; setOnClickListener { close() }
        }, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
        menuPanel.addView(heading)
        if (menuActions) {
            val config = vm.remoteInputConfig
            RemoteAction.entries.filter { config.shortcut(it) != null }.forEach { action ->
                val label = RemoteInputEditor.actionLabel(activity, action)
                option(label, enabled = vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT,
                        tag = "remote_action_${action.name.lowercase()}") {
                    close()
                    activity.performRemoteAction(action)
                }
            }
        } else if (menuHelp) {
            menuPanel.addView(style.label(activity.getString(R.string.control_help_intro), 16f, true).apply { setPadding(0, style.dp(8), 0, style.dp(8)) })
            menuPanel.addView(style.label(activity.getString(R.string.msg_mouse_gesture_help), 15f).apply {
                setLineSpacing(style.dp(5).toFloat(), 1f); setPadding(0, style.dp(8), 0, style.dp(16))
            })
            menuPanel.addView(style.label(activity.getString(R.string.control_touchpad_hint), 15f).apply { setLineSpacing(style.dp(5).toFloat(), 1f) })
        } else {
            if (macControls.isMac) {
                section(R.string.mac_action_dock)
                listOf(R.string.mac_spotlight to RemoteAction.SEARCH,
                        R.string.mac_previous_app to RemoteAction.SWITCH_APP,
                        R.string.mac_action_quick_look to RemoteAction.QUICK_LOOK,
                        R.string.mac_action_fullscreen to RemoteAction.TOGGLE_FULLSCREEN,
                        R.string.mac_show_desktop to RemoteAction.SHOW_DESKTOP).forEach { (label, action) ->
                    option(label, enabled = vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT) {
                        close(); activity.performRemoteAction(action)
                    }
                }
                option(R.string.mac_dock_activation) { close(); macControls.showDockSettings() }
                option(R.string.mac_displays) { close(); macControls.showDisplays() }
                option(R.string.mac_quality) { close(); macControls.showQuality() }
            }
            section(R.string.control_picture)
            option(R.string.control_fit) { vm.resetZoomToDefault(); close() }
            option(R.string.control_actual) { vm.resetZoom(); close() }
            option(R.string.control_zoom_lock, vm.profile.fZoomLocked) { vm.toggleZoomLock(!vm.profile.fZoomLocked); renderMenu() }
            option(R.string.control_zoom_save, enabled = vm.profile.isSaved()) { vm.saveZoom(); close() }
            section(R.string.control_view)
            listOf(R.string.control_normal to ServerProfile.VIEW_MODE_NORMAL,
                    R.string.control_readonly to ServerProfile.VIEW_MODE_NO_INPUT,
                    R.string.control_novideo to ServerProfile.VIEW_MODE_NO_VIDEO).forEach { (label, mode) ->
                option(label, vm.activeViewMode.value == mode) { vm.setViewMode(mode); close() }
            }
            section(R.string.control_interaction)
            val platform = RemoteInputEditor.platformLabel(activity, vm.remoteInputConfig.platform)
            option(activity.getString(R.string.remote_input_this_computer) + " · " + platform, tag = "remote_input_session") {
                close()
                RemoteInputEditor.showSession(activity)
            }
            if (RemotePlatform.from(vm.profile.remotePlatform) == RemotePlatform.AUTO &&
                    vm.remoteInputConfig.platform == RemotePlatform.GENERIC) {
                menuPanel.addView(style.label(activity.getString(R.string.remote_input_auto_unknown), 13f).apply {
                    setPadding(style.dp(12), 0, style.dp(12), style.dp(8))
                })
            }
            option(R.string.remote_input_shortcuts, enabled = vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT) {
                menuActions = true
                renderMenu()
            }
            if (vm.pref.viewer.toolbarShowGestureStyleToggle) {
                listOf(R.string.control_auto to "auto", R.string.control_direct to "touchscreen", R.string.control_relative to "touchpad").forEach { (label, mode) ->
                    option(label, vm.profile.gestureStyle == mode, vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_VIDEO) { vm.setGestureStyle(mode); close() }
                }
            }
            option(R.string.control_aux, enabled = vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT) {
                activity.hideComputerKeyboard(); activity.virtualKeys.show(true); close()
            }
            section(R.string.control_help)
            option(R.string.control_help) { menuHelp = true; vm.pref.input.hasShownControlHelp = true; renderMenu() }
        }
        menuScroll.scrollTo(0, 0)
        menuOverlay.post { position() }
    }

    private fun section(label: Int) {
        menuPanel.addView(style.label(activity.getString(label), 12f).apply { setPadding(style.dp(10), style.dp(16), 0, style.dp(6)) })
    }

    private fun option(label: Int, selected: Boolean? = null, enabled: Boolean = true, run: () -> Unit) {
        option(activity.getString(label), selected, enabled, run = run)
    }

    private fun option(label: String, selected: Boolean? = null, enabled: Boolean = true, tag: String? = null, run: () -> Unit) {
        val b = style.button(label + if (selected == true) "  ✓" else "").apply {
            this.tag = tag
            contentDescription = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6))
            maxLines = 2
            minimumHeight = style.dp(48)
            isEnabled = enabled; alpha = if (enabled) 1f else .4f
            style.select(this, selected == true)
            if (Build.VERSION.SDK_INT >= 30 && selected != null) stateDescription = activity.getString(if (selected) R.string.control_active else R.string.control_inactive)
            setOnClickListener { run() }
        }
        menuPanel.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = style.dp(3) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onEdgeTouch(event: MotionEvent): Boolean {
        if (!connected || !vm.pref.viewer.toolbarOpenWithSwipe || activity.computerKeyboard.isVisible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeCaptured = false
                val left = vm.pref.viewer.toolbarAlignment == "start"
                edgeCandidate = if (left) event.x <= style.dp(20) else event.x >= host.width - style.dp(20)
                edgeX = event.x; edgeY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> edgeCandidate = false
            MotionEvent.ACTION_MOVE -> if (edgeCandidate && event.pointerCount == 1) {
                val dx = (event.x - edgeX) * if (vm.pref.viewer.toolbarAlignment == "start") 1 else -1
                val dy = kotlin.math.abs(event.y - edgeY)
                if (dy > style.dp(32) && dy > dx) edgeCandidate = false
                if (dx > maxOf(style.dp(32), ViewConfiguration.get(activity).scaledTouchSlop * 2) && dx > dy * 2) {
                    edgeCandidate = false; edgeCaptured = true; open()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { edgeCandidate = false; return edgeCaptured.also { edgeCaptured = false } }
        }
        return edgeCaptured
    }
}
