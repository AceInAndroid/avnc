/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.pref

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.runOnMainSync
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.viewmodel.PrefsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupStateTest {
    @JvmField @Rule val preferences = CleanPrefsRule()
    @JvmField @Rule val database = EmptyDatabaseRule()
    private fun model() = PrefsViewModel(targetContext.applicationContext as Application)

    @Test fun cancelledPickerIsNeutralAndAllowsAnotherRequest() {
        runOnMainSync {
            val model = model()
            assertTrue(model.beginBackupSelection(PrefsViewModel.BackupAction.IMPORT))
            assertFalse(model.beginBackupSelection(PrefsViewModel.BackupAction.EXPORT))
            assertTrue(model.backupBusy.value == true)
            model.cancelBackupSelection()
            assertEquals(PrefsViewModel.BackupState.Cancelled, model.backupState.value)
            assertFalse(model.backupBusy.value == true)
            assertNull(model.importExportFinishedEvent.value)
            assertTrue(model.beginBackupSelection(PrefsViewModel.BackupAction.EXPORT))
            model.cancelBackupSelection()
        }
    }

    @Test fun operationRunsOnceAndFailureHasExplicitRetry() {
        val model = model()
        runOnMainSync {
            model.import("not JSON")
            assertTrue(model.backupState.value is PrefsViewModel.BackupState.Running)
            model.import("""{"profiles":[{"name":"must not be imported"}]}""")
        }
        pollingAssert {
            assertTrue(model.backupState.value is PrefsViewModel.BackupState.Error)
            assertFalse(model.backupBusy.value == true)
        }
        assertEquals(0, kotlinx.coroutines.runBlocking { database.db.serverProfileDao.getList().size })
        runOnMainSync { assertTrue(model.retryLastBackup()) }
        pollingAssert { assertTrue(model.backupState.value is PrefsViewModel.BackupState.Error) }
        runOnMainSync { model.import("""{"profiles":[],"preferences":{"gesture_swipe2":"pan"}}""") }
        pollingAssert { assertTrue(model.backupState.value is PrefsViewModel.BackupState.Success) }
        assertEquals("pan", androidx.preference.PreferenceManager.getDefaultSharedPreferences(targetContext).getString("gesture_swipe2", null))
    }
}
