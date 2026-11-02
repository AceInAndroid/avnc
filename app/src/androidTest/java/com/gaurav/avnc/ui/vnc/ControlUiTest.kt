/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlUiTest : VncSessionTest() {
    @JvmField @Rule val cleanPrefs = CleanPrefsRule()

    private fun tap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(now, now + 10, action, x, y, 0).let { view.dispatchTouchEvent(it); it.recycle() }
        }
    }

    @Test fun newSessionStartsWithOneCapsuleAndNoFloatingPad() {
        vncSession.run {
            vncSession.onActivity {
                assertTrue(it.findViewById<View>(R.id.controls_capsule).isShown)
                assertFalse(it.findViewById<View>(R.id.floating_touchpad).isShown)
                assertFalse(it.computerKeyboard.isVisible)
                assertFalse(it.virtualKeys.container?.isVisible == true)
                assertFalse(isKeyboardVisible(it.binding.inputView))
                it.findViewById<View>(R.id.controls_collapse).performClick()
                assertTrue(it.findViewById<View>(R.id.open_toolbar_btn).isShown)
                assertFalse(it.findViewById<View>(R.id.controls_capsule).isShown)
                it.findViewById<View>(R.id.open_toolbar_btn).performClick()
                assertTrue(it.findViewById<View>(R.id.controls_capsule).isShown)
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun keyboardOwnsControlsAndBackClosesMenuBeforeKeyboard() {
        vncSession.run {
            vncSession.onActivity {
                it.findViewById<View>(R.id.keyboard_btn).performClick()
                assertTrue(it.computerKeyboard.isVisible)
                assertFalse(it.findViewById<View>(R.id.controls_capsule).isShown)
                it.findViewById<View>(R.id.keyboard_more).performClick()
                assertTrue(it.toolbar.isOpen)
                it.onBackPressedDispatcher.onBackPressed()
                assertFalse(it.toolbar.isOpen)
                assertTrue(it.computerKeyboard.isVisible)
                it.onBackPressedDispatcher.onBackPressed()
                assertFalse(it.computerKeyboard.isVisible)
                assertTrue(it.findViewById<View>(R.id.controls_capsule).isShown)
            }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun menuOutsideTapNeverClicksRemoteDesktopOrChangesKeyboard() {
        vncSession.run {
            vncSession.onActivity { it.showKeyboard(); it.toolbar.open() }
            pollingAssert {
                vncSession.onActivity {
                    val overlay = it.findViewById<android.view.ViewGroup>(R.id.controls_menu)
                    assertEquals(it.binding.viewerRoot.height, overlay.height)
                    assertTrue(overlay.height > it.binding.frameContainer.height)
                    assertTrue(overlay.getChildAt(0).left > 0)
                }
            }
            vncSession.onActivity {
                tap(it.findViewById(R.id.controls_menu), 2f, 2f)
            }
            pollingAssert {
                vncSession.onActivity {
                    assertFalse(it.toolbar.isOpen)
                    assertTrue(it.computerKeyboard.isVisible)
                }
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun physicalKeyboardIncludesNumbersAndKeepsSymbolsAndNavigationAccessible() {
        vncSession.run {
            vncSession.onActivity {
                it.showKeyboard()
                val keyboard = it.computerKeyboard.container
                assertNotNull(keyboard.findViewWithTag<View>("keyboard_number_row"))
                keyboard.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_1}").performClick()
                keyboard.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_A}").performClick()
                keyboard.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_SPACE}").performClick()
                keyboard.findViewWithTag<View>("keyboard_page_symbols").performClick()
                keyboard.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_1}").performClick()
                keyboard.findViewWithTag<View>("keyboard_page_functions").performClick()
                keyboard.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_F12}").performClick()
            }
        }
        assertEquals(listOf('1'.code, 'a'.code, ' '.code, '1'.code, XKeySym.XK_F12), vncSession.server.receivedKeyDowns)
    }

    @Test fun landscapeDockReusesPadAndRestoresSavedFloatingPosition() {
        var original: Int? = null
        vncSession.run {
            try {
                vncSession.onActivity {
                    original = it.requestedOrientation
                    it.viewModel.pref.input.hasShownControlHelp = true
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    it.floatingTouchpad.toggle()
                    it.showKeyboard()
                }
                pollingAssert {
                    vncSession.onActivity {
                        assertEquals(Configuration.ORIENTATION_LANDSCAPE, it.resources.configuration.orientation)
                        if (it.binding.viewerRoot.width >= ControlStyle(it).dp(800)) {
                            assertTrue(it.floatingTouchpad.isDocked)
                            val pad = it.findViewById<View>(R.id.floating_touchpad)
                            assertSame(it.binding.touchpadDock, pad.parent)
                            assertTrue(pad.width > 0 && pad.height > 0)
                            assertEquals(it.binding.touchpadDock.height, pad.height)
                            val handle = it.findViewById<View>(R.id.touchpad_handle)
                            val visible = Rect()
                            assertTrue(handle.getGlobalVisibleRect(visible))
                            assertEquals(handle.height, visible.height())
                            assertTrue(it.binding.frameView.height > 0)
                        }
                    }
                }
                vncSession.onActivity { it.hideComputerKeyboard() }
                pollingAssert {
                    vncSession.onActivity {
                        assertFalse(it.floatingTouchpad.isDocked)
                        assertSame(it.binding.touchpadHost, it.findViewById<View>(R.id.floating_touchpad).parent)
                        val pad = it.findViewById<View>(R.id.floating_touchpad)
                        val capsule = it.findViewById<View>(R.id.controls_capsule)
                        val p = Rect(); val c = Rect()
                        pad.getGlobalVisibleRect(p); capsule.getGlobalVisibleRect(c)
                        assertFalse(Rect.intersects(p, c))
                    }
                }
            } finally { vncSession.onActivity { original?.let { orientation -> it.requestedOrientation = orientation } } }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }
    @Test fun legacyAuxiliaryKeysDoNotOverlapCapsule() {
        vncSession.run {
            vncSession.onActivity { it.virtualKeys.show() }
            pollingAssert {
                vncSession.onActivity {
                    val keys = Rect(); val capsule = Rect()
                    assertTrue(it.virtualKeys.container!!.getGlobalVisibleRect(keys))
                    assertTrue(it.findViewById<View>(R.id.controls_capsule).getGlobalVisibleRect(capsule))
                    assertTrue(capsule.bottom <= keys.top)
                }
            }
        }
    }

    @Test fun edgeSwipeOpensControlsWithoutRemoteClickOrKeyboard() {
        vncSession.run {
            vncSession.onActivity {
                val view = it.binding.inputView
                val now = SystemClock.uptimeMillis()
                val events = listOf(MotionEvent.ACTION_DOWN to 1f, MotionEvent.ACTION_MOVE to ControlStyle(it).dp(64).toFloat(),
                        MotionEvent.ACTION_UP to ControlStyle(it).dp(64).toFloat())
                events.forEachIndexed { index, (action, x) ->
                    MotionEvent.obtain(now, now + index * 20L, action, x, view.height / 2f, 0).let { event ->
                        assertTrue(view.dispatchTouchEvent(event)); event.recycle()
                    }
                }
                assertTrue(it.toolbar.isOpen)
                assertFalse(it.computerKeyboard.isVisible)
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

}
