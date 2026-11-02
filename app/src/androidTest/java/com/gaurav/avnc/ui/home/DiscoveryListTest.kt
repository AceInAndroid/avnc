/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.app.Activity
import android.app.Instrumentation
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.*
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.vnc.VncActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.equalTo
import org.hamcrest.core.AllOf.allOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoveryListTest {
    private val activityRule = ActivityScenarioRule(HomeActivity::class.java)
    private val dbRule = EmptyDatabaseRule()
    @Rule @JvmField val rules: RuleChain = RuleChain.outerRule(HomeTestPreferencesRule()).around(dbRule).around(activityRule)

    private val testProfile = ServerProfile(name = "Test Profile", host = "123.123.123.123")
    private val address get() = "${testProfile.host}:${testProfile.port}"
    private fun testProfileMatcher() = allOf(
            withTagValue(equalTo<Any>("local-${testProfile.name}-$address")),
            hasDescendant(withText(testProfile.name)), hasDescendant(withText(address)))

    private fun revealProfile() {
        onView(withId(R.id.home_list)).performWithTimeout(
                RecyclerViewActions.scrollTo<RecyclerView.ViewHolder>(testProfileMatcher()))
        onView(testProfileMatcher()).checkWillBeDisplayed()
    }
    private fun addTestProfile() {
        // UI boundary injection only; real NSD lifecycle is covered by DiscoveryTest.
        activityRule.scenario.onActivity { it.viewModel.discovery.servers.value = listOf(testProfile) }
        revealProfile()
    }
    private fun openMore() = onView(allOf(withId(R.id.home_row_more),
            isDescendantOfA(testProfileMatcher()))).doClick()

    @Test fun launchConnection() {
        Intents.init()
        try {
            Intents.intending(hasComponent(VncActivity::class.qualifiedName))
                    .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            addTestProfile()
            onView(testProfileMatcher()).doClick()
            Intents.intended(hasComponent(VncActivity::class.qualifiedName))
            Intents.assertNoUnverifiedIntents()
        } finally { Intents.release() }
    }

    @Test fun discoveredComputerOpensPrefilledQuickFormAndCanBeSaved() {
        addTestProfile()
        openMore()
        onView(withText(R.string.home_save_computer)).doClick()
        onView(withText(R.string.title_add_server_profile)).checkWillBeDisplayed()
        onView(withId(R.id.name)).check(matches(withText(testProfile.name)))
        onView(withId(R.id.host)).check(matches(withText(testProfile.host)))
        onView(withId(R.id.port)).check(matches(withText(testProfile.port.toString())))
        onView(withId(R.id.save_btn)).doClick()
        pollingAssert {
            val saved = runBlocking { dbRule.db.serverProfileDao.getList().single() }
            assertTrue(saved.ID > 0L)
            assertEquals(testProfile.name, saved.name)
            assertEquals(testProfile.host, saved.host)
            assertEquals(testProfile.port, saved.port)
        }
    }

    @Test fun copyAddressUsesHostAndPortWithoutStartingAConnection() {
        addTestProfile()
        openMore()
        onView(withText(R.string.home_copy_address)).doClick()
        pollingAssert { assertEquals(address, getClipboardText()) }
        onView(withText(R.string.msg_copied_to_clipboard)).checkWillBeDisplayed()
    }

    @Test fun emptyLocalNetworkShowsGuidanceAndFindAction() {
        activityRule.scenario.onActivity { it.viewModel.discovery.servers.value = emptyList() }
        onView(withId(R.id.home_list)).performWithTimeout(
                RecyclerViewActions.scrollTo<RecyclerView.ViewHolder>(hasDescendant(withText(R.string.home_discovery_help))))
        onView(withText(R.string.home_discovery_help)).checkWillBeDisplayed()
        onView(withId(R.id.home_discovery_action)).check(matches(withText(R.string.home_find_devices)))
        assertTrue(runBlocking { dbRule.db.serverProfileDao.getList() }.isEmpty())
    }
}
