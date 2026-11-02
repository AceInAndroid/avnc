/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasDataString
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.checkWillBeDisplayed
import com.gaurav.avnc.doClick
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.about.AboutActivity
import com.gaurav.avnc.viewmodel.service.Discovery
import org.hamcrest.Matchers.containsString
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Restore preferences after each legacy Home case and keep discovery traffic deterministic. */
internal class HomeTestPreferencesRule : ExternalResource() {
    private var snapshot: Map<String, *> = emptyMap<String, Any?>()
    override fun before() {
        snapshot = targetPrefs.all.toMap()
        targetPrefs.edit {
            clear()
            putBoolean("discovery_autorun", false)
            putBoolean("prefer_advanced_editor", false)
            putBoolean("sort_server_list", false)
        }
        instrumentation.runOnMainSync { Discovery.stop() }
        pollingAssert {
            instrumentation.runOnMainSync {
                assertFalse(Discovery.isRunning.value == true)
                assertEquals(Discovery.Phase.STOPPED, Discovery.state.value?.phase)
            }
        }
        instrumentation.runOnMainSync { Discovery.servers.value = emptyList() }
    }
    override fun after() {
        instrumentation.runOnMainSync { Discovery.stop(); Discovery.servers.value = emptyList() }
        targetPrefs.edit {
            clear()
            snapshot.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
class BasicHomeTest {
    private val activityRule = ActivityScenarioRule(HomeActivity::class.java)
    @Rule @JvmField val rules: RuleChain = RuleChain.outerRule(HomeTestPreferencesRule())
            .around(EmptyDatabaseRule()).around(activityRule)

    @Before fun before() { Intents.init() }
    @After fun after() {
        try { Intents.assertNoUnverifiedIntents() } finally { Intents.release() }
    }

    private fun openSettings() = onView(withId(R.id.home_tab_settings)).doClick()
    private fun openAdvancedSettings() {
        openSettings()
        onView(allOf(withText(R.string.settings_advanced), isDisplayed())).perform(scrollTo()).doClick()
    }

    @Test fun openSettingsInPersistentTab() {
        openSettings()
        onView(withId(R.id.home_tab_settings)).check(matches(isSelected()))
        onView(withId(R.id.settings_search)).checkWillBeDisplayed()
        // Settings is hosted by Home; no replacement Activity should be launched.
    }

    @Test fun settingsBackReturnsToConnectionsWithoutLaunchingAnotherActivity() {
        openAdvancedSettings()
        onView(withText(R.string.title_about)).checkWillBeDisplayed()
        Espresso.pressBack()
        onView(withId(R.id.settings_search)).checkWillBeDisplayed()
        Espresso.pressBack()
        onView(withId(R.id.home_tab_connections)).check(matches(isSelected()))
        onView(withId(R.id.home_search)).checkWillBeDisplayed()
    }

    @Test fun openAboutFromAdvancedSettings() {
        openAdvancedSettings()
        onView(withText(R.string.title_about)).doClick()
        Intents.intended(hasComponent(AboutActivity::class.qualifiedName))
    }

    @Test fun addOpensQuickFormWithConnectionDefaults() {
        onView(withId(R.id.home_add)).doClick()
        onView(withText(R.string.title_add_server_profile)).checkWillBeDisplayed()
        onView(withId(R.id.host)).check(matches(withText("")))
        onView(withId(R.id.port)).check(matches(withText("5900")))
        onView(withId(R.id.save_connect_btn)).checkWillBeDisplayed()
        onView(withId(R.id.save_btn)).checkWillBeDisplayed()
    }

    @Test fun bugReportStillOpensTheActualIssueTracker() {
        Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        openAdvancedSettings()
        onView(withText(R.string.settings_bug_report)).doClick()
        Intents.intended(allOf(hasAction(Intent.ACTION_VIEW),
                hasDataString(containsString(AboutActivity.BUG_REPORT_URL))))
    }
}

@RunWith(AndroidJUnit4::class)
class AutoConnectTest {
    private val dbRule = EmptyDatabaseRule()
    @Rule @JvmField val rules: RuleChain = RuleChain.outerRule(HomeTestPreferencesRule()).around(dbRule)

    @Test fun autoConnectOnStartup() {
        val vncSession = VncSessionScenario()
        try {
            vncSession.profile.fConnectOnAppStart = true
            vncSession.saveProfileToDB(dbRule.db).suppressViewerHelp().startServer()
            ActivityScenario.launch(HomeActivity::class.java).use {
                vncSession.assertConnected()
                Espresso.pressBack()
            }
        } finally { vncSession.stop() }
    }
}
