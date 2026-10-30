/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComputerKeyboardTest : VncSessionTest() {
    @JvmField @Rule val prefsRule = CleanPrefsRule()

    private fun key(code: Int) = "keyboard_key_$code"

    private fun click(tag: String) {
        vncSession.onActivity { activity ->
            val view = activity.computerKeyboard.container.findViewWithTag<View>(tag)
            checkNotNull(view) { "Keyboard control is missing: $tag" }
            assertTrue(view.performClick())
        }
    }

    private fun show() = vncSession.onActivity { it.showKeyboard() }

    @Test fun opensWithoutSystemImeAndClosesWithItsButton() {
        vncSession.run {
            show()
            vncSession.onActivity {
                assertTrue(it.computerKeyboard.isVisible)
                assertFalse(isKeyboardVisible(it.binding.inputView))
                assertFalse(it.virtualKeys.container?.visibility == View.VISIBLE)
            }
            click("keyboard_close")
            vncSession.onActivity { assertFalse(it.computerKeyboard.isVisible) }
        }
    }

    @Test fun hiddenKeyboardLeavesNoKeysInViewerHierarchyAndReopensOnSelectedPage() {
        vncSession.run {
            vncSession.onActivity {
                val host = it.computerKeyboard.container as ViewGroup
                assertEquals(0, host.childCount)
                // Configuration updates must also be safe before the first keyboard opening.
                it.computerKeyboard.onConfigurationChanged()
                assertEquals(0, host.childCount)
            }
            show()
            click("keyboard_page_functions")
            click("keyboard_close")
            vncSession.onActivity {
                val host = it.computerKeyboard.container as ViewGroup
                assertEquals(0, host.childCount)
                assertFalse(it.computerKeyboard.isVisible)
                it.computerKeyboard.onConfigurationChanged()
                assertEquals(0, host.childCount)
            }
            show()
            click(key(KeyEvent.KEYCODE_F12))
            click("keyboard_page_common")
            click(key(KeyEvent.KEYCODE_A))
        }
        assertEquals(listOf(XKeySym.XK_F12, 'a'.code), vncSession.server.receivedKeyDowns)
    }

    @Test fun lettersShiftCapsNumbersAndSymbolsUseExistingKeyMapping() {
        vncSession.run {
            show()
            click(key(KeyEvent.KEYCODE_A))
            click(key(KeyEvent.KEYCODE_SHIFT_LEFT))
            click(key(KeyEvent.KEYCODE_B))
            click(key(KeyEvent.KEYCODE_C))
            click("keyboard_page_functions")
            click(key(KeyEvent.KEYCODE_CAPS_LOCK))
            click("keyboard_page_common")
            click(key(KeyEvent.KEYCODE_D))
            click("keyboard_page_functions")
            click(key(KeyEvent.KEYCODE_CAPS_LOCK))
            click("keyboard_page_symbols")
            click(key(KeyEvent.KEYCODE_1))
            click("keyboard_symbol_@")
        }
        val printable = vncSession.server.receivedKeyDowns.filter { it < 128 }
        assertEquals(listOf('a', 'B', 'c', 'D', '1', '@').map { it.code }, printable)
    }

    @Test fun oneShotCtrlWrapsOnlyNextKey() {
        vncSession.run {
            show()
            click(key(KeyEvent.KEYCODE_CTRL_LEFT))
            click(key(KeyEvent.KEYCODE_C))
            click(key(KeyEvent.KEYCODE_V))
        }
        assertEquals(listOf(XKeySym.XK_Control_L to true, 'c'.code to true,
                'c'.code to false, XKeySym.XK_Control_L to false,
                'v'.code to true, 'v'.code to false), vncSession.server.receivedKeySyms)
    }

    @Test fun lockedModifierRemainsUntilTappedAgain() {
        vncSession.run {
            show()
            vncSession.onActivity {
                assertTrue(it.computerKeyboard.container.findViewWithTag<View>(key(KeyEvent.KEYCODE_CTRL_LEFT)).performLongClick())
            }
            click(key(KeyEvent.KEYCODE_A))
            click(key(KeyEvent.KEYCODE_C))
            click(key(KeyEvent.KEYCODE_CTRL_LEFT))
            click(key(KeyEvent.KEYCODE_V))
        }
        assertEquals(listOf(XKeySym.XK_Control_L to true,
                'a'.code to true, 'a'.code to false, 'c'.code to true, 'c'.code to false,
                XKeySym.XK_Control_L to false, 'v'.code to true, 'v'.code to false),
                vncSession.server.receivedKeySyms)
    }

    @Test fun altTabWinAndFunctionKeysAreAvailable() {
        vncSession.run {
            show()
            click(key(KeyEvent.KEYCODE_ALT_LEFT))
            click(key(KeyEvent.KEYCODE_TAB))
            click(key(KeyEvent.KEYCODE_META_LEFT))
            click(key(KeyEvent.KEYCODE_E))
            click("keyboard_page_functions")
            click(key(KeyEvent.KEYCODE_F12))
            click(key(KeyEvent.KEYCODE_MOVE_HOME))
            click(key(KeyEvent.KEYCODE_DPAD_LEFT))
        }
        assertEquals(listOf(XKeySym.XK_Alt_L, XKeySym.XK_Tab, XKeySym.XK_Super_L,
                'e'.code, XKeySym.XK_F12, XKeySym.XK_Home, XKeySym.XK_Left),
                vncSession.server.receivedKeyDowns)
    }

    @Test fun hideReleasesModifiersAndStopsHeldKeyRepeat() {
        vncSession.run {
            show()
            click(key(KeyEvent.KEYCODE_CTRL_LEFT))
            val down = SystemClock.uptimeMillis()
            vncSession.onActivity {
                val backspace = it.computerKeyboard.container.findViewWithTag<View>(key(KeyEvent.KEYCODE_DEL))
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 8f, 8f, 0).also { event ->
                    backspace.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            pollingAssert {
                assertTrue(vncSession.server.receivedKeyDowns.contains(XKeySym.XK_BackSpace))
            }
            vncSession.onActivity { it.computerKeyboard.hide() }
            pollingAssert {
                assertTrue(vncSession.server.receivedKeySyms.contains(XKeySym.XK_BackSpace to false))
                assertTrue(vncSession.server.receivedKeySyms.contains(XKeySym.XK_Control_L to false))
            }
            val count = vncSession.server.receivedKeySyms.size
            Thread.sleep(600)
            assertEquals(count, vncSession.server.receivedKeySyms.size)
            show()
            click(key(KeyEvent.KEYCODE_C))
        }
        assertEquals('c'.code to false, vncSession.server.receivedKeySyms.last())
        assertEquals(1, vncSession.server.receivedKeyDowns.count { it == XKeySym.XK_Control_L })
    }

    @Test fun arrowRepeatsWhileHeldAndReleasesOnCancel() {
        vncSession.run {
            show()
            click("keyboard_page_functions")
            val down = SystemClock.uptimeMillis()
            vncSession.onActivity {
                val arrow = it.computerKeyboard.container.findViewWithTag<View>(key(KeyEvent.KEYCODE_DPAD_RIGHT))
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 8f, 8f, 0).also { event ->
                    arrow.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            pollingAssert {
                assertTrue(vncSession.server.receivedKeyDowns.count { it == XKeySym.XK_Right } >= 2)
            }
            vncSession.onActivity {
                val arrow = it.computerKeyboard.container.findViewWithTag<View>(key(KeyEvent.KEYCODE_DPAD_RIGHT))
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 8f, 8f, 0).also { event ->
                    arrow.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            pollingAssert { assertEquals(XKeySym.XK_Right to false, vncSession.server.receivedKeySyms.last()) }
            val count = vncSession.server.receivedKeySyms.size
            Thread.sleep(150)
            assertEquals(count, vncSession.server.receivedKeySyms.size)
        }
    }
}
