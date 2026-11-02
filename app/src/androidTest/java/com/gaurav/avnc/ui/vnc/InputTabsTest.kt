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
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputTabsTest : VncSessionTest() {
    @JvmField @Rule val prefs = CleanPrefsRule()
    private fun event(view: View, action: Int, x: Float = 30f, y: Float = 30f) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).let { assertTrue(view.dispatchTouchEvent(it)); it.recycle() }
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun orderedTabsSwitchExclusivePagesAndBackNeverOpensIme() {
        vncSession.run {
            vncSession.onActivity { a ->
                val tags = descendants(a.findViewById(R.id.controls_capsule)).mapNotNull { it.tag?.toString() }
                assertEquals(listOf("input_tab_keyboard", "input_tab_touchpad", "input_tab_shortcuts", "input_more"), tags)
                assertNull(a.selectedInputTab)
                a.findViewById<View>(R.id.keyboard_btn).performClick()
                assertEquals(InputTab.Keyboard, a.selectedInputTab)
                a.computerKeyboard.container.findViewById<View>(R.id.keyboard_touchpad).performClick()
                assertEquals(InputTab.Touchpad, a.selectedInputTab)
                assertFalse(a.computerKeyboard.isVisible)
                assertTrue(a.floatingTouchpad.isTabbed)
                a.inputPanels.container.findViewById<View>(R.id.keyboard_shortcuts).performClick()
                assertEquals(InputTab.Shortcuts, a.selectedInputTab)
                assertFalse(a.floatingTouchpad.isTabbed)
                assertTrue(a.inputPanels.container.findViewById<View>(R.id.keyboard_shortcuts).isSelected)
                assertFalse(isKeyboardVisible(a.binding.inputView))
                a.onBackPressedDispatcher.onBackPressed()
                assertNull(a.selectedInputTab)
                assertFalse(a.binding.keyboardHost.isShown)
                assertTrue(a.findViewById<View>(R.id.controls_capsule).isShown)
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun touchpadEditRailUsesRemotePlatformChords() {
        vncSession.run {
            for (platform in listOf("mac", "windows")) vncSession.onActivity { a ->
                a.viewModel.profile.remotePlatform = platform
                a.applyRemoteInputSettings()
                a.selectInputTab(InputTab.Touchpad)
                for (action in listOf("copy", "paste", "cut", "undo"))
                    a.inputPanels.container.findViewWithTag<View>("touchpad_action_$action").performClick()
                assertFalse(a.computerKeyboard.isVisible)
                assertFalse(isKeyboardVisible(a.binding.inputView))
            }
        }
        val expected = listOf(XKeySym.XK_Super_L, XKeySym.XK_Control_L).flatMap { modifier ->
            listOf('c', 'v', 'x', 'z').flatMap { listOf(modifier to true, it.code to true, it.code to false, modifier to false) }
        }
        assertEquals(expected, vncSession.server.receivedKeySyms)
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun shortcutsOfferPlatformAppSwitchAndSelectAllWithoutKeyboard() {
        vncSession.run {
            for (platform in listOf("mac", "windows")) vncSession.onActivity { a ->
                a.viewModel.profile.remotePlatform = platform
                a.applyRemoteInputSettings()
                a.selectInputTab(InputTab.Shortcuts)
                a.inputPanels.container.findViewWithTag<View>("shortcut_action_switch_app").performClick()
                a.inputPanels.container.findViewWithTag<View>("shortcut_action_select_all").performClick()
                assertFalse(a.computerKeyboard.isVisible)
            }
        }
        assertEquals(listOf(XKeySym.XK_Super_L, XKeySym.XK_Tab, XKeySym.XK_Super_L, 'a'.code,
                XKeySym.XK_Alt_L, XKeySym.XK_Tab, XKeySym.XK_Control_L, 'a'.code), vncSession.server.receivedKeyDowns)
    }

    @Test fun changingTabsReleasesHeldModifiersAndMouseButtons() {
        vncSession.run {
            vncSession.onActivity { a ->
                a.showKeyboard()
                a.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_CTRL_LEFT}").performLongClick()
                a.selectInputTab(InputTab.Touchpad)
                event(a.findViewById(R.id.touchpad_left), MotionEvent.ACTION_DOWN)
                a.selectInputTab(InputTab.Shortcuts)
            }
        }
        assertEquals(listOf(XKeySym.XK_Control_L to true, XKeySym.XK_Control_L to false), vncSession.server.receivedKeySyms)
        assertEquals(listOf(1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
    }

    @Test fun touchpadIsLargeAndConstrainedAcrossRotationWithNoRemoteEvents() {
        vncSession.run {
            var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            try {
                vncSession.onActivity { orientation = it.requestedOrientation; it.selectInputTab(InputTab.Touchpad) }
                for (requested in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    vncSession.onActivity { it.requestedOrientation = requested }
                    pollingAssert {
                        vncSession.onActivity { a ->
                            assertEquals(if (requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE,
                                    a.resources.configuration.orientation)
                            val surface = a.findViewById<View>(R.id.touchpad_surface)
                            val rail = a.findViewById<View>(R.id.touchpad_edit_actions)
                            val pad = a.findViewById<View>(R.id.floating_touchpad)
                            assertSame(a.findViewById<View>(R.id.input_touchpad_surface_host), pad.parent)
                            assertTrue(surface.width > ControlStyle(a).dp(140))
                            assertTrue(surface.height >= ControlStyle(a).dp(96))
                            val visible = Rect()
                            assertTrue(surface.getGlobalVisibleRect(visible))
                            assertEquals(surface.height, visible.height())
                            assertTrue(rail.width < surface.width)
                            descendants(rail).filter { it.tag?.toString()?.startsWith("touchpad_action_") == true }
                                    .forEach { assertTrue(it.height >= ControlStyle(a).dp(48)) }
                            assertTrue(a.binding.frameView.height >= ControlStyle(a).dp(72))
                        }
                    }
                }
            } finally { vncSession.onActivity { it.requestedOrientation = orientation } }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun landscapeKeyboardRetainsRemoteSpaceAndCanReachBottomKeys() {
        vncSession.run {
            var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            try {
                vncSession.onActivity { orientation = it.requestedOrientation; it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; it.showKeyboard() }
                pollingAssert {
                    vncSession.onActivity { a ->
                        assertEquals(Configuration.ORIENTATION_LANDSCAPE, a.resources.configuration.orientation)
                        assertTrue(a.binding.frameView.height >= ControlStyle(a).dp(72))
                        val scroll = a.computerKeyboard.container.findViewWithTag<android.widget.ScrollView>("keyboard_vertical_deck")
                        assertTrue(scroll.height >= ControlStyle(a).dp(48))
                        scroll.scrollTo(0, scroll.getChildAt(0).height)
                        val space = a.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_SPACE}")
                        val visible = Rect()
                        assertTrue(space.getGlobalVisibleRect(visible))
                        assertEquals(space.height, visible.height())
                    }
                }
            } finally { vncSession.onActivity { it.requestedOrientation = orientation } }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun readOnlyAndPipClosePagesAndSuppressTouchpad() {
        vncSession.run {
            vncSession.onActivity { a ->
                a.selectInputTab(InputTab.Touchpad)
                event(a.findViewById(R.id.touchpad_left), MotionEvent.ACTION_DOWN)
                a.viewModel.setViewMode(ServerProfile.VIEW_MODE_NO_INPUT)
                assertNull(a.selectedInputTab)
                a.selectInputTab(InputTab.Shortcuts)
                assertNull(a.selectedInputTab)
                a.viewModel.setViewMode(ServerProfile.VIEW_MODE_NORMAL)
                a.selectInputTab(InputTab.Shortcuts)
                a.viewModel.inPiPMode.value = true
                assertNull(a.selectedInputTab)
                assertFalse(a.floatingTouchpad.isExpanded)
                a.viewModel.inPiPMode.value = false
            }
        }
        assertEquals(listOf(1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
    }

    @Test fun moreDisconnectFinishesSessionAndReleasesInput() {
        vncSession.run {
            vncSession.onActivity { a ->
                a.selectInputTab(InputTab.Touchpad)
                event(a.findViewById(R.id.touchpad_left), MotionEvent.ACTION_DOWN)
                a.toolbar.open()
                val menu = a.findViewById<View>(R.id.controls_menu)
                assertNotNull(menu.findViewWithTag<View>("controls_clipboard"))
                assertNotNull(menu.findViewWithTag<View>("controls_send_file"))
                menu.findViewWithTag<View>("controls_disconnect").performClick()
                assertTrue(a.isFinishing)
            }
            pollingAssert { assertEquals(Lifecycle.State.DESTROYED, vncSession.activityScenario!!.state) }
        }
        assertEquals(listOf(1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
    }
}
