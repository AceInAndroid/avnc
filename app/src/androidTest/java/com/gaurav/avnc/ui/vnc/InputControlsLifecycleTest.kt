/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.core.content.edit
import org.junit.runner.RunWith

/** Real configuration and socket transitions, rather than invoking lifecycle callbacks directly. */
@RunWith(AndroidJUnit4::class)
class InputControlsLifecycleTest {
    @JvmField @Rule val prefsRule = CleanPrefsRule()
    @Before fun enableTouchpadForInputTests() { targetPrefs.edit { putBoolean("show_touchpad", true); putBoolean("control_help_shown", true) } }

    private fun keyboardKey(activity: VncActivity, code: Int): View =
            checkNotNull(activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_$code"))

    /** Send through the actual panel so it owns the pointer gesture before the child presses left. */
    private fun holdMouseLeft(activity: VncActivity) {
        val panel = activity.findViewById<ViewGroup>(R.id.floating_touchpad)
        val button = activity.findViewById<View>(R.id.touchpad_left)
        val bounds = Rect(0, 0, button.width, button.height)
        panel.offsetDescendantRectToMyCoords(button, bounds)
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                bounds.exactCenterX(), bounds.exactCenterY(), 0).let { event ->
            assertTrue(panel.dispatchTouchEvent(event))
            event.recycle()
        }
    }

    private fun assertPanelInBounds(activity: VncActivity) {
        val host = if (activity.floatingTouchpad.isDocked) activity.binding.touchpadDock else activity.binding.touchpadHost
        val panel = activity.findViewById<View>(R.id.floating_touchpad)
        assertTrue(panel.isVisible)
        assertTrue(panel.width > 0 && panel.height > 0)
        assertTrue(panel.x >= host.paddingLeft)
        assertTrue(panel.y >= host.paddingTop)
        assertTrue(panel.x + panel.width <= host.width - host.paddingRight + 1f)
        assertTrue(panel.y + panel.height <= host.height - host.paddingBottom + 1f)
    }

    @Test fun realRotationKeepsKeyboardVisibleReleasesHeldInputAndClampsTouchpad() {
        val session = VncSessionScenario()
        var originalOrientation: Int? = null
        try {
            session.start()
            session.onActivity {
                originalOrientation = it.requestedOrientation
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                it.showKeyboard()
            }
            pollingAssert {
                session.onActivity {
                    assertEquals(Configuration.ORIENTATION_PORTRAIT, it.resources.configuration.orientation)
                    assertTrue(it.computerKeyboard.isVisible)
                    assertPanelInBounds(it)
                    assertTrue(it.findViewById<View>(R.id.touchpad_left).isShown)
                }
            }
            session.onActivity {
                keyboardKey(it, KeyEvent.KEYCODE_CTRL_LEFT).performClick()
                holdMouseLeft(it)
            }
            pollingAssert {
                assertTrue(session.server.receivedKeySyms.contains(XKeySym.XK_Control_L to true))
                assertEquals(1, session.server.receivedPointerEvents.last().mask)
            }
            session.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            pollingAssert {
                session.onActivity {
                    assertEquals(Configuration.ORIENTATION_LANDSCAPE, it.resources.configuration.orientation)
                    assertTrue(it.computerKeyboard.isVisible)
                    assertFalse(keyboardKey(it, KeyEvent.KEYCODE_CTRL_LEFT).isSelected)
                    assertPanelInBounds(it)
                }
                assertTrue(session.server.receivedKeySyms.contains(XKeySym.XK_Control_L to false))
                assertEquals(0, session.server.receivedPointerEvents.last().mask)
            }
            // The next key must be plain lowercase, rather than inheriting the held Ctrl.
            session.onActivity { keyboardKey(it, KeyEvent.KEYCODE_A).performClick() }
            pollingAssert { assertEquals('a'.code to false, session.server.receivedKeySyms.last()) }
        } finally {
            if (session.activityScenario != null && originalOrientation != null)
                runCatching { session.onActivity { it.requestedOrientation = originalOrientation!! } }
            session.stop()
        }
    }

    @Test fun socketDisconnectClearsControlsAndNextSessionStartsWithoutHeldKeysOrButtons() {
        val first = VncSessionScenario()
        val next = VncSessionScenario()
        var originalOrientation: Int? = null
        var ctrl: View? = null
        var alt: View? = null
        try {
            first.start()
            first.onActivity {
                originalOrientation = it.requestedOrientation
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                it.showKeyboard()
            }
            pollingAssert {
                first.onActivity {
                    assertEquals(Configuration.ORIENTATION_PORTRAIT, it.resources.configuration.orientation)
                    assertPanelInBounds(it)
                    assertTrue(it.findViewById<View>(R.id.touchpad_left).isShown)
                }
            }
            first.onActivity {
                ctrl = keyboardKey(it, KeyEvent.KEYCODE_CTRL_LEFT)
                alt = keyboardKey(it, KeyEvent.KEYCODE_ALT_LEFT)
                assertTrue(ctrl!!.performLongClick())
                assertTrue(alt!!.performLongClick())
                holdMouseLeft(it)
            }
            pollingAssert {
                assertTrue(first.server.receivedKeySyms.contains(XKeySym.XK_Control_L to true))
                assertTrue(first.server.receivedKeySyms.contains(XKeySym.XK_Alt_L to true))
                assertEquals(1, first.server.receivedPointerEvents.last().mask)
            }
            // stop() exits the server loop and closes the accepted socket: the client observes EOF.
            first.server.stop()
            pollingAssert {
                first.onActivity {
                    assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value)
                    assertFalse(it.computerKeyboard.isVisible)
                    assertFalse(it.findViewById<View>(R.id.floating_touchpad).isVisible)
                    // Cached off-tree views prove the old keyboard discarded its locked modifiers.
                    assertFalse(ctrl!!.isSelected)
                    assertFalse(alt!!.isSelected)
                }
            }
            first.onActivity { it.requestedOrientation = originalOrientation!! }
            first.stop()

            next.start()
            next.onActivity {
                it.showKeyboard()
                assertFalse(keyboardKey(it, KeyEvent.KEYCODE_CTRL_LEFT).isSelected)
                assertFalse(keyboardKey(it, KeyEvent.KEYCODE_ALT_LEFT).isSelected)
                keyboardKey(it, KeyEvent.KEYCODE_A).performClick()
                it.findViewById<View>(R.id.touchpad_surface).performClick()
            }
            pollingAssert {
                assertEquals(listOf('a'.code to true, 'a'.code to false), next.server.receivedKeySyms.toList())
                assertEquals(listOf(1, 0), next.server.receivedPointerEvents.map { it.mask })
            }
        } finally {
            if (first.activityScenario != null && originalOrientation != null)
                runCatching { first.onActivity { it.requestedOrientation = originalOrientation!! } }
            next.stop()
            first.stop()
        }
    }
}
