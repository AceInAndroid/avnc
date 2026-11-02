/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.isVisible
import com.gaurav.avnc.R
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.ui.prefs.RemoteInputEditor

internal enum class InputTab { Keyboard, Touchpad, Shortcuts }

/** Main input pages reserve bottom space, with one explicit selection at a time. */
class ViewerInputPanels(private val activity: VncActivity) {
    private val style = ControlStyle(activity)
    private val root = LinearLayout(activity).apply {
        id = R.id.input_pages
        orientation = LinearLayout.VERTICAL
        background = style.background(R.color.viewer_solid)
        setPadding(style.dp(6), style.dp(4), style.dp(6), style.dp(8))
        isClickable = true
        visibility = View.GONE
    }
    val container: View get() = root
    internal var selectedTab: InputTab? = null
        private set
    val isVisible get() = root.isVisible

    internal fun show(tab: InputTab) {
        require(tab != InputTab.Keyboard)
        activity.floatingTouchpad.setTabHost(null)
        root.removeAllViews()
        selectedTab = tab
        val header = LinearLayout(activity).apply {
            id = R.id.input_page_header
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.toolbar.createTabRow(keyboard = true), LinearLayout.LayoutParams(0, style.dp(48), 1f))
            addView(style.icon(R.drawable.ic_control_chevron, R.string.input_panel_close).apply {
                id = R.id.input_page_close
                setOnClickListener { activity.hideInputPanels() }
            }, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
        }
        root.addView(header)
        val body = if (tab == InputTab.Touchpad) touchpad() else shortcuts()
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = style.dp(6) })
        root.isVisible = true
        updateHeight()
        activity.toolbar.refresh()
    }

    fun hide() {
        if (!isVisible) return
        activity.floatingTouchpad.setTabHost(null)
        root.isVisible = false
        selectedTab = null
        root.removeAllViews()
    }

    fun refresh() {
        selectedTab?.let { show(it) }
    }

    fun updateHeight() {
        if (!isVisible) return
        val host = activity.binding.viewerRoot
        val height = host.height - host.paddingTop - host.paddingBottom
        if (height <= 0) return
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val wanted = minOf(style.dp(390), (height * if (landscape) .55f else .44f).toInt())
        val target = wanted.coerceAtLeast(style.dp(280)).coerceAtMost((height - style.dp(72)).coerceAtLeast(1))
        if (root.layoutParams.height != target) root.layoutParams = root.layoutParams.apply { this.height = target }
    }

    private fun run(action: RemoteAction) {
        activity.releaseInputControls()
        activity.performRemoteAction(action)
    }

    private fun touchpad(): View = LinearLayout(activity).apply {
        isClickable = true
        val rail = LinearLayout(activity).apply {
            id = R.id.touchpad_edit_actions
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(RemoteAction.COPY, RemoteAction.PASTE, RemoteAction.CUT, RemoteAction.UNDO).forEach { action ->
            rail.addView(style.button(RemoteInputEditor.actionLabel(activity, action)).apply {
                tag = "touchpad_action_${action.name.lowercase()}"
                setOnClickListener { run(action) }
            }, LinearLayout.LayoutParams(-1, style.dp(48)).apply { setMargins(0, style.dp(2), style.dp(6), style.dp(2)) })
        }
        addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(rail, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(style.dp(86), -1))
        val host = FrameLayout(activity).apply {
            id = R.id.input_touchpad_surface_host
            isClickable = true
        }
        addView(host, LinearLayout.LayoutParams(0, -1, 1f))
        activity.floatingTouchpad.setTabHost(host)
    }

    private fun shortcuts(): View = ScrollView(activity).apply {
        isFillViewport = true
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content.addView(style.label(activity.getString(R.string.input_shortcuts_hint)).apply {
            setPadding(style.dp(6), style.dp(8), style.dp(6), style.dp(12))
        })
        val config = activity.viewModel.remoteInputConfig
        listOf(RemoteAction.SWITCH_APP, RemoteAction.COPY, RemoteAction.PASTE, RemoteAction.CUT,
                RemoteAction.SELECT_ALL, RemoteAction.UNDO, RemoteAction.REDO, RemoteAction.SEARCH,
                RemoteAction.SWITCH_INPUT).chunked(2).forEach { actions ->
            val row = LinearLayout(activity)
            actions.forEach { action ->
                val label = RemoteInputEditor.actionLabel(activity, action)
                val chord = RemoteInputEditor.shortcutLabel(activity, config.shortcut(action), config.isMac)
                row.addView(style.button("$label\n$chord").apply {
                    tag = "shortcut_action_${action.name.lowercase()}"
                    maxLines = 2
                    isEnabled = config.shortcut(action) != null
                    setOnClickListener { run(action) }
                }, LinearLayout.LayoutParams(0, style.dp(64), 1f).apply { setMargins(style.dp(3), style.dp(3), style.dp(3), style.dp(3)) })
            }
            if (actions.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(row)
        }
        addView(content)
    }
}
