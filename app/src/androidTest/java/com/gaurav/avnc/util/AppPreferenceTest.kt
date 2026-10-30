/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.util

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferenceTest {

    @Test fun recommendedInputDefaultsAndExplicitOverrides() {
        val prefs = AppPreferences(targetContext)
        assertEquals("remote-scroll", prefs.input.gesture.swipe2)
        assertEquals("right-click", prefs.input.gesture.tap2)
        assertEquals("left-drag", prefs.input.gesture.longPress)
        assertEquals("none", prefs.input.gesture.longPressSwipe)
        assertEquals(true, prefs.input.gesture.remoteScrollInertia)
        assertEquals(false, prefs.input.useSystemKeyboard)
        targetPrefs.edit {
            putString("gesture_swipe2", "pan")
            putString("gesture_tap2", "open-keyboard")
            putString("gesture_long_press", "right-click")
            putString("gesture_long_press_swipe", "remote-scroll")
            putBoolean("remote_scroll_inertia", false)
        }
        assertEquals("pan", prefs.input.gesture.swipe2)
        prefs.restoreRecommendedInput()
        assertEquals("remote-scroll", prefs.input.gesture.swipe2)
        assertEquals("pan", prefs.input.gesture.swipe1)
        assertEquals("right-click", prefs.input.gesture.tap2)
        assertEquals("left-drag", prefs.input.gesture.longPress)
        assertEquals("none", prefs.input.gesture.longPressSwipe)
        assertEquals(true, prefs.input.gesture.remoteScrollInertia)
        assertEquals(false, prefs.input.showTouchpad)
    }

    @Test fun savedGestureMappingsSurvivePreferenceInitialization() {
        targetPrefs.edit {
            putString("gesture_swipe2", "pan")
            putString("gesture_tap2", "open-keyboard")
            putString("gesture_long_press", "right-click")
            putString("gesture_long_press_swipe", "remote-scroll")
            putBoolean("remote_scroll_inertia", false)
        }
        val gesture = AppPreferences(targetContext).input.gesture
        assertEquals("pan", gesture.swipe2)
        assertEquals("open-keyboard", gesture.tap2)
        assertEquals("right-click", gesture.longPress)
        assertEquals("remote-scroll", gesture.longPressSwipe)
        assertEquals(true, gesture.longPressSwipeEnabled)
        assertEquals(false, gesture.remoteScrollInertia)
    }

    @Test fun atomicDragDoesNotDeferToLegacyLongPressSwipeMapping() {
        val gesture = AppPreferences(targetContext).input.gesture
        targetPrefs.edit { putString("gesture_long_press_swipe", "remote-scroll") }
        assertEquals(false, gesture.longPressSwipeEnabled)
        targetPrefs.edit { putString("gesture_long_press", "left-press") }
        assertEquals(false, gesture.longPressSwipeEnabled)
        targetPrefs.edit { putString("gesture_long_press", "right-click") }
        assertEquals(true, gesture.longPressSwipeEnabled)
    }

    @Rule
    @JvmField
    val prefRule = CleanPrefsRule()


    // If user had turned off Direct Touch, new Gesture Style pref
    // should be set to 'touchpad'
    @Test
    fun gestureStyleMigrationTest() {
        targetPrefs.edit {
            remove("gesture_style")
            putBoolean("gesture_direct_touch", false)
        }

        val appPrefs = AppPreferences(targetContext)
        assertEquals(appPrefs.input.gesture.style, "touchpad")
    }

    @Test
    fun metaKeysRightToLeftMigration() {
        targetPrefs.edit { putString("vk_keys_layout", "RightAlt,RightShift,F1,Up") }
        val p1 = AppPreferences(targetContext)
        assertEquals("LeftAlt,LeftShift,F1,Up", p1.input.vkLayout)

        // If Right-variants are ever added in future, they should not be affected
        targetPrefs.edit { putString("vk_keys_layout", "LeftAlt,RightAlt,RightShift,F1,Up") }
        val p2 = AppPreferences(targetContext)
        assertEquals("LeftAlt,RightAlt,RightShift,F1,Up", p2.input.vkLayout)

    }
    @Test fun viewerControlsPreserveExplicitSavedVisibilityAndPosition() {
        val defaults = AppPreferences(targetContext)
        assertEquals(false, defaults.input.showTouchpad)
        assertEquals(false, defaults.runInfo.showVirtualKeys)
        targetPrefs.edit {
            putBoolean("show_touchpad", true)
            putBoolean("touchpad_collapsed", true)
            putBoolean("run_info_show_virtual_keys", true)
            putFloat("touchpad_x", .25f)
            putFloat("touchpad_y", .75f)
        }
        val restored = AppPreferences(targetContext)
        assertEquals(true, restored.input.showTouchpad)
        assertEquals(true, restored.input.touchpadCollapsed)
        assertEquals(true, restored.runInfo.showVirtualKeys)
        assertEquals(.25f, restored.input.touchpadX)
        assertEquals(.75f, restored.input.touchpadY)
    }

}
