/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.util

import android.view.KeyEvent
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.model.RemoteInputConfig
import com.gaurav.avnc.model.RemoteInputOverrides
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.model.RemoteShortcut
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteInputPreferencesTest {
    @Rule @JvmField val prefRule = CleanPrefsRule()

    @Test fun openingInputSettingsFirstDoesNotMigrateXmlDefaultsAsSavedThreeFingerChoices() {
        androidx.test.core.app.ActivityScenario.launch(com.gaurav.avnc.ui.prefs.PrefsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.beginTransaction()
                        .replace(com.gaurav.avnc.R.id.fragment_host, com.gaurav.avnc.ui.prefs.PrefsActivity.Input())
                        .commitNow()
            }
        }
        val prefs = RemoteInputPreferences(targetContext)
        assertEquals("system", prefs.template(RemotePlatform.MAC).threeFinger)
        assertEquals("system", prefs.template(RemotePlatform.WINDOWS).threeFinger)
    }

    @Test fun touchpadAppearancePercentagesPersistAndClampToUsableBounds() {
        val input = AppPreferences(targetContext).input
        assertEquals(100, input.touchpadSizePercent)
        assertEquals(100, input.touchpadOpacityPercent)
        targetPrefs.edit { putInt("touchpad_size", 125); putInt("touchpad_opacity", 45) }
        assertEquals(125, AppPreferences(targetContext).input.touchpadSizePercent)
        assertEquals(45, AppPreferences(targetContext).input.touchpadOpacityPercent)
        targetPrefs.edit { putInt("touchpad_size", 0); putInt("touchpad_opacity", 0) }
        assertEquals(75, input.touchpadSizePercent)
        assertEquals(30, input.touchpadOpacityPercent)
        targetPrefs.edit { putInt("touchpad_size", 999); putInt("touchpad_opacity", 999) }
        assertEquals(150, input.touchpadSizePercent)
        assertEquals(100, input.touchpadOpacityPercent)
    }

    @Test fun inputSettingsExposeAndSaveTouchpadPercentageSliders() {
        androidx.test.core.app.ActivityScenario.launch(com.gaurav.avnc.ui.prefs.PrefsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = com.gaurav.avnc.ui.prefs.PrefsActivity.Input()
                activity.supportFragmentManager.beginTransaction()
                        .replace(com.gaurav.avnc.R.id.fragment_host, fragment).commitNow()
                val size = checkNotNull(fragment.findPreference<androidx.preference.SeekBarPreference>("touchpad_size"))
                val opacity = checkNotNull(fragment.findPreference<androidx.preference.SeekBarPreference>("touchpad_opacity"))
                assertEquals(75, size.min); assertEquals(150, size.max)
                assertEquals(30, opacity.min); assertEquals(100, opacity.max)
                size.value = 125; opacity.value = 45
            }
        }
        assertEquals(125, AppPreferences(targetContext).input.touchpadSizePercent)
        assertEquals(45, AppPreferences(targetContext).input.touchpadOpacityPercent)
    }

    @Test fun platformDefaultsRemainIndependent() {
        val prefs = RemoteInputPreferences(targetContext)
        assertEquals("standard", prefs.template(RemotePlatform.MAC).inertia)
        assertEquals("short", prefs.template(RemotePlatform.WINDOWS).inertia)
        assertEquals("system", prefs.template(RemotePlatform.MAC).threeFinger)
        assertEquals("pan", prefs.template(RemotePlatform.GENERIC).threeFinger)
        assertTrue(prefs.template(RemotePlatform.MAC).acceleration)
        assertFalse(prefs.template(RemotePlatform.WINDOWS).acceleration)
        prefs.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(acceleration = false))
        assertFalse(prefs.template(RemotePlatform.MAC).acceleration)
        prefs.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(pointerSpeed = 2f))
        assertEquals(2f, prefs.template(RemotePlatform.MAC).pointerSpeed, 0f)
        assertEquals(1f, prefs.template(RemotePlatform.WINDOWS).pointerSpeed, 0f)
        assertEquals(1f, prefs.template(RemotePlatform.GENERIC).pointerSpeed, 0f)
    }

    @Test fun connectionOverridesOnlySelectedTemplateFields() {
        val prefs = RemoteInputPreferences(targetContext)
        prefs.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(
                pointerSpeed = 1.4f, scrollSpeed = 1.6f, invertVertical = true))
        val own = ServerProfile(remotePlatform = "mac", remoteInputOverrides =
                RemoteInputOverrides(pointerSpeed = 2f, invertVertical = false).encode())
        val other = ServerProfile(remotePlatform = "mac")
        assertEquals(2f, prefs.resolve(own).pointerSpeed, 0f)
        assertEquals(1.6f, prefs.resolve(own).scrollSpeed, 0f)
        assertFalse(prefs.resolve(own).invertVertical)
        assertEquals(1.4f, prefs.resolve(other).pointerSpeed, 0f)
        assertTrue(prefs.resolve(other).invertVertical)
    }

    @Test fun laterTemplateChangesReachOnlyInheritedFields() {
        val prefs = RemoteInputPreferences(targetContext)
        val profile = ServerProfile(remotePlatform = "windows", remoteInputOverrides =
                RemoteInputOverrides(pointerSpeed = 1.8f).encode())
        prefs.saveTemplate(RemotePlatform.WINDOWS, RemoteInputOverrides(pointerSpeed = .8f, scrollSpeed = 1.2f))
        assertEquals(1.2f, prefs.resolve(profile).scrollSpeed, 0f)
        prefs.saveTemplate(RemotePlatform.WINDOWS, RemoteInputOverrides(pointerSpeed = 2.5f, scrollSpeed = 2f))
        assertEquals(1.8f, prefs.resolve(profile).pointerSpeed, 0f)
        assertEquals(2f, prefs.resolve(profile).scrollSpeed, 0f)
    }

    @Test fun absentHorizontalSpeedFollowsResolvedVerticalSpeed() {
        val prefs = RemoteInputPreferences(targetContext)
        prefs.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(scrollSpeed = 1.5f))
        val profile = ServerProfile(remotePlatform = "mac", remoteInputOverrides =
                RemoteInputOverrides(scrollSpeed = 2.3f).encode())
        assertNull(prefs.resolve(profile).horizontalSpeed)
        assertEquals(2.3f, prefs.resolve(profile).effectiveHorizontalSpeed, 0f)
    }

    @Test fun independentTemplateHorizontalSpeedSurvivesVerticalOverride() {
        val prefs = RemoteInputPreferences(targetContext)
        prefs.saveTemplate(RemotePlatform.WINDOWS, RemoteInputOverrides(scrollSpeed = 1.5f, horizontalSpeed = .7f))
        val profile = ServerProfile(remotePlatform = "windows", remoteInputOverrides =
                RemoteInputOverrides(scrollSpeed = 2f).encode())
        assertEquals(2f, prefs.resolve(profile).scrollSpeed, 0f)
        assertEquals(.7f, prefs.resolve(profile).effectiveHorizontalSpeed, 0f)
    }

    @Test fun connectionCanSetIndependentHorizontalSpeed() {
        val prefs = RemoteInputPreferences(targetContext)
        prefs.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(horizontalSpeed = .7f))
        val profile = ServerProfile(remotePlatform = "mac", remoteInputOverrides =
                RemoteInputOverrides(horizontalSpeed = 1.9f).encode())
        assertEquals(1.9f, prefs.resolve(profile).effectiveHorizontalSpeed, 0f)
        assertEquals(.7f, prefs.template(RemotePlatform.MAC).effectiveHorizontalSpeed, 0f)
    }

    @Test fun resetTemplatePreservesConnectionOverrides() {
        val prefs = RemoteInputPreferences(targetContext)
        prefs.saveTemplate(RemotePlatform.WINDOWS, RemoteInputOverrides(scrollSpeed = 2f, inertia = "off"))
        val profile = ServerProfile(remotePlatform = "windows", remoteInputOverrides =
                RemoteInputOverrides(pointerSpeed = 1.7f).encode())
        prefs.resetTemplate(RemotePlatform.WINDOWS)
        assertEquals(RemoteInputOverrides(), prefs.getOverrides(RemotePlatform.WINDOWS))
        assertEquals(1.7f, prefs.resolve(profile).pointerSpeed, 0f)
        assertEquals(1f, prefs.resolve(profile).scrollSpeed, 0f)
        assertEquals("short", prefs.resolve(profile).inertia)
    }

    @Test fun invalidValuesRetainTemplateAndFiniteSpeedsAreClamped() {
        val template = RemoteInputConfig.defaults(RemotePlatform.WINDOWS)
                .apply(RemoteInputOverrides(pointerSpeed = 1.6f, scrollSpeed = 1.7f, horizontalSpeed = .8f))
        val invalid = template.apply(RemoteInputOverrides(pointerSpeed = Float.NaN,
                scrollSpeed = Float.POSITIVE_INFINITY, horizontalSpeed = Float.NEGATIVE_INFINITY,
                inertia = "unknown", threeFinger = "unknown"))
        assertEquals(template, invalid)
        val bounded = template.apply(RemoteInputOverrides(pointerSpeed = 99f, scrollSpeed = -2f, horizontalSpeed = 0f))
        assertEquals(3f, bounded.pointerSpeed, 0f)
        assertEquals(.25f, bounded.scrollSpeed, 0f)
        assertEquals(.25f, bounded.effectiveHorizontalSpeed, 0f)
        val fast = template.apply(RemoteInputOverrides(scrollSpeed = 99f, horizontalSpeed = 9.5f))
        assertEquals(10f, fast.scrollSpeed, 0f)
        assertEquals(9.5f, fast.effectiveHorizontalSpeed, 0f)
    }

    @Test fun invalidShortcutEntriesDoNotReplaceValidDefaultsOrTemplate() {
        val copy = RemoteShortcut(KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_ALT_LEFT))
        val template = RemoteInputConfig.defaults(RemotePlatform.MAC)
                .apply(RemoteInputOverrides(shortcuts = mapOf("COPY" to copy)))
        val invalid = template.apply(RemoteInputOverrides(shortcuts = mapOf(
                "COPY" to RemoteShortcut(KeyEvent.KEYCODE_CTRL_LEFT),
                "PASTE" to RemoteShortcut(KeyEvent.KEYCODE_V, listOf(KeyEvent.KEYCODE_C)),
                "UNDO" to RemoteShortcut(KeyEvent.KEYCODE_Z, listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_LEFT)),
                "CUT" to RemoteShortcut(-1),
                "FUTURE_ACTION" to RemoteShortcut(KeyEvent.KEYCODE_F1))))
        assertEquals(template.shortcuts, invalid.shortcuts)
        assertEquals(copy, invalid.shortcut(RemoteAction.COPY))
        assertEquals(template.shortcut(RemoteAction.PASTE), invalid.shortcut(RemoteAction.PASTE))
        assertFalse(invalid.shortcuts.containsKey("FUTURE_ACTION"))
    }

    @Test fun shortcutOverridesInheritOtherActionsAndOtherConnections() {
        val prefs = RemoteInputPreferences(targetContext)
        val templateCopy = RemoteShortcut(KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_ALT_LEFT))
        val ownPaste = RemoteShortcut(KeyEvent.KEYCODE_INSERT, listOf(KeyEvent.KEYCODE_SHIFT_LEFT))
        prefs.saveTemplate(RemotePlatform.WINDOWS, RemoteInputOverrides(shortcuts = mapOf("COPY" to templateCopy)))
        val own = ServerProfile(remotePlatform = "windows", remoteInputOverrides =
                RemoteInputOverrides(shortcuts = mapOf("PASTE" to ownPaste)).encode())
        assertEquals(templateCopy, prefs.resolve(own).shortcut(RemoteAction.COPY))
        assertEquals(ownPaste, prefs.resolve(own).shortcut(RemoteAction.PASTE))
        assertEquals(RemoteInputConfig.defaults(RemotePlatform.WINDOWS).shortcut(RemoteAction.PASTE),
                prefs.resolve(ServerProfile(remotePlatform = "windows")).shortcut(RemoteAction.PASTE))
    }

    @Test fun automaticPlatformUsesAppleEvidenceAndKeepsUnknownGeneric() {
        val prefs = RemoteInputPreferences(targetContext)
        assertEquals(RemotePlatform.MAC, prefs.resolve(ServerProfile(), appleServer = true).platform)
        assertEquals(RemotePlatform.GENERIC, prefs.resolve(ServerProfile(), appleServer = false).platform)
        assertEquals(RemotePlatform.MAC, prefs.resolve(ServerProfile(remotePlatform = "future"), true).platform)
        assertEquals(RemotePlatform.WINDOWS, prefs.resolve(ServerProfile(remotePlatform = "windows"), true).platform)
        assertEquals(RemotePlatform.GENERIC, prefs.resolve(ServerProfile(remotePlatform = "generic"), true).platform)
        assertEquals(RemotePlatform.MAC, prefs.resolve(ServerProfile(remotePlatform = "mac"), false).platform)
    }

    @Test fun malformedOverridesFallBackAndUnknownFieldsRemainCompatible() {
        val prefs = RemoteInputPreferences(targetContext)
        prefs.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(pointerSpeed = 1.4f))
        assertEquals(1.4f, prefs.resolve(ServerProfile(remotePlatform = "mac", remoteInputOverrides = "broken")).pointerSpeed, 0f)
        assertEquals(RemoteInputOverrides(), RemoteInputOverrides.decode("{\"scrollSpeed\":\"wrong type\"}"))
        val decoded = RemoteInputOverrides.decode("{\"pointerSpeed\":2.1,\"futureOption\":{\"enabled\":true}}")
        assertEquals(2.1f, decoded.pointerSpeed!!, 0f)
    }

    @Test fun explicitlySavedLegacyPreferencesMigrateOnlyOnce() {
        targetPrefs.edit {
            putInt("gesture_swipe_sensitivity", 17)
            putBoolean("invert_vertical_scrolling", true)
            putBoolean("remote_scroll_inertia", false)
            putString("gesture_swipe3", "pan")
        }
        val first = RemoteInputPreferences(targetContext)
        for (platform in listOf(RemotePlatform.MAC, RemotePlatform.WINDOWS, RemotePlatform.GENERIC)) {
            val config = first.template(platform)
            assertEquals(1.7f, config.pointerSpeed, 0f)
            assertEquals(1.7f, config.scrollSpeed, 0f)
            assertTrue(config.invertVertical)
            assertEquals("off", config.inertia)
            assertEquals("legacy", config.threeFinger)
        }
        first.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(pointerSpeed = 2f))
        targetPrefs.edit { putInt("gesture_swipe_sensitivity", 5) }
        val second = RemoteInputPreferences(targetContext)
        assertEquals(2f, second.template(RemotePlatform.MAC).pointerSpeed, 0f)
        assertEquals(1.7f, second.template(RemotePlatform.WINDOWS).pointerSpeed, 0f)
        assertEquals(5, targetPrefs.getInt("gesture_swipe_sensitivity", 0))
        assertTrue(targetPrefs.getBoolean("invert_vertical_scrolling", false))
    }

    @Test fun migrationPreservesAnAlreadySavedPlatformTemplate() {
        val saved = RemoteInputOverrides(pointerSpeed = 2.2f, inertia = "off")
        targetPrefs.edit {
            putString("remote_input_template_mac", saved.encode())
            putInt("gesture_swipe_sensitivity", 8)
            putBoolean("remote_scroll_inertia", true)
        }
        val prefs = RemoteInputPreferences(targetContext)
        assertEquals(saved, prefs.getOverrides(RemotePlatform.MAC))
        assertEquals(.8f, prefs.template(RemotePlatform.WINDOWS).pointerSpeed, 0f)
        assertEquals("short", prefs.template(RemotePlatform.WINDOWS).inertia)
    }
}
