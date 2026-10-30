/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.gaurav.avnc.R
import com.gaurav.avnc.model.DisplayViewMode
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.model.RemoteDisplay
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.StreamingPreset
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference

/** Shared Mac command row for the floating capsule and the app-owned keyboard. */
class MacViewerControls(private val activity: VncActivity) {
    private val vm = activity.viewModel
    private val style = ControlStyle(activity)
    private val rows = mutableListOf<WeakReference<LinearLayout>>()
    private var activeDialog: AlertDialog? = null
    private var manualEditor: ManualDisplayEditor? = null
    private var refreshDialog: (() -> Unit)? = null
    private var initialized = false
    val isMac get() = vm.remoteInputConfig.isMac

    fun initialize() {
        if (initialized) return
        initialized = true
        vm.viewerSettingsVersion.observe(activity) { refresh() }
        vm.displayLayout.observe(activity) { refresh() }
        vm.streamingPreset.observe(activity) { refresh() }
        vm.streamingPresetPending.observe(activity) { refresh() }
        vm.streamingPresetFailed.observe(activity) { refresh() }
        vm.inPiPMode.observe(activity) { if (it) dismiss() }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = dismiss()
        })
    }

    fun createRow(keyboard: Boolean = false): HorizontalScrollView {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val scroll = HorizontalScrollView(activity).apply {
            id = R.id.mac_controls_row
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row, android.widget.FrameLayout.LayoutParams(-2, -1))
        }
        fun action(label: Int, icon: Int, id: Int, input: Boolean = false, run: () -> Unit) {
            val b = style.button(activity.getString(label)).apply {
                this.id = id
                tag = if (input) "mac_remote_input" else "mac_local_control"
                this.icon = AppCompatResources.getDrawable(activity, icon)
                iconSize = style.dp(20); iconPadding = style.dp(5)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconTint = ColorStateList.valueOf(style.ink)
                minimumWidth = style.dp(48)
                cornerRadius = style.dp(24)
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                setOnClickListener { run() }
                ViewCompat.setTooltipText(this, activity.getString(label))
                if (id == R.id.mac_dock) setOnLongClickListener { showDockSettings(); true }
            }
            row.addView(b, LinearLayout.LayoutParams(-2, style.dp(48)))
        }
        action(R.string.mac_action_dock, R.drawable.ic_mac_dock, R.id.mac_dock, true) { activity.performMacDock() }
        action(R.string.mac_mission_control, R.drawable.ic_mac_mission, R.id.mac_mission_control, true) { activity.performRemoteAction(RemoteAction.OVERVIEW) }
        action(R.string.mac_displays, R.drawable.ic_mac_displays, R.id.mac_displays) { showDisplays() }
        action(R.string.mac_quality, R.drawable.ic_mac_quality, R.id.mac_quality) { showQuality() }
        action(R.string.control_keyboard, R.drawable.ic_control_keyboard, R.id.keyboard_btn, true) {
            if (keyboard) activity.hideComputerKeyboard() else activity.showKeyboard()
        }
        action(R.string.control_touchpad, R.drawable.ic_control_touchpad, if (keyboard) R.id.keyboard_touchpad else R.id.touchpad_btn, true) { activity.toolbar.toggleTouchpad() }
        action(R.string.control_more, R.drawable.ic_control_more, if (keyboard) R.id.keyboard_more else R.id.controls_more) { activity.toolbar.open() }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRow(row, scroll.width) }
        rows += WeakReference(row)
        updateRow(row, activity.resources.displayMetrics.widthPixels)
        return scroll
    }

    fun refresh() {
        rows.removeAll { it.get() == null }
        rows.forEach { it.get()?.let { row -> updateRow(row, (row.parent as View).width) } }
        refreshDialog?.invoke()
    }

    private fun updateRow(row: LinearLayout, width: Int) {
        val buttons = (0 until row.childCount).map { row.getChildAt(it) as MaterialButton }
        if (buttons.isEmpty()) return
        val labels = buttons.map { b -> activity.getString(when (b.id) {
            R.id.mac_dock -> R.string.mac_action_dock
            R.id.mac_mission_control -> R.string.mac_mission_control
            R.id.mac_displays -> R.string.mac_displays
            R.id.mac_quality -> when (vm.streamingPreset.value) {
                StreamingPreset.SMOOTH -> R.string.mac_quality_smooth
                StreamingPreset.HD -> R.string.mac_quality_hd
                else -> R.string.mac_quality
            }
            R.id.keyboard_btn -> R.string.control_keyboard
            R.id.touchpad_btn, R.id.keyboard_touchpad -> R.string.control_touchpad
            else -> R.string.control_more
        }) }
        val available = (width - row.paddingLeft - row.paddingRight).coerceAtLeast(0)
        // Every icon gets an equal share of the capsule. Below seven 48dp targets,
        // keep their minimum width and let the parent scroll instead of shrinking.
        val total = maxOf(available, buttons.size * style.dp(48))
        val cellWidth = total / buttons.size
        val remainder = total % buttons.size
        val labelWidth = buttons.indices.maxOf { i ->
            kotlin.math.ceil(buttons[i].paint.measureText(labels[i]).toDouble()).toInt() +
                    buttons[i].paddingLeft + buttons[i].paddingRight + style.dp(25)
        }
        val compact = available < style.dp(760) || cellWidth < labelWidth ||
                activity.resources.configuration.fontScale > 1.3f
        val canInput = vm.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT
        buttons.forEachIndexed { i, b ->
            val desiredWidth = cellWidth + if (i < remainder) 1 else 0
            if (b.layoutParams.width != desiredWidth) b.layoutParams = b.layoutParams.apply { this.width = desiredWidth }
            val text = if (compact) "" else labels[i]
            if (b.text.toString() != text) b.text = text
            b.contentDescription = labels[i]
            val iconPadding = if (compact) 0 else style.dp(5)
            if (b.iconPadding != iconPadding) b.iconPadding = iconPadding
            b.isEnabled = b.tag != "mac_remote_input" || canInput
            b.alpha = if (b.isEnabled) 1f else .4f
            val selected = when (b.id) {
                R.id.touchpad_btn, R.id.keyboard_touchpad -> activity.floatingTouchpad.isExpanded
                R.id.keyboard_btn -> activity.computerKeyboard.isVisible
                else -> false
            }
            b.isSelected = selected
            b.setTextColor(if (selected) style.blue else style.ink)
            b.iconTint = ColorStateList.valueOf(if (selected) style.blue else style.ink)
            ViewCompat.setTooltipText(b, b.contentDescription)
        }
    }

    fun dismiss() {
        manualEditor?.dismiss()
        manualEditor = null
        activeDialog?.dismiss()
        activeDialog = null
        refreshDialog = null
    }

    private fun dialog(title: Int, content: View): AlertDialog {
        dismiss()
        activity.releaseInputControls()
        val context = ContextThemeWrapper(activity, R.style.ThemeOverlay_RemoteInput)
        val result = MaterialAlertDialogBuilder(context)
                .setTitle(title).setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton(R.string.control_done, null)
                .setBackground(ControlStyle(context).background()).create()
        activeDialog = result
        result.setOnDismissListener { if (activeDialog === result) { activeDialog = null; refreshDialog = null } }
        result.show()
        return result
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(style.dp(18), style.dp(4), style.dp(18), style.dp(12))
    }

    private fun text(parent: LinearLayout, value: String) {
        parent.addView(style.label(value, 14f).apply { setPadding(0, style.dp(8), 0, style.dp(12)) })
    }

    private fun choice(parent: LinearLayout, label: String, selected: Boolean = false, enabled: Boolean = true, run: () -> Unit) {
        parent.addView(style.button(label + if (selected) "  ✓" else "").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = style.dp(48)
            setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
            isEnabled = enabled
            style.select(this, selected)
            setOnClickListener { run() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = style.dp(4) })
    }

    fun showDockSettings() {
        val content = column()
        dialog(R.string.mac_dock_activation, content)
        fun render() {
            content.removeAllViews()
            listOf("shortcut" to R.string.mac_dock_shortcut, "bottom" to R.string.mac_dock_bottom,
                    "left" to R.string.mac_dock_left, "right" to R.string.mac_dock_right).forEach { (method, label) ->
                choice(content, activity.getString(label), vm.profile.dockActivation == method) { activity.setDockActivation(method) }
            }
            text(content, activity.getString(R.string.mac_dock_hint))
        }
        refreshDialog = ::render
        render()
    }

    private fun displayName(display: RemoteDisplay, index: Int) = display.name.ifBlank { activity.getString(R.string.mac_display_name, index + 1) }

    fun showDisplays() {
        val content = column()
        dialog(R.string.mac_displays, content)
        fun render() {
            content.removeAllViews()
            val layout = vm.displayLayout.value ?: return
            if (layout.isValid()) {
                content.addView(DisplayLayoutDiagram(activity).apply {
                    setLayout(layout, vm.selectedDisplay?.id)
                    onSelect = { activity.selectDisplay(it) }
                }, LinearLayout.LayoutParams(-1, style.dp(180)))
                choice(content, activity.getString(R.string.mac_display_single), vm.displayViewMode == DisplayViewMode.SINGLE) { activity.setDisplayViewMode(DisplayViewMode.SINGLE) }
                choice(content, activity.getString(R.string.mac_display_all), vm.displayViewMode == DisplayViewMode.ALL) { activity.setDisplayViewMode(DisplayViewMode.ALL) }
                layout.displays.forEachIndexed { i, display ->
                    choice(content, activity.getString(R.string.mac_display_resolution, displayName(display, i), display.width, display.height),
                            vm.displayViewMode == DisplayViewMode.SINGLE && vm.selectedDisplay?.id == display.id) { activity.selectDisplay(display.id) }
                }
            } else text(content, activity.getString(if (layout.source == "manual_invalid") R.string.mac_display_invalid else R.string.mac_display_missing))
            choice(content, activity.getString(R.string.mac_display_manual)) {
                dismiss()
                val width = vm.frameState.fbWidth.toInt()
                val height = vm.frameState.fbHeight.toInt()
                if (width <= 0 || height <= 0) Toast.makeText(activity, R.string.mac_display_wait, Toast.LENGTH_SHORT).show()
                else ManualDisplayEditor(activity, width, height, layout).also { manualEditor = it; it.show() }
            }
        }
        refreshDialog = ::render
        render()
    }

    fun showQuality() {
        val content = column()
        dialog(R.string.mac_quality, content)
        fun render() {
            content.removeAllViews()
            val pending = vm.streamingPresetPending.value == true
            if (pending) text(content, activity.getString(R.string.mac_quality_pending))
            if (vm.streamingPresetFailed.value == true) text(content, activity.getString(R.string.mac_quality_failed))
            if (vm.streamingPreset.value == StreamingPreset.LEGACY) text(content, activity.getString(R.string.mac_quality_legacy))
            listOf(Triple(StreamingPreset.SMOOTH, R.string.mac_quality_smooth, R.string.mac_quality_smooth_hint),
                    Triple(StreamingPreset.HD, R.string.mac_quality_hd, R.string.mac_quality_hd_hint)).forEach { (preset, label, hint) ->
                choice(content, activity.getString(label), vm.streamingPreset.value == preset, !pending) { activity.setStreamingPreset(preset) }
                text(content, activity.getString(hint))
            }
            text(content, activity.getString(R.string.mac_quality_scope))
        }
        refreshDialog = ::render
        render()
    }
}
