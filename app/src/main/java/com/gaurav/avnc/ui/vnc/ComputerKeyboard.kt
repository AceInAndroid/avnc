/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.os.Build
import androidx.core.view.isVisible
import com.gaurav.avnc.R
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.google.android.material.button.MaterialButton

/** App-owned computer keyboard. Its host reserves layout space; this view never opens an IME. */
class ComputerKeyboard(private val activity: VncActivity, private val inputHandler: InputHandler) {
    private enum class Page { Common, Functions, Symbols }
    private enum class Modifier { Armed, Locked }
    private data class Key(val label: String, val code: Int, val shifted: Boolean = false,
                           val description: String = label)

    private val style = ControlStyle(activity)
    private val root = FrameLayout(activity).apply {
        setPadding(dp(4), dp(4), dp(4), dp(8))
        background = style.background(R.color.viewer_solid, 24)
        visibility = View.GONE
        isClickable = true
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    val container: View get() = root
    val isVisible: Boolean get() = root.isVisible
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    // Keep the inactive keyboard out of the viewer hierarchy, including accessibility searches.
    // Cache its content separately so reopening preserves the selected page without rebuilding it.
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val keysArea = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var initialized = false
    private val modifiers = linkedMapOf<Int, Modifier>()
    private val modifierButtons = mutableMapOf<Int, MaterialButton>()
    private val letterButtons = mutableMapOf<MaterialButton, Key>()
    private val pageButtons = mutableMapOf<Page, MaterialButton>()
    private var page = Page.Common
    private var extraSymbols = false
    private var measuredContentWidth = 0
    private var capsLock = false
    private var repeatedKey: Key? = null
    private var repeatView: View? = null
    private var repeatCount = 0
    private var repeatDownTime = 0L
    private var suppressRepeatClick = false
    private val repeat = object : Runnable {
        override fun run() {
            val key = repeatedKey ?: return
            if (!isVisible) {
                stopRepeating()
                return
            }
            send(key, true, ++repeatCount, repeatDownTime)
            root.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    init {
        inputHandler.onAfterKeyEventListeners += { event ->
            if (event.action == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(event.keyCode) &&
                event.keyCode != KeyEvent.KEYCODE_CAPS_LOCK)
                releaseOneShotModifiers()
        }
    }

    private fun ensureContent() {
        if (initialized) return
        initialized = true
        renderContent()
    }

    private fun renderContent() {
        content.removeAllViews()
        content.addView(keysArea)
        renderPage()
    }

    // Selection for the shared main tabs is owned by Toolbar.
    fun updateTouchpadSelection(@Suppress("UNUSED_PARAMETER") selected: Boolean) = Unit

    /** Limit desktop-wide key stretching without changing the keyboard's reserved bottom space. */
    fun updateWidth() {
        val width = minOf(activity.binding.keyboardContentHost.width - dp(8), dp(1100)).coerceAtLeast(0)
        if (width == 0 || content.parent != root) return
        if (width != measuredContentWidth) {
            measuredContentWidth = width
            content.layoutParams = FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL)
            updateSelection()
        }
        val viewport = keysArea.findViewWithTag<ScrollView>("keyboard_vertical_deck") ?: return
        val host = activity.binding.viewerRoot
        val available = host.height - host.paddingTop - host.paddingBottom
        if (available <= 0) return
        val naturalDeck = rowHeight() * if (page == Page.Common) 5 else 4
        // Keep a visible remote region on short landscape windows without shrinking key targets.
        val height = minOf(naturalDeck, (available - dp(72 + 108)).coerceAtLeast(dp(48)))
        if (viewport.layoutParams.height != height)
            viewport.layoutParams = viewport.layoutParams.apply { this.height = height }
    }

    fun show() {
        if (isVisible) return
        ensureContent()
        root.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        measuredContentWidth = 0
        root.post { updateWidth() }
        root.isVisible = true
        onVisibilityChanged?.invoke(true)
    }

    fun hide() {
        releaseKeys()
        if (!isVisible) return
        root.isVisible = false
        root.removeView(content)
        onVisibilityChanged?.invoke(false)
    }

    /** Release before the input handler is torn down, even if the view is already hidden. */
    fun releaseKeys() {
        root.cancelPendingInputEvents()
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).let {
            root.dispatchTouchEvent(it)
            it.recycle()
        }
        stopRepeating()
        modifiers.keys.toList().forEach { code -> send(Key("", code), false) }
        modifiers.clear()
        // Caps Lock is a remote toggle, not a held key. Only explicit taps change it;
        // synthesizing an unlock during view-only/focus transitions can desynchronize it.
        updateSelection()
    }

    fun onDisconnected() = hide()

    /** Platform changes update cached keys without changing the user's keyboard visibility/page. */
    fun onRemoteInputChanged() {
        releaseKeys()
        if (initialized) {
            renderContent()
            measuredContentWidth = 0
            root.post { updateWidth() }
        }
    }

    fun onConfigurationChanged() {
        releaseKeys()
        // Activity handles configuration changes without recreation. The keyboard may never
        // have been opened, or its cached content may currently be detached from the host.
        if (initialized) {
            renderContent()
            measuredContentWidth = 0
            root.post { updateWidth() }
        }
    }

    private fun modifier(label: String, code: Int) = button(label).apply {
        tag = "keyboard_key_$code"
        setOnClickListener { toggleModifier(code, lock = false) }
        setOnLongClickListener { toggleModifier(code, lock = true); true }
        modifierButtons[code] = this
    }

    private fun renderPage() {
        stopRepeating()
        keysArea.removeAllViews()
        modifierButtons.clear()
        letterButtons.clear()
        pageButtons.clear()
        val header = row(48).apply {
            tag = "keyboard_header"
            addView(activity.toolbar.createTabRow(keyboard = true), LinearLayout.LayoutParams(0, -1, 1f))
            addView(style.icon(R.drawable.ic_control_chevron, R.string.computer_keyboard_close).apply {
                tag = "keyboard_close"; setOnClickListener { hide() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        keysArea.addView(header)
        addFunctionKeys()
        // Scroll the aligned physical rows together when the viewport cannot fit 48dp keys.
        val deck = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(if (page == Page.Common) 840 else 680)
        }
        val scroll = HorizontalScrollView(activity).apply {
            tag = "keyboard_key_deck"
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(deck, FrameLayout.LayoutParams(-2, -2))
        }
        keysArea.addView(ScrollView(activity).apply {
            tag = "keyboard_vertical_deck"
            isFillViewport = false
            addView(scroll, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        when (page) {
            Page.Common -> {
                val numbers = row().apply { tag = "keyboard_number_row" }
                "`1234567890-=".forEach { numbers.addView(keyButton(characterKey(it)), cell()) }
                numbers.addView(keyButton(backspace()), cell(2f))
                deck.addView(numbers)
                val upper = row().apply { tag = "keyboard_qwerty_row" }
                upper.addView(keyButton(Key("Tab", KeyEvent.KEYCODE_TAB)), cell(1.5f))
                "qwertyuiop[]".forEach { upper.addView(keyButton(characterKey(it)), cell()) }
                upper.addView(keyButton(characterKey('\\')), cell(1.5f))
                deck.addView(upper)
                val home = row().apply { tag = "keyboard_home_row" }
                home.addView(capsButton(), cell(1.75f))
                "asdfghjkl;'".forEach { home.addView(keyButton(characterKey(it)), cell()) }
                home.addView(keyButton(Key(if (activity.viewModel.remoteInputConfig.isMac) "return" else "Enter",
                        KeyEvent.KEYCODE_ENTER, description = activity.getString(R.string.computer_keyboard_enter))), cell(2.25f))
                deck.addView(home)
                val lower = row().apply { tag = "keyboard_shift_row" }
                lower.addView(modifier("⇧", KeyEvent.KEYCODE_SHIFT_LEFT), cell(2.25f))
                "zxcvbnm,./".forEach { lower.addView(keyButton(characterKey(it)), cell()) }
                lower.addView(modifier("⇧", KeyEvent.KEYCODE_SHIFT_RIGHT), cell(1.75f))
                lower.addView(keyButton(arrow(KeyEvent.KEYCODE_DPAD_UP)), cell())
                deck.addView(lower)
            }
            Page.Symbols -> {
                addKeys(deck, (if (extraSymbols) "!@#$%^&*()" else "1234567890").map { characterKey(it) })
                addKeys(deck, (if (extraSymbols) "_+{}\\|~<>" else "-/:;()$&@\"").map { characterKey(it) })
                val third = row()
                third.addView(button(if (extraSymbols) "123" else "#+=" , activity.getString(R.string.control_symbol_more)).apply {
                    tag = "keyboard_symbols_more"
                    setOnClickListener { extraSymbols = !extraSymbols; renderPage() }
                }, cell(1.7f))
                (if (extraSymbols) "[]=*`'?!" else ".,?!'=+").forEach { third.addView(keyButton(characterKey(it)), cell()) }
                third.addView(keyButton(backspace()), cell(1.7f))
                deck.addView(third)
            }
            Page.Functions -> {
                val nav = row()
                nav.addView(capsButton(), cell())
                listOf(Key("Home", KeyEvent.KEYCODE_MOVE_HOME), Key("↑", KeyEvent.KEYCODE_DPAD_UP, description = activity.getString(R.string.computer_keyboard_up)),
                       Key("End", KeyEvent.KEYCODE_MOVE_END), Key("PgUp", KeyEvent.KEYCODE_PAGE_UP), Key("Ins", KeyEvent.KEYCODE_INSERT)).forEach {
                    nav.addView(keyButton(it), cell())
                }
                deck.addView(nav)
                val arrows = row()
                arrows.addView(modifier("⇧", KeyEvent.KEYCODE_SHIFT_LEFT), cell())
                listOf(Key("←", KeyEvent.KEYCODE_DPAD_LEFT, description = activity.getString(R.string.computer_keyboard_left)),
                       Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, description = activity.getString(R.string.computer_keyboard_down)),
                       Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, description = activity.getString(R.string.computer_keyboard_right)),
                       Key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN), Key("Del", KeyEvent.KEYCODE_FORWARD_DEL)).forEach {
                    arrows.addView(keyButton(it), cell())
                }
                deck.addView(arrows)
                val textNavigation = row()
                listOf(RemoteAction.LINE_START to R.string.platform_keyboard_line_start,
                       RemoteAction.LINE_END to R.string.platform_keyboard_line_end,
                       RemoteAction.PREVIOUS_WORD to R.string.platform_keyboard_previous_word,
                       RemoteAction.NEXT_WORD to R.string.platform_keyboard_next_word).forEach { (action, label) ->
                    textNavigation.addView(button(activity.getString(label)).apply {
                        tag = "keyboard_action_${action.name.lowercase()}"
                        textSize = 12f
                        setOnClickListener { if (isVisible) inputHandler.onRemoteAction(action) }
                    }, cell())
                }
                deck.addView(textNavigation)
            }
        }
        val bottom = row().apply { tag = "keyboard_modifier_row" }
        val modifierOrder = if (activity.viewModel.remoteInputConfig.isMac)
            listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT)
        else listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ALT_LEFT)
        modifierOrder.forEach { code -> bottom.addView(modifier(modifierLabel(code), code), cell(1.3f)) }
        bottom.addView(keyButton(Key(activity.getString(R.string.computer_keyboard_space), KeyEvent.KEYCODE_SPACE)), cell(4.4f))
        fun pageButton(destination: Page, label: String, weight: Float) {
            bottom.addView(button(label).apply {
                tag = "keyboard_page_${destination.name.lowercase()}"
                setOnClickListener { page = destination; renderPage() }
                pageButtons[destination] = this
            }, cell(weight))
        }
        pageButton(Page.Common, "ABC", 1f)
        pageButton(Page.Symbols, "123", 1f)
        pageButton(Page.Functions, activity.getString(R.string.computer_keyboard_navigation), 1.7f)
        if (page == Page.Common) {
            listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT).forEach {
                bottom.addView(keyButton(arrow(it)), cell())
            }
        } else {
            bottom.addView(keyButton(Key("↵", KeyEvent.KEYCODE_ENTER,
                    description = activity.getString(R.string.computer_keyboard_enter))), cell(2f))
        }
        deck.addView(bottom)
        pageButtons.forEach { (destination, button) -> select(button, page == destination) }
        updateSelection()
        root.post { updateWidth() }
    }

    private fun capsButton() = button(if (activity.viewModel.remoteInputConfig.isMac) "⇪" else "Caps").apply {
        tag = "keyboard_key_${KeyEvent.KEYCODE_CAPS_LOCK}"
        setOnClickListener { capsLock = !capsLock; tap(Key("", KeyEvent.KEYCODE_CAPS_LOCK), false); updateSelection() }
        modifierButtons[KeyEvent.KEYCODE_CAPS_LOCK] = this
    }

    private fun arrow(code: Int): Key = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Key("↑", code, description = activity.getString(R.string.computer_keyboard_up))
        KeyEvent.KEYCODE_DPAD_DOWN -> Key("↓", code, description = activity.getString(R.string.computer_keyboard_down))
        KeyEvent.KEYCODE_DPAD_LEFT -> Key("←", code, description = activity.getString(R.string.computer_keyboard_left))
        else -> Key("→", code, description = activity.getString(R.string.computer_keyboard_right))
    }

    private fun backspace() = Key("⌫", KeyEvent.KEYCODE_DEL, description = activity.getString(R.string.computer_keyboard_backspace))

    private fun toggleModifier(code: Int, lock: Boolean) {
        when {
            lock && modifiers[code] != Modifier.Locked -> {
                if (code !in modifiers) send(Key("", code), true)
                modifiers[code] = Modifier.Locked
            }
            code in modifiers -> {
                send(Key("", code), false)
                modifiers.remove(code)
            }
            else -> {
                send(Key("", code), true)
                modifiers[code] = Modifier.Armed
            }
        }
        updateSelection()
    }

    private fun releaseOneShotModifiers() {
        modifiers.filterValues { it == Modifier.Armed }.keys.toList().forEach { code ->
            send(Key("", code), false)
            modifiers.remove(code)
        }
        updateSelection()
    }

    private fun updateSelection() {
        modifierButtons.forEach { (code, button) ->
            val enabled = if (code == KeyEvent.KEYCODE_CAPS_LOCK) capsLock else code in modifiers
            val locked = modifiers[code] == Modifier.Locked
            select(button, enabled)
            val label = modifierLabel(code, accessible = true)
            button.text = if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) "⇧" else modifierLabel(code)
            button.icon = if (locked) androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.ic_control_lock) else null
            button.iconSize = dp(12)
            button.iconPadding = dp(2)
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
            button.iconTint = ColorStateList.valueOf(style.blue)
            if (Build.VERSION.SDK_INT >= 30) button.stateDescription = activity.getString(
                    if (locked) R.string.control_locked else if (enabled) R.string.control_active else R.string.control_inactive)
            button.contentDescription = when {
                code == KeyEvent.KEYCODE_CAPS_LOCK -> activity.getString(
                        if (capsLock) R.string.computer_keyboard_caps_on else R.string.computer_keyboard_caps_off)
                locked -> activity.getString(R.string.computer_keyboard_modifier_locked, label)
                enabled -> activity.getString(R.string.computer_keyboard_modifier_active, label)
                else -> activity.getString(R.string.computer_keyboard_modifier, label)
            }
        }
        val shifted = KeyEvent.KEYCODE_SHIFT_LEFT in modifiers || KeyEvent.KEYCODE_SHIFT_RIGHT in modifiers
        val uppercase = capsLock.xor(shifted)
        letterButtons.forEach { (button, key) ->
            val character = key.label.singleOrNull()
            val label = when {
                character?.isLetter() == true -> if (uppercase) key.label.uppercase() else key.label
                shifted && character != null && !key.shifted -> shiftedCharacters[character]?.toString() ?: key.label
                else -> key.label
            }
            button.text = label
            if (character != null) button.contentDescription = label
        }
    }

    private fun modifierLabel(code: Int, accessible: Boolean = false): String {
        if (activity.viewModel.remoteInputConfig.isMac) {
            val compact = (measuredContentWidth.takeIf { it > 0 }?.div(activity.resources.displayMetrics.density)
                    ?: activity.resources.configuration.screenWidthDp.toFloat()) < 600f
            when (code) {
                KeyEvent.KEYCODE_CTRL_LEFT -> return if (!accessible && compact) "⌃" else activity.getString(R.string.platform_keyboard_mac_control)
                KeyEvent.KEYCODE_ALT_LEFT -> return if (!accessible && compact) "⌥" else activity.getString(R.string.platform_keyboard_mac_option)
                KeyEvent.KEYCODE_META_LEFT -> return if (!accessible && compact) "⌘" else activity.getString(R.string.platform_keyboard_mac_command)
            }
        }
        return when (code) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
            KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
            KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
            KeyEvent.KEYCODE_META_LEFT -> "Win"
            else -> if (activity.viewModel.remoteInputConfig.isMac) "⇪" else "Caps"
        }
    }

    private fun addKeys(deck: LinearLayout, keys: List<Key>) {
        val row = row()
        keys.forEach { row.addView(keyButton(it), cell()) }
        deck.addView(row)
    }

    /** Keep desktop function keys visible on every page without shrinking their touch targets. */
    private fun addFunctionKeys() {
        val functionKeys = row(48)
        val keys = listOf(Key("Esc", KeyEvent.KEYCODE_ESCAPE)) +
                (0..11).map { Key("F${it + 1}", KeyEvent.KEYCODE_F1 + it) }
        val buttons = keys.map { key ->
            keyButton(key).apply {
                textSize = 12f
                minimumWidth = dp(48)
                functionKeys.addView(this, LinearLayout.LayoutParams(dp(48), -1).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                })
            }
        }
        val scroll = HorizontalScrollView(activity).apply {
            tag = "keyboard_function_row"
            contentDescription = activity.getString(R.string.computer_keyboard_function_keys)
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(functionKeys, FrameLayout.LayoutParams(-2, -1))
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val keyWidth = maxOf(dp(48), (right - left) / buttons.size - dp(4))
                buttons.forEach { button ->
                    if (button.layoutParams.width != keyWidth)
                        button.layoutParams = button.layoutParams.apply { width = keyWidth }
                }
            }
        }
        keysArea.addView(scroll, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun keyButton(key: Key) = button(key.label, key.description).apply {
        tag = if (key.shifted) "keyboard_symbol_${key.label}" else "keyboard_key_${key.code}"
        letterButtons[this] = key
        setOnClickListener { if (isVisible && !suppressRepeatClick) tap(key) }
        if (key.code in repeatableKeys) makeRepeatable(this, key)
    }

    private fun tap(key: Key, consumeModifiers: Boolean = true) {
        send(key, true)
        send(key, false)
        if (consumeModifiers) releaseOneShotModifiers()
    }

    private fun send(key: Key, down: Boolean, count: Int = 0, downTime: Long = SystemClock.uptimeMillis()) {
        val meta = (if (capsLock) KeyEvent.META_CAPS_LOCK_ON else 0) or
                (if (key.shifted) KeyEvent.META_SHIFT_ON else 0)
        inputHandler.onVkKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(),
                if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                key.code, count, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeRepeatable(view: View, key: Key) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopRepeating()
                    if (isVisible) {
                        repeatedKey = key
                        repeatView = view
                        repeatDownTime = event.downTime
                        repeatCount = 0
                        view.isPressed = true
                        send(key, true, downTime = repeatDownTime)
                        root.postDelayed(repeat, ViewConfiguration.getKeyRepeatTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 || event.x >= view.width || event.y >= view.height)
                    stopRepeating()
                MotionEvent.ACTION_UP -> {
                    val wasPressed = repeatedKey != null && repeatView == view
                    stopRepeating()
                    if (wasPressed) {
                        releaseOneShotModifiers()
                        suppressRepeatClick = true
                        view.performClick()
                        suppressRepeatClick = false
                    }
                }
                MotionEvent.ACTION_CANCEL -> stopRepeating()
            }
            true
        }
    }

    private fun stopRepeating() {
        root.removeCallbacks(repeat)
        repeatedKey?.let { send(it, false, downTime = repeatDownTime) }
        repeatedKey = null
        repeatView?.isPressed = false
        repeatView = null
        repeatCount = 0
    }

    private fun row(height: Int? = null) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(-1, if (height != null) dp(height) else rowHeight())
    }

    private fun cell(weight: Float = 1f) = LinearLayout.LayoutParams(0, -1, weight).apply {
        setMargins(dp(2), dp(1), dp(2), dp(1))
    }

    private fun button(label: String, description: String = label) = style.button(label, description).apply {
        textSize = if (label.length > 3) 11f else 15f
        setPadding(0, 0, 0, 0)
        isFocusable = false
        style.select(this, false, key = true)
    }

    private fun select(button: MaterialButton, selected: Boolean) = style.select(button, selected, key = true)
    private fun rowHeight(): Int = dp(48) + 2 * dp(1)
    private fun dp(value: Int) = style.dp(value)

    private fun characterKey(char: Char): Key {
        if (char in 'a'..'z') return Key(char.toString(), KeyEvent.KEYCODE_A + (char - 'a'))
        if (char in '0'..'9') return Key(char.toString(), KeyEvent.KEYCODE_0 + (char - '0'))
        val shiftedDigits = ")!@#$%^&*("
        val digit = shiftedDigits.indexOf(char)
        if (digit >= 0) return Key(char.toString(), KeyEvent.KEYCODE_0 + digit, true)
        val mapping = mapOf(
                ',' to KeyEvent.KEYCODE_COMMA, '.' to KeyEvent.KEYCODE_PERIOD,
                '-' to KeyEvent.KEYCODE_MINUS, '=' to KeyEvent.KEYCODE_EQUALS,
                '[' to KeyEvent.KEYCODE_LEFT_BRACKET, ']' to KeyEvent.KEYCODE_RIGHT_BRACKET,
                '\\' to KeyEvent.KEYCODE_BACKSLASH, ';' to KeyEvent.KEYCODE_SEMICOLON,
                '\'' to KeyEvent.KEYCODE_APOSTROPHE, '`' to KeyEvent.KEYCODE_GRAVE,
                '/' to KeyEvent.KEYCODE_SLASH)
        mapping[char]?.let { return Key(char.toString(), it) }
        val base = mapOf('_' to '-', '+' to '=', '{' to '[', '}' to ']', '|' to '\\',
                         ':' to ';', '"' to '\'', '~' to '`', '<' to ',', '>' to '.', '?' to '/')[char]
        requireNotNull(base) { "Missing keyboard character: $char" }
        return Key(char.toString(), mapping.getValue(base), true)
    }

    private companion object {
        val shiftedCharacters = "`1234567890-=[]\\;',./".zip("~!@#$%^&*()_+{}|:\"<>?").toMap()
        val repeatableKeys = setOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    }
}
