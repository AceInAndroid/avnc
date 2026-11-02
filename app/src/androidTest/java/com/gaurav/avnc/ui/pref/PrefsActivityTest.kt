/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.pref

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.gaurav.avnc.R
import com.gaurav.avnc.R.string.*
import com.gaurav.avnc.checkIsDisplayed
import com.gaurav.avnc.checkWillBeDisplayed
import com.gaurav.avnc.doClick
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.ui.prefs.PrefsActivity
import com.gaurav.avnc.util.KnownHostsTest.Companion.getTestCert
import com.gaurav.avnc.util.isCertificateTrusted
import com.gaurav.avnc.util.trustCertificate
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not

/**
 * Simple tests to make sure [PrefsActivity] is not completely broken.
 */
class PrefsActivityTest {

    @Rule
    @JvmField
    val prefActivity = ActivityScenarioRule(PrefsActivity::class.java)

    @Test
    fun uiTest() {
        openSection(settings_appearance, settings_appearance_summary, pref_theme)
    }

    @Test
    fun viewerTest() {
        openSection(settings_appearance, settings_appearance_summary, pref_theme)
        onView(withText(pref_viewer)).doClick()
        onView(withText(pref_fullscreen)).checkWillBeDisplayed()
    }

    @Test
    fun inputTest() {
        openSection(settings_input, settings_input_summary, pref_system_keyboard)
        onView(withText(settings_input_details)).doClick()
        onView(withText(pref_gesture_style)).checkWillBeDisplayed()
    }

    @Test
    fun serverTest() {
        openSection(settings_connection, settings_connection_summary, pref_clipboard_sync)
    }

    @Test
    fun toolsTest() {
        openSection(settings_advanced, settings_advanced_summary, title_about)
    }

    @Test
    fun forgetKnownHosts() {
        trustCertificate(targetContext, getTestCert())
        Assert.assertTrue(isCertificateTrusted(targetContext, getTestCert()))

        openSection(settings_connection, settings_connection_summary, pref_forget_known_hosts)
        onView(withText(pref_forget_known_hosts)).doClick()
        onView(withText(pref_forget_known_hosts_question)).checkWillBeDisplayed()
        onView(withText(title_forget)).doClick()
        onView(withText(msg_done)).checkWillBeDisplayed()

        Assert.assertFalse(isCertificateTrusted(targetContext, getTestCert()))
    }

    /**
     * Validates section can be opened.
     */
    private fun openSection(sectionNameId: Int, sectionSummaryId: Int, childPrefNameId: Int) {
        val home = isDescendantOfA(withId(R.id.settings_home))
        onView(allOf(withText(sectionNameId), home)).perform(scrollTo()).checkIsDisplayed()
        onView(allOf(withText(sectionSummaryId), home)).checkIsDisplayed()

        onView(allOf(withText(sectionNameId), home)).doClick()

        onView(withText(childPrefNameId)).checkWillBeDisplayed()
        onView(allOf(withText(sectionNameId), isDescendantOfA(withId(R.id.toolbar)), isDisplayed())).checkIsDisplayed()
        onView(allOf(withText(sectionSummaryId), home)).check(matches(not(isDisplayed())))
    }
}
