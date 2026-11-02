/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.app.Activity
import android.app.Instrumentation
import androidx.core.content.edit
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.PositionAssertions.isCompletelyAbove
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerListTest {
    private val testProfile = ServerProfile(name = "Test Profile", host = "123.123.123.123")
    private val activityRule = ActivityScenarioRule(HomeActivity::class.java)
    private val dbRule = EmptyDatabaseRule()
    @Rule @JvmField val rules: RuleChain = RuleChain.outerRule(HomeTestPreferencesRule()).around(dbRule).around(activityRule)

    private fun testProfileMatcher() = allOf(withTagValue(equalTo<Any>("saved-${testProfile.ID}")), hasDescendant(withText(testProfile.name)))
    private fun openMore() = onView(allOf(withId(R.id.home_row_more),
            isDescendantOfA(testProfileMatcher()))).doClick()

    @Before fun before() {
        testProfile.ID = runBlocking { dbRule.db.serverProfileDao.save(testProfile) }
        onView(withId(R.id.home_list)).performWithTimeout(
                RecyclerViewActions.scrollTo<RecyclerView.ViewHolder>(testProfileMatcher()))
        onView(testProfileMatcher()).checkWillBeDisplayed()
    }

    @Test fun launchConnection() {
        Intents.init()
        try {
            Intents.intending(hasComponent(VncActivity::class.qualifiedName))
                    .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            onView(testProfileMatcher()).doClick()
            Intents.intended(hasComponent(VncActivity::class.qualifiedName))
            Intents.assertNoUnverifiedIntents()
        } finally { Intents.release() }
    }

    @Test fun launchEditorKeepsSavedFields() {
        openMore()
        onView(withText(R.string.title_edit)).doClick()
        onView(withText(R.string.title_edit_server_profile)).checkWillBeDisplayed()
        onView(withId(R.id.name)).check(matches(withText(testProfile.name)))
        onView(withId(R.id.host)).check(matches(withText(testProfile.host)))
        onView(withId(R.id.port)).check(matches(withText(testProfile.port.toString())))
    }

    @Test fun deleteProfileAndUndoRestoreTheSameSavedId() {
        openMore()
        onView(withText(R.string.title_delete)).doClick()
        pollingAssert { assertTrue(runBlocking { dbRule.db.serverProfileDao.getList() }.isEmpty()) }
        onView(withText(R.string.msg_server_profile_deleted)).checkWillBeDisplayed()
        onView(withText(R.string.title_undo)).doClick()
        pollingAssert { assertEquals(testProfile, runBlocking { dbRule.db.serverProfileDao.getList().single() }) }
        onView(testProfileMatcher()).checkWillBeDisplayed()
    }

    @Test fun copyAddressIncludesTheConfiguredPort() {
        var original: android.content.ClipData? = null
        activityRule.scenario.onActivity {
            original = com.gaurav.avnc.util.getClipboard(it).primaryClip
            org.junit.Assume.assumeTrue("Copy test requires a restorable text clipboard", original?.let { clip ->
                (0 until clip.itemCount).all { index -> clip.getItemAt(index).uri == null && clip.getItemAt(index).intent == null }
            } ?: true)
        }
        try {
            openMore()
            onView(withText(R.string.home_copy_address)).doClick()
            pollingAssert { assertEquals("${testProfile.host}:${testProfile.port}", getClipboardText()) }
            onView(withText(R.string.msg_copied_to_clipboard)).checkWillBeDisplayed()
        } finally {
            activityRule.scenario.onActivity {
                val manager = com.gaurav.avnc.util.getClipboard(it)
                original?.let(manager::setPrimaryClip) ?: run {
                    if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                    else manager.setPrimaryClip(android.content.ClipData.newPlainText(null, ""))
                }
            }
        }
    }

    @Test fun duplicateCreatesAnIndependentSavedRow() {
        openMore()
        onView(withText(R.string.title_duplicate)).doClick()
        onView(withId(R.id.host)).check(matches(withText(testProfile.host)))
        onView(withId(R.id.name)).check(matches(withText("${testProfile.name} (Copy)")))
        onView(withId(R.id.save_btn)).doClick()
        pollingAssert {
            val profiles = runBlocking { dbRule.db.serverProfileDao.getList() }
            assertEquals(2, profiles.size)
            assertEquals(testProfile, profiles.single { it.ID == testProfile.ID })
            val copy = profiles.single { it.ID != testProfile.ID }
            assertTrue(copy.ID > 0)
            assertEquals(testProfile.host, copy.host)
            assertEquals("${testProfile.name} (Copy)", copy.name)
        }
    }

    @Test fun sortServersRetainsInsertionAndAlphabeticalOrders() {
        runBlocking {
            dbRule.db.serverProfileDao.deleteAll()
            dbRule.db.serverProfileDao.save(ServerProfile(name = "pqr", host = "192.0.2.1"))
            dbRule.db.serverProfileDao.save(ServerProfile(name = "abc", host = "192.0.2.2"))
        }
        targetPrefs.edit { putBoolean("sort_server_list", false) }
        onView(withText("pqr")).checkWithTimeout(isCompletelyAbove(withText("abc")))
        targetPrefs.edit { putBoolean("sort_server_list", true) }
        onView(withText("abc")).checkWithTimeout(isCompletelyAbove(withText("pqr")))
    }
}
