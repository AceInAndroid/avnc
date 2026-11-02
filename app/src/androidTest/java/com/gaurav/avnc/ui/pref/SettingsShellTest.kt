/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.pref

import android.content.res.Configuration
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.prefs.PrefsActivity
import com.gaurav.avnc.ui.prefs.SettingsRootFragment
import com.gaurav.avnc.ui.prefs.SettingsSearch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsShellTest {
    @JvmField @Rule val preferences = CleanPrefsRule()

    @Test fun localizedSearchMapsRealKeysWithoutWritingDefaults() {
        val before = targetPrefs.all.toMap()
        val english = targetContext.createConfigurationContext(Configuration(targetContext.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val chinese = targetContext.createConfigurationContext(Configuration(targetContext.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        for (context in listOf(english, chinese)) {
            val result = SettingsSearch.find(context, context.getString(R.string.pref_swipe2)).single { it.key == "gesture_swipe2" }
            assertEquals(PrefsActivity.Input::class.java.name, result.destination)
            assertEquals(context.getString(R.string.settings_input), result.category)
            assertTrue(SettingsSearch.find(context, "zzzz_no_setting_9137").isEmpty())
            assertEquals(6, SettingsSearch.groups(context).size)
        }
        assertEquals(before, targetPrefs.all)
    }

    @Test fun searchDestinationAndBackStackSurviveRecreationWithoutChangingLegacyChoices() {
        targetPrefs.edit { putString("gesture_swipe2", "pan"); putBoolean("use_system_keyboard", true) }
        ActivityScenario.launch(PrefsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.supportFragmentManager.findFragmentByTag("settings_root") as SettingsRootFragment
                root.requireView().findViewById<SearchView>(R.id.settings_search).setQuery("gesture", false)
                root.navigate(PrefsActivity.Input::class.java.name, "gesture_swipe2")
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.supportFragmentManager.findFragmentByTag("settings_root") as SettingsRootFragment
                val page = root.childFragmentManager.findFragmentById(R.id.settings_child_host) as PrefsActivity.Input
                assertEquals("gesture_swipe2", page.requireArguments().getString(SettingsRootFragment.HIGHLIGHT_KEY))
                assertEquals("pan", page.findPreference<ListPreference>("gesture_swipe2")!!.value)
                assertTrue(targetPrefs.getBoolean("use_system_keyboard", false))
                assertTrue(root.handleBack())
                assertEquals("gesture", root.requireView().findViewById<SearchView>(R.id.settings_search).query.toString())
                assertFalse(root.handleBack())
            }
        }
    }
}
