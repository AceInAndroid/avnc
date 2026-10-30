/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.view.View
import android.widget.SeekBar
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.checkWillBeDisplayed
import com.gaurav.avnc.model.RemoteInputOverrides
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.model.db.MainDb
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.util.RemoteInputPreferences
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.vnc.XKeySym
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the public viewer controls through real clicks, including scrollable dialog rows. */
@RunWith(AndroidJUnit4::class)
class RemoteInputEditorTest : VncSessionTest() {
    @JvmField @Rule val cleanPrefs = CleanPrefsRule()

    private fun openComputerSettings() {
        onView(withId(R.id.controls_more)).perform(click())
        onView(withTagValue(equalTo("remote_input_session"))).perform(scrollTo(), click())
        onView(withTagValue(equalTo("remote_input_platform"))).inRoot(isDialog()).checkWillBeDisplayed()
    }

    private fun choosePlatform(platform: RemotePlatform, label: Int) {
        onView(withTagValue(equalTo("remote_input_platform"))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText(label)).inRoot(isDialog()).perform(click())
        pollingAssert {
            vncSession.onActivity {
                assertEquals(platform.id, it.viewModel.profile.remotePlatform)
                assertEquals(platform, it.viewModel.remoteInputConfig.platform)
                assertFalse(it.computerKeyboard.isVisible)
                assertFalse(isKeyboardVisible(it.binding.inputView))
            }
        }
    }

    private fun closeComputerSettings() {
        onView(withText(R.string.remote_input_done)).inRoot(isDialog()).perform(click())
    }

    @Test fun moreSettingsSwitchesMacAndWindowsWithoutOpeningKeyboardOrSendingPointerInput() {
        vncSession.run {
            openComputerSettings()
            choosePlatform(RemotePlatform.MAC, R.string.remote_input_mac)
            choosePlatform(RemotePlatform.WINDOWS, R.string.remote_input_windows)
            closeComputerSettings()
            vncSession.onActivity {
                assertFalse(it.toolbar.isOpen)
                assertFalse(it.computerKeyboard.isVisible)
                assertFalse(isKeyboardVisible(it.binding.inputView))
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun moreSystemActionCopiesWithCommandOnMacWithoutShowingKeyboardOrClickingDesktop() {
        vncSession.run {
            openComputerSettings()
            choosePlatform(RemotePlatform.MAC, R.string.remote_input_mac)
            closeComputerSettings()
            onView(withId(R.id.controls_more)).perform(click())
            onView(withText(R.string.remote_input_shortcuts)).perform(scrollTo(), click())
            onView(withTagValue(equalTo("remote_action_copy"))).perform(scrollTo(), click())
            pollingAssert {
                assertEquals(listOf(XKeySym.XK_Super_L to true, 'c'.code to true,
                        'c'.code to false, XKeySym.XK_Super_L to false),
                        vncSession.server.receivedKeySyms)
            }
            vncSession.onActivity {
                assertFalse(it.toolbar.isOpen)
                assertFalse(it.computerKeyboard.isVisible)
                assertFalse(isKeyboardVisible(it.binding.inputView))
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun speedSliderPersistsPerComputerAndResetRestoresTemplateInheritance() {
        val dao = MainDb.getInstance(targetContext).serverProfileDao
        val preferences = RemoteInputPreferences(targetContext)
        preferences.saveTemplate(RemotePlatform.MAC, RemoteInputOverrides(pointerSpeed = .75f))
        vncSession.profile.remotePlatform = RemotePlatform.MAC.id
        vncSession.saveProfileToDB(MainDb.getInstance(targetContext))
        try {
            vncSession.run {
                openComputerSettings()
                onView(withTagValue(equalTo("remote_input_pointer_speed"))).inRoot(isDialog()).perform(scrollTo(), click())
                // Progress is in 0.05 increments above 0.25: 30 selects 1.75x.
                onView(isAssignableFrom(SeekBar::class.java)).inRoot(isDialog()).perform(setProgress(30))
                onView(withText(R.string.remote_input_apply)).inRoot(isDialog()).perform(click())
                pollingAssert {
                    vncSession.onActivity {
                        assertEquals(1.75f, it.viewModel.remoteInputConfig.pointerSpeed, .001f)
                        assertEquals(1.75f, RemoteInputOverrides.decode(it.viewModel.profile.remoteInputOverrides).pointerSpeed!!, .001f)
                        assertFalse(it.computerKeyboard.isVisible)
                    }
                    val saved = runBlocking { checkNotNull(dao.getByID(vncSession.profile.ID)) }
                    assertEquals(1.75f, RemoteInputOverrides.decode(saved.remoteInputOverrides).pointerSpeed!!, .001f)
                }
                closeComputerSettings()
                openComputerSettings()
                onView(withTagValue(equalTo("remote_input_pointer_speed"))).inRoot(isDialog()).perform(scrollTo(), click())
                onView(withText(R.string.remote_input_follow_template)).inRoot(isDialog()).perform(click())
                pollingAssert {
                    vncSession.onActivity {
                        assertEquals(.75f, it.viewModel.remoteInputConfig.pointerSpeed, .001f)
                        assertNull(RemoteInputOverrides.decode(it.viewModel.profile.remoteInputOverrides).pointerSpeed)
                        assertEquals(RemotePlatform.MAC.id, it.viewModel.profile.remotePlatform)
                        assertFalse(it.computerKeyboard.isVisible)
                        assertFalse(isKeyboardVisible(it.binding.inputView))
                    }
                    val saved = runBlocking { checkNotNull(dao.getByID(vncSession.profile.ID)) }
                    assertNull(RemoteInputOverrides.decode(saved.remoteInputOverrides).pointerSpeed)
                }
                assertEquals(.75f, preferences.template(RemotePlatform.MAC).pointerSpeed, .001f)
                closeComputerSettings()
            }
        } finally {
            runBlocking { dao.delete(vncSession.profile) }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun scrollSliderAllowsSixTimesWithoutChangingPointerRange() {
        vncSession.run {
            openComputerSettings()
            onView(withTagValue(equalTo("remote_input_scroll_speed"))).inRoot(isDialog()).perform(scrollTo(), click())
            onView(isAssignableFrom(SeekBar::class.java)).inRoot(isDialog()).perform(setProgress(115))
            onView(withText(R.string.remote_input_apply)).inRoot(isDialog()).perform(click())
            vncSession.onActivity {
                assertEquals(6f, it.viewModel.remoteInputConfig.scrollSpeed, .001f)
                assertEquals(1f, it.viewModel.remoteInputConfig.pointerSpeed, .001f)
            }
            closeComputerSettings()
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    private fun setProgress(value: Int) = object : ViewAction {
        override fun getConstraints() = isAssignableFrom(SeekBar::class.java)
        override fun getDescription() = "Set pointer speed slider progress to $value"
        override fun perform(uiController: UiController, view: View) {
            (view as SeekBar).progress = value
            uiController.loopMainThreadUntilIdle()
        }
    }
}
