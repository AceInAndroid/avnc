/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import android.app.Application
import androidx.lifecycle.MutableLiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.runOnMainSync
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.viewmodel.PrefsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The actual backup import/export boundary, without platform biometric UI mocks. */
@RunWith(AndroidJUnit4::class)
class StreamingBackupTest {
    @JvmField @Rule val prefs = CleanPrefsRule()
    @JvmField @Rule val database = EmptyDatabaseRule()
    private fun vm() = PrefsViewModel(targetContext.applicationContext as Application)

    @Test fun oldBackupRetainsLegacyRawEncodingAndQuality() {
        runOnMainSync { vm().import("""{"profiles":[{"name":"Old","useRawEncoding":true,"imageQuality":7}]}""") }
        pollingAssert {
            val profile = runBlocking { database.db.serverProfileDao.getList() }.single()
            assertEquals("legacy", profile.streamingPreset)
            assertTrue(profile.useRawEncoding)
            assertEquals(7, profile.imageQuality)
        }
    }

    @Test fun newBackupKeepsSmoothDefaultAndExplicitHdPreset() {
        runOnMainSync { vm().import("""{"version":2,"profiles":[{"name":"Smooth"},{"name":"HD","streamingPreset":"hd","selectedDisplayId":"42"}]}""") }
        pollingAssert {
            val profiles = runBlocking { database.db.serverProfileDao.getList() }
            assertEquals("smooth", profiles.first { it.name == "Smooth" }.streamingPreset)
            assertEquals("hd", profiles.first { it.name == "HD" }.streamingPreset)
            assertEquals("42", profiles.first { it.name == "HD" }.selectedDisplayId)
        }
    }

    @Test fun exportedDefaultSmoothUsesNewBackupVersion() {
        runBlocking { database.db.serverProfileDao.save(ServerProfile(name = "Smooth")) }
        val destination = MutableLiveData<String>()
        runOnMainSync { vm().apply { exportSettings.value = false }.export(destination) }
        pollingAssert {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(destination.value)).toString()
            assertTrue(json.contains("\"version\":2"))
            // Smooth may be omitted as the constructor default, so versioning preserves its meaning.
            assertTrue(json.contains("Smooth"))
        }
    }
}
