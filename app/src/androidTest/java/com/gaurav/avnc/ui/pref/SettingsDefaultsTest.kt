/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.pref

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.prefs.SettingsDefaults
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDefaultsTest {
    @JvmField @Rule val preferences = CleanPrefsRule()

    @Test fun inputGroupRestorationPreservesOtherGroupsSecurityAndTemplates() {
        targetPrefs.edit {
            putBoolean("use_system_keyboard", true)
            putInt("touchpad_size", 140)
            putInt("touchpad_opacity", 45)
            putString("theme", "dark")
            putString("gesture_swipe2", "pan")
            putBoolean("clipboard_sync", false)
            putBoolean("lock_saved_server", true)
            putString("remote_input_template_mac", "custom template sentinel")
        }
        val before = targetPrefs.all.toMap()
        val restored = SettingsDefaults.restore(targetContext, targetPrefs, R.xml.pref_input_overview)
        assertEquals(setOf("use_system_keyboard", "touchpad_size", "touchpad_opacity"), restored.keys)
        assertFalse(targetPrefs.getBoolean("use_system_keyboard", true))
        assertEquals(100, targetPrefs.getInt("touchpad_size", 0))
        assertEquals(100, targetPrefs.getInt("touchpad_opacity", 0))
        assertEquals(before.filterKeys { it !in restored }, targetPrefs.all.filterKeys { it !in restored })
    }

    @Test fun connectionGroupNeverBypassesSavedComputerAuthentication() {
        targetPrefs.edit {
            putBoolean("lock_saved_server", true)
            putBoolean("clipboard_sync", false)
            putBoolean("auto_reconnect", true)
            putStringSet("trusted_hosts", setOf("trusted sentinel"))
            putInt("touchpad_size", 140)
        }
        val before = targetPrefs.all.toMap()
        val restored = SettingsDefaults.restore(targetContext, targetPrefs, R.xml.pref_server)
        assertFalse("lock_saved_server" in restored)
        assertTrue(targetPrefs.getBoolean("lock_saved_server", false))
        assertTrue(targetPrefs.getBoolean("clipboard_sync", false))
        assertFalse(targetPrefs.getBoolean("auto_reconnect", true))
        assertEquals(before.filterKeys { it !in restored }, targetPrefs.all.filterKeys { it !in restored })
    }

    @Test fun toolGroupWithoutPersistedControlsIsANoOp() {
        targetPrefs.edit { putBoolean("clipboard_sync", false); putString("theme", "dark") }
        val before = targetPrefs.all.toMap()
        assertTrue(SettingsDefaults.restore(targetContext, targetPrefs, R.xml.pref_tools).isEmpty())
        assertEquals(before, targetPrefs.all)
    }
}
