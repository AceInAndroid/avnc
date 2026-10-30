/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import android.view.KeyEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class RemotePlatform(val id: String) {
    AUTO("auto"), MAC("mac"), WINDOWS("windows"), GENERIC("generic");
    companion object {
        fun from(value: String) = entries.firstOrNull { it.id == value } ?: AUTO
    }
}

enum class RemoteAction {
    COPY, CUT, PASTE, SELECT_ALL, UNDO, REDO, SEARCH, SWITCH_APP, SWITCH_INPUT,
    PREVIOUS_DESKTOP, NEXT_DESKTOP, OVERVIEW, SHOW_DESKTOP, SNAP_LEFT, SNAP_RIGHT,
    LINE_START, LINE_END, PREVIOUS_WORD, NEXT_WORD, DOCK, QUICK_LOOK, TOGGLE_FULLSCREEN
}

@Serializable
data class RemoteShortcut(val key: Int, val modifiers: List<Int> = emptyList()) {
    fun isValid() = key in 1..KeyEvent.getMaxKeyCode() && !KeyEvent.isModifierKey(key) &&
            modifiers.distinct().size == modifiers.size && modifiers.all { it in MODIFIERS }
    companion object {
        val MODIFIERS = setOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT)
    }
}

/** Null fields inherit the selected platform template, including individual shortcuts. */
@Serializable
data class RemoteInputOverrides(
    val pointerSpeed: Float? = null,
    val scrollSpeed: Float? = null,
    val horizontalSpeed: Float? = null,
    val invertVertical: Boolean? = null,
    val invertHorizontal: Boolean? = null,
    val acceleration: Boolean? = null,
    val inertia: String? = null,
    val axisLock: Boolean? = null,
    val threeFinger: String? = null,
    val swapCtrlMeta: Boolean? = null,
    val shortcuts: Map<String, RemoteShortcut> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(value: String): RemoteInputOverrides =
                runCatching { json.decodeFromString<RemoteInputOverrides>(value) }.getOrDefault(RemoteInputOverrides())
    }
    fun encode(): String = json.encodeToString(this)
}

data class RemoteInputConfig(
    val platform: RemotePlatform = RemotePlatform.GENERIC,
    val pointerSpeed: Float = 1f,
    val scrollSpeed: Float = 1f,
    val horizontalSpeed: Float? = null,
    val invertVertical: Boolean = false,
    val invertHorizontal: Boolean = false,
    val acceleration: Boolean = false,
    val inertia: String = "standard",
    val axisLock: Boolean = true,
    val threeFinger: String = "pan",
    val swapCtrlMeta: Boolean = false,
    val shortcuts: Map<String, RemoteShortcut> = emptyMap(),
) {
    val isMac get() = platform == RemotePlatform.MAC
    val effectiveHorizontalSpeed get() = horizontalSpeed ?: scrollSpeed
    val systemGestures get() = threeFinger == "system" && platform in listOf(RemotePlatform.MAC, RemotePlatform.WINDOWS)
    val inertiaDecay get() = if (inertia == "short") .100f else if (isMac) .240f else .180f
    val inertiaDuration get() = if (inertia == "short") 400L else if (isMac) 1000L else 700L

    fun apply(overrides: RemoteInputOverrides): RemoteInputConfig {
        fun speed(value: Float?, fallback: Float, max: Float = 3f) = value?.takeIf { it.isFinite() }?.coerceIn(.25f, max) ?: fallback
        return copy(pointerSpeed = speed(overrides.pointerSpeed, pointerSpeed),
                scrollSpeed = speed(overrides.scrollSpeed, scrollSpeed, 6f),
                horizontalSpeed = overrides.horizontalSpeed?.takeIf { it.isFinite() }?.coerceIn(.25f, 6f) ?: horizontalSpeed,
                invertVertical = overrides.invertVertical ?: invertVertical,
                invertHorizontal = overrides.invertHorizontal ?: invertHorizontal,
                acceleration = overrides.acceleration ?: acceleration,
                inertia = overrides.inertia?.takeIf { it in listOf("off", "short", "standard") } ?: inertia,
                axisLock = overrides.axisLock ?: axisLock,
                threeFinger = overrides.threeFinger?.takeIf { it in listOf("system", "pan", "none", "legacy") } ?: threeFinger,
                swapCtrlMeta = overrides.swapCtrlMeta ?: swapCtrlMeta,
                shortcuts = shortcuts + overrides.shortcuts.filter { (name, shortcut) ->
                    RemoteAction.entries.any { it.name == name } && shortcut.isValid()
                })
    }

    fun shortcut(action: RemoteAction): RemoteShortcut? {
        shortcuts[action.name]?.let { return it }
        val primary = if (isMac) KeyEvent.KEYCODE_META_LEFT else KeyEvent.KEYCODE_CTRL_LEFT
        fun chord(key: Int, vararg mods: Int) = RemoteShortcut(key, mods.toList())
        return when (action) {
            RemoteAction.DOCK -> if (isMac) chord(KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_CTRL_LEFT) else null
            RemoteAction.QUICK_LOOK -> if (isMac) chord(KeyEvent.KEYCODE_SPACE) else null
            RemoteAction.TOGGLE_FULLSCREEN -> if (isMac) chord(KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_CTRL_LEFT, primary) else null
            RemoteAction.COPY -> chord(KeyEvent.KEYCODE_C, primary)
            RemoteAction.CUT -> chord(KeyEvent.KEYCODE_X, primary)
            RemoteAction.PASTE -> chord(KeyEvent.KEYCODE_V, primary)
            RemoteAction.SELECT_ALL -> chord(KeyEvent.KEYCODE_A, primary)
            RemoteAction.UNDO -> chord(KeyEvent.KEYCODE_Z, primary)
            RemoteAction.REDO -> if (isMac) chord(KeyEvent.KEYCODE_Z, primary, KeyEvent.KEYCODE_SHIFT_LEFT) else chord(KeyEvent.KEYCODE_Y, primary)
            RemoteAction.SEARCH -> if (isMac) chord(KeyEvent.KEYCODE_SPACE, primary) else chord(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_META_LEFT)
            RemoteAction.SWITCH_APP -> chord(KeyEvent.KEYCODE_TAB, if (isMac) primary else KeyEvent.KEYCODE_ALT_LEFT)
            RemoteAction.SWITCH_INPUT -> chord(KeyEvent.KEYCODE_SPACE, if (isMac) KeyEvent.KEYCODE_CTRL_LEFT else KeyEvent.KEYCODE_META_LEFT)
            RemoteAction.PREVIOUS_DESKTOP, RemoteAction.NEXT_DESKTOP -> {
                if (platform == RemotePlatform.GENERIC) null
                else RemoteShortcut(if (action == RemoteAction.PREVIOUS_DESKTOP) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
                        if (isMac) listOf(KeyEvent.KEYCODE_CTRL_LEFT) else listOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_CTRL_LEFT))
            }
            RemoteAction.OVERVIEW -> when (platform) {
                RemotePlatform.MAC -> chord(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CTRL_LEFT)
                RemotePlatform.WINDOWS -> chord(KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_META_LEFT)
                else -> null
            }
            RemoteAction.SHOW_DESKTOP -> when (platform) {
                RemotePlatform.MAC -> chord(KeyEvent.KEYCODE_F11)
                RemotePlatform.WINDOWS -> chord(KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_META_LEFT)
                else -> null
            }
            RemoteAction.SNAP_LEFT, RemoteAction.SNAP_RIGHT -> if (platform == RemotePlatform.WINDOWS)
                chord(if (action == RemoteAction.SNAP_LEFT) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_META_LEFT) else null
            RemoteAction.LINE_START, RemoteAction.LINE_END -> if (isMac)
                chord(if (action == RemoteAction.LINE_START) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, primary)
                else chord(if (action == RemoteAction.LINE_START) KeyEvent.KEYCODE_MOVE_HOME else KeyEvent.KEYCODE_MOVE_END)
            RemoteAction.PREVIOUS_WORD, RemoteAction.NEXT_WORD -> chord(
                    if (action == RemoteAction.PREVIOUS_WORD) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
                    if (isMac) KeyEvent.KEYCODE_ALT_LEFT else primary)
        }
    }

    companion object {
        fun defaults(platform: RemotePlatform) = RemoteInputConfig(platform = platform,
                acceleration = platform == RemotePlatform.MAC,
                inertia = if (platform == RemotePlatform.WINDOWS) "short" else "standard",
                threeFinger = if (platform in listOf(RemotePlatform.MAC, RemotePlatform.WINDOWS)) "system" else "pan")
    }
}
