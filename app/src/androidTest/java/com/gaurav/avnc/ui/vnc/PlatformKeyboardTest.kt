/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.R
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformKeyboardTest : VncSessionTest() {
    @JvmField @Rule val prefsRule = CleanPrefsRule()

    private fun click(tag: String) = vncSession.onActivity {
        assertTrue(checkNotNull(it.computerKeyboard.container.findViewWithTag<View>(tag)).performClick())
    }

    private fun platform(value: String) = vncSession.onActivity {
        it.viewModel.profile.remotePlatform = value
        it.applyRemoteInputSettings()
    }

    private fun key(code: Int) = "keyboard_key_$code"

    @Test fun macModifiersKeepPlatformLabelsWhenArmedAndLocked() {
        vncSession.run {
            platform("mac")
            vncSession.onActivity { it.showKeyboard() }
            vncSession.onActivity { activity ->
                val keyboard = activity.computerKeyboard.container
                val control = keyboard.findViewWithTag<TextView>(key(KeyEvent.KEYCODE_CTRL_LEFT))
                val option = keyboard.findViewWithTag<TextView>(key(KeyEvent.KEYCODE_ALT_LEFT))
                val command = keyboard.findViewWithTag<TextView>(key(KeyEvent.KEYCODE_META_LEFT))
                assertTrue(control.text.contains("⌃"))
                assertTrue(control.contentDescription.contains("Control"))
                assertTrue(option.text.contains("⌥"))
                assertTrue(option.contentDescription.contains("Option"))
                assertTrue(command.text.contains("⌘"))
                assertTrue(command.contentDescription.contains("Command"))
                command.performClick()
                assertTrue(command.isSelected)
                assertTrue(command.text.contains("⌘"))
                assertTrue(command.contentDescription.contains("Command"))
                command.performLongClick()
                assertTrue(command.isSelected)
                assertTrue(command.text.contains("⌘"))
                assertFalse(command.text.contains("Win"))
                activity.computerKeyboard.releaseKeys()
            }
        }
        assertEquals(listOf(XKeySym.XK_Super_L to true, XKeySym.XK_Super_L to false),
                vncSession.server.receivedKeySyms)
    }

    @Test fun mainKeyboardHeaderUsesOrderedTabsAndExplicitCloseOnBothPlatforms() {
        vncSession.run {
            for (platformValue in listOf("mac", "windows")) {
                platform(platformValue)
                vncSession.onActivity { activity ->
                    activity.showKeyboard()
                    val keyboard = activity.computerKeyboard.container
                    val header = checkNotNull(keyboard.findViewWithTag<ViewGroup>("keyboard_header"))
                    fun descendants(view: View): List<View> = listOf(view) +
                            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                    assertEquals(listOf("input_tab_keyboard", "input_tab_touchpad", "input_tab_shortcuts", "input_more"),
                            descendants(header).mapNotNull { it.tag?.toString() }.filter { it.startsWith("input_") })
                    assertTrue(checkNotNull(header.findViewWithTag<View>("input_tab_keyboard")).isSelected)
                    assertNotNull(header.findViewWithTag<View>("keyboard_close"))
                    assertNull(keyboard.findViewById<View>(R.id.mac_controls_row))
                    assertFalse(descendants(header).any { it.tag?.toString()?.startsWith("keyboard_key_") == true })
                }
            }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun desktopBottomRowUsesPlatformModifierOrderAndReturnLabel() {
        vncSession.run {
            for (platformValue in listOf("mac", "windows")) {
                platform(platformValue)
                vncSession.onActivity { activity ->
                    activity.showKeyboard()
                    val keyboard = activity.computerKeyboard.container
                    val bottom = keyboard.findViewWithTag<ViewGroup>("keyboard_modifier_row")
                    val expected = if (platformValue == "mac")
                        listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT)
                    else listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_ALT_LEFT)
                    assertEquals(expected.map { key(it) }, (0..2).map { bottom.getChildAt(it).tag })
                    val enter = keyboard.findViewWithTag<TextView>(key(KeyEvent.KEYCODE_ENTER))
                    assertEquals(if (platformValue == "mac") "return" else "Enter", enter.text.toString())
                    if (platformValue == "windows") {
                        assertEquals("Ctrl", (bottom.getChildAt(0) as TextView).text.toString())
                        assertEquals("Win", (bottom.getChildAt(1) as TextView).text.toString())
                        assertEquals("Alt", (bottom.getChildAt(2) as TextView).text.toString())
                    }
                }
            }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun platformChangesPreserveVisibilityAndFunctionPageWithoutOpeningIme() {
        vncSession.run {
            platform("mac")
            vncSession.onActivity {
                assertFalse(it.computerKeyboard.isVisible)
                assertEquals(0, (it.computerKeyboard.container as ViewGroup).childCount)
                it.showKeyboard()
            }
            click("keyboard_page_functions")
            click(key(KeyEvent.KEYCODE_META_LEFT))
            platform("windows")
            vncSession.onActivity {
                val keyboard = it.computerKeyboard.container
                assertTrue(it.computerKeyboard.isVisible)
                assertFalse(isKeyboardVisible(it.binding.inputView))
                assertNotNull(keyboard.findViewWithTag<View>(key(KeyEvent.KEYCODE_F12)))
                val windows = keyboard.findViewWithTag<TextView>(key(KeyEvent.KEYCODE_META_LEFT))
                assertEquals("Win", windows.text.toString())
                assertFalse(windows.isSelected)
                it.computerKeyboard.hide()
            }
            platform("mac")
            vncSession.onActivity {
                assertFalse(it.computerKeyboard.isVisible)
                assertEquals(0, (it.computerKeyboard.container as ViewGroup).childCount)
                it.showKeyboard()
                assertNotNull(it.computerKeyboard.container.findViewWithTag<View>(key(KeyEvent.KEYCODE_F12)))
                assertTrue(it.computerKeyboard.container.findViewWithTag<TextView>(key(KeyEvent.KEYCODE_META_LEFT)).text.contains("⌘"))
            }
        }
        // A configuration change releases the old platform's modifier before rebuilding.
        assertEquals(listOf(XKeySym.XK_Super_L to true, XKeySym.XK_Super_L to false),
                vncSession.server.receivedKeySyms)
    }

    @Test fun macFunctionRowSendsPlainFunctionKeysWithoutModifierOrSystemAction() {
        vncSession.run {
            platform("mac")
            vncSession.onActivity {
                it.showKeyboard()
                assertNotNull(it.computerKeyboard.container.findViewWithTag<View>("keyboard_function_row"))
                assertFalse(isKeyboardVisible(it.binding.inputView))
            }
            for (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) click(key(code))
            click(key(KeyEvent.KEYCODE_CTRL_LEFT))
            click(key(KeyEvent.KEYCODE_F3))
            click(key(KeyEvent.KEYCODE_F4))
        }
        val plainKeys = (XKeySym.XK_F1..XKeySym.XK_F12).flatMap { listOf(it to true, it to false) }
        assertEquals(plainKeys + listOf(XKeySym.XK_Control_L to true,
                XKeySym.XK_F3 to true, XKeySym.XK_F3 to false, XKeySym.XK_Control_L to false,
                XKeySym.XK_F4 to true, XKeySym.XK_F4 to false), vncSession.server.receivedKeySyms)
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    private fun navigateText(platformValue: String) {
        vncSession.run {
            platform(platformValue)
            vncSession.onActivity { it.showKeyboard() }
            click("keyboard_page_functions")
            listOf("line_start", "line_end", "previous_word", "next_word").forEach {
                click("keyboard_action_$it")
            }
            vncSession.onActivity { assertTrue(it.computerKeyboard.isVisible) }
        }
    }

    @Test fun macTextNavigationUsesCommandForLinesAndOptionForWords() {
        navigateText("mac")
        assertEquals(listOf(XKeySym.XK_Super_L, XKeySym.XK_Left,
                XKeySym.XK_Super_L, XKeySym.XK_Right,
                XKeySym.XK_Alt_L, XKeySym.XK_Left,
                XKeySym.XK_Alt_L, XKeySym.XK_Right), vncSession.server.receivedKeyDowns)
        assertEquals(vncSession.server.receivedKeyDowns.sorted(),
                vncSession.server.receivedKeySyms.filter { !it.second }.map { it.first }.sorted())
    }

    @Test fun windowsTextNavigationUsesHomeEndAndCtrlForWords() {
        navigateText("windows")
        assertEquals(listOf(XKeySym.XK_Home, XKeySym.XK_End,
                XKeySym.XK_Control_L, XKeySym.XK_Left,
                XKeySym.XK_Control_L, XKeySym.XK_Right), vncSession.server.receivedKeyDowns)
        assertEquals(vncSession.server.receivedKeyDowns.sorted(),
                vncSession.server.receivedKeySyms.filter { !it.second }.map { it.first }.sorted())
    }
}
