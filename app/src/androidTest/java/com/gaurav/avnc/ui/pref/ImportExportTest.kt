/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.pref

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.biometric.BiometricPrompt
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.SdkSuppress
import com.gaurav.avnc.BiometricMocking
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.R
import com.gaurav.avnc.checkIsDisplayed
import com.gaurav.avnc.checkWillBeDisplayed
import com.gaurav.avnc.doClick
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.db.MainDb
import com.gaurav.avnc.setupFileOpenIntent
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.prefs.PrefsActivity
import com.gaurav.avnc.util.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImportExportTest {
    @Rule
    @JvmField
    val prefsRule = CleanPrefsRule()

    @Rule
    @JvmField
    val dbRule = EmptyDatabaseRule()

    @Rule
    @JvmField
    val activityRule = ActivityScenarioRule(PrefsActivity::class.java)

    @Before
    fun openImportExportScreen() {
        onView(withText(R.string.settings_backup)).perform(scrollTo()).doClick()
        onView(withText(R.string.title_import)).perform(scrollTo()).checkIsDisplayed()
        onView(withText(R.string.title_export)).perform(scrollTo()).checkIsDisplayed()
    }

    @Before
    fun initIntents() = Intents.init()

    @After
    fun releaseIntents() = Intents.release()


    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun exportWithoutSecrets() {
        // Insert sample data
        val sampleName = "Days of our Lives"
        val sampleSecret = "Drake Ramoray"
        val profile = ServerProfile(name = sampleName, password = sampleSecret, sshPassword = sampleSecret, sshPrivateKey = sampleSecret)
        runBlocking { MainDb.getInstance(targetContext).serverProfileDao.save(profile) }

        // Setup export file
        val file = File.createTempFile("avnc", "test")
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(file.toUri())))

        // Export
        BiometricMocking.start()
        onView(withId(R.id.export_btn)).perform(scrollTo()).doClick()
        BiometricMocking.endWithSuccess()
        onView(withText(R.string.msg_exported)).checkWillBeDisplayed()

        // Verify exported data
        instrumentation.waitForIdleSync()
        val data = file.bufferedReader().use { it.readText() }
        assertTrue("Exported data: `$data` should contain `$sampleName`", data.contains(sampleName))
        assertFalse("Exported data: `$data` should not contain `$sampleSecret`", data.contains(sampleSecret))
    }


    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun exportWithSecrets() {
        // Insert sample data
        val sampleName = "Days of our Lives"
        val sampleSecret = "Drake Ramoray"
        val profile = ServerProfile(name = sampleName, password = sampleSecret, sshPassword = sampleSecret, sshPrivateKey = sampleSecret)
        runBlocking { MainDb.getInstance(targetContext).serverProfileDao.save(profile) }

        // Setup export file
        val file = File.createTempFile("avnc", "test")
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(file.toUri())))

        // Export
        BiometricMocking.start()
        onView(withText(R.string.title_export_passwords_and_keys)).doClick() // Check
        onView(withId(R.id.export_btn)).perform(scrollTo()).doClick()
        BiometricMocking.endWithSuccess()
        onView(withText(R.string.msg_exported)).checkWillBeDisplayed()

        // Verify exported data
        instrumentation.waitForIdleSync()
        val data = file.bufferedReader().use { it.readText() }
        assertTrue("Exported data: `$data` should contain `$sampleName`", data.contains(sampleName))
        assertTrue("Exported data: `$data` should contain `$sampleSecret`", data.contains(sampleSecret))
    }

    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun exportFailsOnIncorrectBiometric() {
        BiometricMocking.start()
        onView(withText(R.string.title_export_passwords_and_keys)).doClick()
        onView(withId(R.id.export_btn)).perform(scrollTo()).doClick()

        val errorMessage = "You shall not pass!!!"
        BiometricMocking.endWithError(errorMessage)
        onView(withSubstring(errorMessage)).checkWillBeDisplayed()
        onView(withId(R.id.backup_retry)).checkIsDisplayed()
        onView(withId(R.id.export_btn)).perform(scrollTo()).check(matches(isEnabled()))
        onView(withText(R.string.msg_exported)).check(doesNotExist())
        Intents.assertNoUnverifiedIntents()
    }

    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun exportAuthenticationCancellationIsNeutralAndAllowsAnotherAttempt() {
        BiometricMocking.start()
        onView(withId(R.id.export_btn)).perform(scrollTo()).doClick()
        val cancellationMessage = "The user dismissed authentication"
        BiometricMocking.endWithError(cancellationMessage, BiometricPrompt.ERROR_USER_CANCELED)

        onView(withId(R.id.backup_status)).perform(scrollTo())
                .check(matches(withText(R.string.settings_backup_cancelled)))
        onView(withId(R.id.backup_retry)).check(matches(not(isDisplayed())))
        onView(withText(cancellationMessage)).check(doesNotExist())
        onView(withId(R.id.export_btn)).perform(scrollTo()).check(matches(isEnabled()))
        Intents.assertNoUnverifiedIntents()

        BiometricMocking.start()
        onView(withId(R.id.export_btn)).doClick()
        val errorMessage = "Authentication is temporarily locked"
        BiometricMocking.endWithError(errorMessage, BiometricPrompt.ERROR_LOCKOUT)
        onView(withSubstring(errorMessage)).checkWillBeDisplayed()
        onView(withId(R.id.backup_retry)).checkIsDisplayed()
        Intents.assertNoUnverifiedIntents()
    }

    @Test
    fun missingFilePickerApp() {
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWithFunction { throw ActivityNotFoundException() }

        BiometricMocking.start()
        onView(withId(R.id.export_btn)).perform(scrollTo()).doClick()
        BiometricMocking.endWithSuccess()
        onView(withText(R.string.settings_backup_file_missing)).checkWillBeDisplayed()
    }


    @Test
    fun importProfiles() {
        val sampleName = "Joey Tribbiani"
        val sampleJson = """{ "profiles": [{ "name": "$sampleName" }]}"""
        setupFileOpenIntent(sampleJson)

        // Import
        BiometricMocking.start()
        onView(withText(R.string.title_delete_servers_before_import)).doClick()
        onView(withId(R.id.import_btn)).perform(scrollTo()).doClick()
        BiometricMocking.endWithSuccess()
        onView(withText(R.string.msg_imported)).checkWillBeDisplayed()

        // Verify imported data
        instrumentation.waitForIdleSync()
        val profiles = runBlocking { MainDb.getInstance(targetContext).serverProfileDao.getList() }
        assertEquals(1, profiles.size)
        assertEquals(sampleName, profiles.first().name)
    }


    @Test
    fun importSettings() {
        val sampleJson = """{ "profiles": [],  "preferences": { "gesture_style": "touchpad", "vk_open_with_keyboard": true }}"""
        setupFileOpenIntent(sampleJson)

        // Import
        BiometricMocking.start()
        onView(withId(R.id.import_btn)).perform(scrollTo()).doClick()
        BiometricMocking.endWithSuccess()
        onView(withText(R.string.msg_imported)).checkWillBeDisplayed()

        // Verify imported data
        instrumentation.waitForIdleSync()
        val pref = AppPreferences(targetContext)
        assertEquals("touchpad", pref.input.gesture.style)
        assertEquals(true, pref.input.vkOpenWithKeyboard)
    }


    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun exportSettings() {
        val pref = AppPreferences(targetContext)
        pref.input.vkLayout = "Foobar"
        targetPrefs.edit {
            putBoolean("rt_bool", true)
            putString("rt_str", "hello")
            putInt("rt_int", 7)
            putFloat("rt_float", 1.5f)
        }

        // Setup export file
        val file = File.createTempFile("avnc", "test")
        Intents.intending(IntentMatchers.hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(file.toUri())))

        // Export
        BiometricMocking.start()
        onView(withText(R.string.title_export_settings)).check(matches(isChecked()))
        onView(withText(R.string.title_export_profiles)).doClick().check(matches(isNotChecked()))
        onView(withId(R.id.export_btn)).perform(scrollTo()).doClick()
        BiometricMocking.endWithSuccess()
        onView(withText(R.string.msg_exported)).checkWillBeDisplayed()

        // Verify exported data
        instrumentation.waitForIdleSync()
        val data = file.bufferedReader().use { it.readText() }
        assertTrue(data.contains("vk_keys_layout"))
        assertTrue(data.contains("Foobar"))
        assertTrue(data.contains("rt_bool"))
        assertTrue(data.contains("rt_str"))
        assertTrue(data.contains("rt_int"))
        assertTrue(data.contains("rt_float"))
    }
}