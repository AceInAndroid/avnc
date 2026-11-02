/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.prefs

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.gaurav.avnc.R
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.model.RemoteInputConfig
import com.gaurav.avnc.model.RemoteInputOverrides
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.model.RemoteShortcut
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.ui.vnc.ControlStyle
import com.gaurav.avnc.util.RemoteInputPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/** Editors share the same inheritance rules in app settings, connection editing and a live session. */
object RemoteInputEditor {
    private fun themed(context: Context) = ContextThemeWrapper(context, R.style.ThemeOverlay_RemoteInput)
    private fun dialog(context: Context) = MaterialAlertDialogBuilder(context)
            .setBackground(ControlStyle(context).background())

    fun showSession(activity: VncActivity) {
        Editor(activity, activity.viewModel.profile, null,
                activity.viewModel.client?.isAppleServer() == true) { activity.applyRemoteInputSettings() }.show()
    }

    fun showProfile(context: Context, profile: ServerProfile, onChanged: () -> Unit = {}) {
        Editor(context, profile, null, false, onChanged).show()
    }

    fun showTemplate(baseContext: Context, platform: RemotePlatform, onChanged: () -> Unit = {}) {
        require(platform != RemotePlatform.AUTO)
        Editor(themed(baseContext), null, platform, false, onChanged).show()
    }

    fun showTemplates(baseContext: Context) {
        val context = themed(baseContext)
        val platforms = listOf(RemotePlatform.MAC, RemotePlatform.WINDOWS, RemotePlatform.GENERIC)
        dialog(context)
                .setTitle(R.string.remote_input_templates)
                .setItems(platforms.map { platformLabel(context, it) }.toTypedArray()) { _, index ->
                    Editor(context, null, platforms[index], false) {}.show()
                }
                .setNegativeButton(R.string.remote_input_cancel, null)
                .show()
    }

    fun platformLabel(context: Context, platform: RemotePlatform): String = context.getString(when (platform) {
        RemotePlatform.AUTO -> R.string.remote_input_auto
        RemotePlatform.MAC -> R.string.remote_input_mac
        RemotePlatform.WINDOWS -> R.string.remote_input_windows
        RemotePlatform.GENERIC -> R.string.remote_input_generic
    })

    fun actionLabel(context: Context, action: RemoteAction): String = context.getString(when (action) {
        RemoteAction.COPY -> R.string.remote_input_copy
        RemoteAction.CUT -> R.string.remote_input_cut
        RemoteAction.PASTE -> R.string.remote_input_paste
        RemoteAction.SELECT_ALL -> R.string.remote_input_select_all
        RemoteAction.UNDO -> R.string.remote_input_undo
        RemoteAction.REDO -> R.string.remote_input_redo
        RemoteAction.SEARCH -> R.string.remote_input_search
        RemoteAction.SWITCH_APP -> R.string.remote_input_switch_app
        RemoteAction.SWITCH_INPUT -> R.string.remote_input_switch_input
        RemoteAction.PREVIOUS_DESKTOP -> R.string.remote_input_previous_desktop
        RemoteAction.NEXT_DESKTOP -> R.string.remote_input_next_desktop
        RemoteAction.OVERVIEW -> R.string.remote_input_overview
        RemoteAction.SHOW_DESKTOP -> R.string.remote_input_show_desktop
        RemoteAction.SNAP_LEFT -> R.string.remote_input_snap_left
        RemoteAction.SNAP_RIGHT -> R.string.remote_input_snap_right
        RemoteAction.LINE_START -> R.string.remote_input_line_start
        RemoteAction.LINE_END -> R.string.remote_input_line_end
        RemoteAction.PREVIOUS_WORD -> R.string.remote_input_previous_word
        RemoteAction.NEXT_WORD -> R.string.remote_input_next_word
        RemoteAction.DOCK -> R.string.mac_action_dock
        RemoteAction.QUICK_LOOK -> R.string.mac_action_quick_look
        RemoteAction.TOGGLE_FULLSCREEN -> R.string.mac_action_fullscreen
    })

    fun shortcutLabel(context: Context, shortcut: RemoteShortcut?, mac: Boolean): String {
        if (shortcut == null) return context.getString(R.string.remote_input_not_configured)
        return (shortcut.modifiers.map { modifierLabel(context, it, mac) } + keyLabel(shortcut.key)).joinToString(" + ")
    }

    private fun modifierLabel(context: Context, code: Int, mac: Boolean) = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> if (mac) context.getString(R.string.remote_input_control) else "Ctrl"
        KeyEvent.KEYCODE_ALT_LEFT -> if (mac) context.getString(R.string.remote_input_option) else "Alt"
        KeyEvent.KEYCODE_META_LEFT -> if (mac) context.getString(R.string.remote_input_cmd) else "Win"
        else -> "Shift"
    }

    private fun keyLabel(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> "←"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
    }

    private class Editor(baseContext: Context, private val profile: ServerProfile?,
                         private val template: RemotePlatform?, private val appleServer: Boolean,
                         private val onChanged: () -> Unit) {
        private val context = themed(baseContext)
        private val preferences = RemoteInputPreferences(context)
        private val density = context.resources.displayMetrics.density
        private fun dp(value: Int) = (value * density).roundToInt()
        private fun text(id: Int) = context.getString(id)
        private val config: RemoteInputConfig get() = if (template != null) preferences.template(template)
                else preferences.resolve(checkNotNull(profile), appleServer)
        private val overrides: RemoteInputOverrides get() = if (template != null) preferences.getOverrides(template)
                else RemoteInputOverrides.decode(checkNotNull(profile).remoteInputOverrides)
        private val inheritanceLabel get() = if (template != null) R.string.remote_input_follow_default else R.string.remote_input_follow_template
        private val main = column()
        private var renderMain: () -> Unit = {}

        private fun column() = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }

        fun show() {
            renderMain = {
                main.removeAllViews()
                val current = config
                if (profile != null) {
                    row(main, R.string.remote_input_platform,
                            platformLabel(context, RemotePlatform.from(profile.remotePlatform)), "platform") { choosePlatform() }
                    if (RemotePlatform.from(profile.remotePlatform) == RemotePlatform.AUTO)
                        note(main, if (appleServer) R.string.remote_input_auto_mac else R.string.remote_input_auto_unknown)
                    if (!profile.isSaved()) note(main, R.string.remote_input_unsaved)
                } else note(main, R.string.remote_input_template_help)
                speedRow(main, R.string.remote_input_pointer_speed, current.pointerSpeed, overrides.pointerSpeed, "pointer_speed") {
                    update(overrides.copy(pointerSpeed = it))
                }
                speedRow(main, R.string.remote_input_scroll_speed, current.scrollSpeed, overrides.scrollSpeed, "scroll_speed") {
                    update(overrides.copy(scrollSpeed = it))
                }
                row(main, R.string.remote_input_direction,
                        valueLabel(overrides.invertVertical, text(if (current.invertVertical) R.string.remote_input_reversed else R.string.remote_input_natural)),
                        "direction") {
                    choose(R.string.remote_input_direction, listOf(false, true), current.invertVertical,
                            { text(if (it) R.string.remote_input_reversed else R.string.remote_input_natural) }) {
                        update(overrides.copy(invertVertical = it))
                    }
                }
                note(main, R.string.remote_input_direction_help)
                row(main, R.string.remote_input_advanced, null, "advanced") { showAdvanced() }
                row(main, R.string.remote_input_shortcuts, null, "shortcuts") { showShortcuts() }
                row(main, if (template != null) R.string.remote_input_restore_template else R.string.remote_input_follow_template,
                        null, "reset") { update(RemoteInputOverrides()) }
            }
            renderMain()
            showPanel(if (template == null) text(R.string.remote_input_this_computer)
                    else context.getString(R.string.remote_input_template_title, platformLabel(context, template)), main)
        }

        private fun update(value: RemoteInputOverrides) {
            if (template != null) preferences.saveTemplate(template, value)
            else checkNotNull(profile).remoteInputOverrides = value.encode()
            onChanged()
            renderMain()
        }

        private fun choosePlatform() {
            val platforms = RemotePlatform.entries.toList()
            dialog(context)
                    .setTitle(R.string.remote_input_platform)
                    .setSingleChoiceItems(platforms.map { platformLabel(context, it) }.toTypedArray(),
                            platforms.indexOf(RemotePlatform.from(checkNotNull(profile).remotePlatform))) { dialog, index ->
                        profile.remotePlatform = platforms[index].id
                        onChanged()
                        renderMain()
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.remote_input_cancel, null).show()
        }

        private fun showAdvanced() {
            val panel = column()
            fun render() {
                panel.removeAllViews()
                val current = config
                speedRow(panel, R.string.remote_input_horizontal_speed, current.effectiveHorizontalSpeed,
                        overrides.horizontalSpeed, "horizontal_speed",
                        if (template != null) R.string.remote_input_follow_vertical else inheritanceLabel) {
                    update(overrides.copy(horizontalSpeed = it)); showAdvancedRefresh(panel)
                }
                booleanRow(panel, R.string.remote_input_invert_horizontal, current.invertHorizontal, overrides.invertHorizontal, "invert_horizontal") {
                    update(overrides.copy(invertHorizontal = it)); showAdvancedRefresh(panel)
                }
                booleanRow(panel, R.string.remote_input_acceleration, current.acceleration, overrides.acceleration, "acceleration") {
                    update(overrides.copy(acceleration = it)); showAdvancedRefresh(panel)
                }
                note(panel, R.string.remote_input_acceleration_help)
                row(panel, R.string.remote_input_inertia, valueLabel(overrides.inertia, inertiaLabel(current.inertia)), "inertia") {
                    choose(R.string.remote_input_inertia, listOf("off", "short", "standard"), current.inertia, ::inertiaLabel) {
                        update(overrides.copy(inertia = it)); showAdvancedRefresh(panel)
                    }
                }
                booleanRow(panel, R.string.remote_input_axis_lock, current.axisLock, overrides.axisLock, "axis_lock") {
                    update(overrides.copy(axisLock = it)); showAdvancedRefresh(panel)
                }
                row(panel, R.string.remote_input_three_finger, valueLabel(overrides.threeFinger, gestureLabel(current.threeFinger)), "three_finger") {
                    choose(R.string.remote_input_three_finger,
                            (if (current.platform == RemotePlatform.GENERIC) emptyList() else listOf("system")) +
                                    listOf("pan", "none", "legacy"), current.threeFinger, ::gestureLabel) {
                        update(overrides.copy(threeFinger = it)); showAdvancedRefresh(panel)
                    }
                }
                booleanRow(panel, R.string.remote_input_swap_modifiers, current.swapCtrlMeta, overrides.swapCtrlMeta, "swap_modifiers") {
                    update(overrides.copy(swapCtrlMeta = it)); showAdvancedRefresh(panel)
                }
                note(panel, R.string.remote_input_swap_modifiers_help)
            }
            panel.tag = { render() }
            render()
            showPanel(text(R.string.remote_input_advanced), panel)
        }

        @Suppress("UNCHECKED_CAST")
        private fun showAdvancedRefresh(panel: LinearLayout) = (panel.tag as () -> Unit).invoke()

        private fun showShortcuts() {
            val panel = column()
            fun render() {
                panel.removeAllViews()
                note(panel, R.string.remote_input_shortcuts_help)
                RemoteAction.entries.forEach { action ->
                    val shortcut = config.shortcut(action)
                    row(panel, actionLabel(context, action), valueLabel(overrides.shortcuts[action.name],
                            shortcutLabel(context, shortcut, config.isMac)), "shortcut_${action.name}") {
                        editShortcut(action) {
                            val shortcuts = overrides.shortcuts.toMutableMap()
                            if (it == null) shortcuts.remove(action.name) else shortcuts[action.name] = it
                            update(overrides.copy(shortcuts = shortcuts))
                            render()
                        }
                    }
                }
            }
            render()
            showPanel(text(R.string.remote_input_shortcuts), panel)
        }

        private fun editShortcut(action: RemoteAction, changed: (RemoteShortcut?) -> Unit) {
            val current = config.shortcut(action)
            var key = current?.key ?: KeyEvent.KEYCODE_A
            val panel = column()
            val keyButton = row(panel, R.string.remote_input_key, keyLabel(key), "shortcut_key") {}
            keyButton.setOnClickListener {
                val keys = ((KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) +
                        (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) + listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_TAB,
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
                        KeyEvent.KEYCODE_INSERT)).distinct()
                dialog(context).setTitle(R.string.remote_input_key)
                        .setSingleChoiceItems(keys.map(::keyLabel).toTypedArray(), keys.indexOf(key)) { dialog, index ->
                            key = keys[index]
                            keyButton.text = text(R.string.remote_input_key) + "\n" + keyLabel(key)
                            dialog.dismiss()
                        }.setNegativeButton(R.string.remote_input_cancel, null).show()
            }
            note(panel, R.string.remote_input_modifiers)
            val checks = RemoteShortcut.MODIFIERS.map { code ->
                code to CheckBox(context).apply {
                    text = modifierLabel(context, code, config.isMac)
                    isChecked = current?.modifiers?.contains(code) == true
                    minimumHeight = dp(48)
                    panel.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
            }
            dialog(context).setTitle(actionLabel(context, action)).setView(panel)
                    .setPositiveButton(R.string.remote_input_apply) { _, _ ->
                        changed(RemoteShortcut(key, checks.filter { it.second.isChecked }.map { it.first }))
                    }
                    .setNeutralButton(inheritanceLabel) { _, _ -> changed(null) }
                    .setNegativeButton(R.string.remote_input_cancel, null).show()
        }

        private fun speedRow(panel: LinearLayout, label: Int, effective: Float, custom: Float?, tag: String,
                             resetLabel: Int = inheritanceLabel, changed: (Float?) -> Unit) {
            val summary = if (tag == "horizontal_speed" && template != null && custom == null)
                text(R.string.remote_input_follow_vertical) + " · " + speed(effective)
            else valueLabel(custom, speed(effective))
            row(panel, label, summary, tag) {
                val body = column()
                val value = TextView(context).apply { text = speed(effective); textSize = 20f; gravity = Gravity.CENTER }
                body.addView(value, LinearLayout.LayoutParams(-1, dp(48)))
                var selected = effective
                val maximum = if (tag == "pointer_speed") 3f else 10f
                body.addView(SeekBar(context).apply {
                    max = ((maximum - .25f) / .05f).roundToInt()
                    progress = ((effective.coerceIn(.25f, maximum) - .25f) / .05f).roundToInt()
                    contentDescription = text(label)
                    minimumHeight = dp(48)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            selected = .25f + progress * .05f
                            value.text = speed(selected)
                        }
                        override fun onStartTrackingTouch(bar: SeekBar) {}
                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    })
                }, LinearLayout.LayoutParams(-1, dp(48)))
                dialog(context).setTitle(label).setView(body)
                        .setPositiveButton(R.string.remote_input_apply) { _, _ -> changed(selected) }
                        .setNeutralButton(resetLabel) { _, _ -> changed(null) }
                        .setNegativeButton(R.string.remote_input_cancel, null).show()
            }
        }

        private fun booleanRow(panel: LinearLayout, label: Int, effective: Boolean, custom: Boolean?, tag: String,
                               changed: (Boolean?) -> Unit) {
            row(panel, label, valueLabel(custom, onOff(effective)), tag) {
                choose(label, listOf(false, true), effective, ::onOff, changed)
            }
        }

        private fun <T> choose(label: Int, values: List<T>, selected: T, format: (T) -> String, changed: (T?) -> Unit) {
            dialog(context).setTitle(label)
                    .setSingleChoiceItems(values.map(format).toTypedArray(), values.indexOf(selected)) { dialog, index ->
                        changed(values[index]); dialog.dismiss()
                    }
                    .setNeutralButton(inheritanceLabel) { _, _ -> changed(null) }
                    .setNegativeButton(R.string.remote_input_cancel, null).show()
        }

        private fun row(panel: LinearLayout, label: Int, summary: String?, tag: String, action: () -> Unit) =
                row(panel, text(label), summary, tag, action)

        private fun row(panel: LinearLayout, label: String, summary: String?, tag: String, action: () -> Unit): MaterialButton {
            return MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label + (summary?.let { "\n$it" } ?: "")
                isAllCaps = false
                cornerRadius = dp(12)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 14f
                minHeight = dp(56)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                this.tag = "remote_input_$tag"
                setOnClickListener { action() }
                panel.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }

        private fun note(panel: LinearLayout, label: Int) {
            panel.addView(TextView(context).apply {
                text = context.getString(label)
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), dp(12))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        private fun showPanel(title: String, panel: LinearLayout) {
            dialog(context).setTitle(title)
                    .setView(ScrollView(context).apply { isFillViewport = false; addView(panel) })
                    .setPositiveButton(R.string.remote_input_done, null).show()
        }

        private fun valueLabel(custom: Any?, value: String): String = context.getString(
                if (custom != null) R.string.remote_input_custom
                else if (template != null) R.string.remote_input_recommended else R.string.remote_input_inherited, value)
        private fun speed(value: Float) = context.getString(R.string.remote_input_speed_value, value)
        private fun onOff(value: Boolean) = text(if (value) R.string.remote_input_on else R.string.remote_input_off)
        private fun inertiaLabel(value: String) = text(when (value) {
            "off" -> R.string.remote_input_off
            "short" -> R.string.remote_input_inertia_short
            else -> R.string.remote_input_inertia_standard
        })
        private fun gestureLabel(value: String) = text(when (value) {
            "system" -> R.string.remote_input_system_gestures
            "pan" -> R.string.remote_input_pan
            "legacy" -> R.string.remote_input_legacy
            else -> R.string.remote_input_none
        })
    }
}
