/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.model.DisplayViewMode
import com.gaurav.avnc.model.RemoteDisplay
import com.gaurav.avnc.model.RemoteDisplayLayout
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.StreamingPreset
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.util.isKeyboardVisible
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.vnc.XKeySym
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Public Mac controls against the recording VNC server; dialog gestures must stay local. */
@RunWith(AndroidJUnit4::class)
class MacViewerUiTest : VncSessionTest() {
    @JvmField @Rule val cleanPrefs = CleanPrefsRule()

    private fun macSession() {
        vncSession = VncSessionScenario(TestServer(width = 200, height = 120))
        vncSession.profile.remotePlatform = "mac"
    }

    private fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun dialogChoice(label: Int) = onView(withText(startsWith(targetContext.getString(label)))).inRoot(isDialog())

    @Test fun keyboardReusesOrderedMainTabsAndNeverOpensSystemIme() {
        macSession()
        vncSession.run {
            vncSession.onActivity { activity ->
                val capsule = activity.findViewById<View>(R.id.controls_capsule)
                assertTrue(capsule.isShown)
                assertEquals(listOf("input_tab_keyboard", "input_tab_touchpad", "input_tab_shortcuts", "input_more"),
                        descendants(capsule).mapNotNull { it.tag?.toString() }.filter { it.startsWith("input_") })
                assertTrue(activity.findViewById<View>(R.id.keyboard_btn).performClick())
            }
            vncSession.onActivity { activity ->
                val keyboard = activity.computerKeyboard.container
                val header = checkNotNull(keyboard.findViewWithTag<View>("keyboard_header"))
                assertEquals(listOf("input_tab_keyboard", "input_tab_touchpad", "input_tab_shortcuts", "input_more"),
                        descendants(header).mapNotNull { it.tag?.toString() }.filter { it.startsWith("input_") })
                assertNotNull(header.findViewById<View>(R.id.keyboard_tab))
                assertNotNull(header.findViewById<View>(R.id.keyboard_touchpad))
                assertNotNull(header.findViewById<View>(R.id.keyboard_shortcuts))
                assertNotNull(header.findViewById<View>(R.id.keyboard_more))
                assertNull(keyboard.findViewById<View>(R.id.mac_controls_row))
                assertFalse(activity.findViewById<View>(R.id.controls_capsule).isShown)
                assertFalse(isKeyboardVisible(activity.binding.inputView))
                assertTrue(header.findViewWithTag<View>("keyboard_close").performClick())
                assertFalse(activity.computerKeyboard.isVisible)
                assertTrue(activity.findViewById<View>(R.id.controls_capsule).isShown)
                assertEquals(0, descendants(activity.binding.viewerRoot).count { it.id == R.id.mac_controls_row && it.isShown })
            }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun displayDialogSwitchesSingleAndAllWithoutClickingRemoteDesktop() {
        macSession()
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.saveManualDisplayLayout(RemoteDisplayLayout(200, 120, listOf(
                        RemoteDisplay("left", "Left", 0, 0, 100, 120),
                        RemoteDisplay("right", "Right", 100, 0, 100, 120)), "manual"))
                activity.toolbar.macControls.showDisplays()
            }
            dialogChoice(R.string.mac_display_all).perform(scrollTo(), click())
            vncSession.onActivity { assertEquals(DisplayViewMode.ALL, it.viewModel.displayViewMode) }
            onView(withText(startsWith("Right ·"))).inRoot(isDialog()).perform(scrollTo(), click())
            vncSession.onActivity {
                assertEquals(DisplayViewMode.SINGLE, it.viewModel.displayViewMode)
                assertEquals("right", it.viewModel.selectedDisplay?.id)
                assertEquals(100f, it.viewModel.frameState.contentRect.left)
                assertFalse(it.computerKeyboard.isVisible)
            }
            dialogChoice(R.string.control_done).perform(click())
        }
        assertTrue(vncSession.server.receivedPointerEvents.all { it.mask == 0 })
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun qualityDialogShowsPendingFailureAndAppliedPreset() {
        macSession()
        vncSession.run {
            vncSession.onActivity {
                it.toolbar.macControls.showQuality()
                it.viewModel.streamingPresetPending.value = true
            }
            onView(withText(R.string.mac_quality_pending)).inRoot(isDialog()).check(matches(isDisplayed()))
            dialogChoice(R.string.mac_quality_hd).check(matches(not(isEnabled())))
            vncSession.onActivity {
                it.viewModel.streamingPresetPending.value = false
                it.viewModel.streamingPresetFailed.value = true
            }
            onView(withText(R.string.mac_quality_failed)).inRoot(isDialog()).check(matches(isDisplayed()))
            dialogChoice(R.string.mac_quality_hd).perform(scrollTo(), click())
            pollingAssert {
                vncSession.onActivity {
                    assertEquals(StreamingPreset.HD, it.viewModel.streamingPreset.value)
                    assertEquals(false, it.viewModel.streamingPresetPending.value)
                    assertEquals(false, it.viewModel.streamingPresetFailed.value)
                    assertFalse(it.computerKeyboard.isVisible)
                    assertFalse(isKeyboardVisible(it.binding.inputView))
                }
            }
            dialogChoice(R.string.control_done).perform(click())
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun manualEditorGesturesStayLocalAndCoordinateDialogClosesOnDisconnect() {
        macSession()
        vncSession.run {
            vncSession.onActivity { it.toolbar.macControls.showDisplays() }
            dialogChoice(R.string.mac_display_manual).perform(scrollTo(), click())
            dialogChoice(R.string.mac_display_horizontal).perform(scrollTo(), click())
            onView(isAssignableFrom(DisplayLayoutDiagram::class.java)).inRoot(isDialog()).perform(localDiagramDrag())
            // Add/delete mutate the staged layout, without selecting a remote display or sending a click.
            dialogChoice(R.string.mac_display_add).perform(scrollTo(), click())
            dialogChoice(R.string.mac_display_delete).perform(scrollTo(), click())
            onView(isAssignableFrom(DisplayLayoutDiagram::class.java)).inRoot(isDialog()).perform(openFirstCoordinates())
            onView(withText(R.string.mac_display_coordinates)).inRoot(isDialog()).check(matches(isDisplayed()))
            // Exercise the real connection-state notification without terminating the test server.
            vncSession.onActivity { it.viewModel.state.value = VncViewModel.State.Disconnected }
            onView(withText(R.string.mac_display_coordinates)).check(doesNotExist())
            onView(withText(R.string.mac_display_manual)).check(doesNotExist())
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun dockInMoreUsesShortcutOrEdgeAndRespectsReadOnly() {
        macSession()
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.setDockActivation("shortcut")
                assertTrue(activity.findViewById<View>(R.id.controls_more).performClick())
                checkNotNull(activity.binding.viewerRoot.findViewWithTag<View>("controls_mac_dock")).performClick()
            }
            pollingAssert { assertEquals(listOf(XKeySym.XK_Control_L, XKeySym.XK_F3), vncSession.server.receivedKeyDowns) }
            vncSession.onActivity { activity ->
                activity.setDockActivation("bottom")
                assertTrue(activity.findViewById<View>(R.id.controls_more).performClick())
                checkNotNull(activity.binding.viewerRoot.findViewWithTag<View>("controls_mac_dock")).performClick()
            }
            pollingAssert { assertTrue(vncSession.server.receivedPointerEvents.any { it.mask == 0 && it.x == 100 && it.y == 119 }) }
            val count = vncSession.server.receivedPointerEvents.size
            vncSession.onActivity { activity ->
                activity.viewModel.setViewMode(ServerProfile.VIEW_MODE_NO_INPUT)
                assertTrue(activity.findViewById<View>(R.id.controls_more).performClick())
                assertFalse(checkNotNull(activity.binding.viewerRoot.findViewWithTag<View>("controls_mac_dock")).isEnabled)
                activity.performMacDock()
            }
            assertEquals(count, vncSession.server.receivedPointerEvents.size)
        }
        assertTrue(vncSession.server.receivedPointerEvents.all { it.mask == 0 })
        assertEquals(vncSession.server.receivedKeyDowns.sorted(), vncSession.server.receivedKeySyms.filter { !it.second }.map { it.first }.sorted())
    }

    private fun openFirstCoordinates() = object : ViewAction {
        override fun getConstraints() = isAssignableFrom(DisplayLayoutDiagram::class.java)
        override fun getDescription() = "Open exact coordinates for the first local screen region"
        override fun perform(uiController: UiController, view: View) {
            val label = targetContext.getString(R.string.mac_display_coordinates)
            descendants(view.rootView).first { it is TextView && it.text.toString() == label }.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    private fun localDiagramDrag() = object : ViewAction {
        override fun getConstraints() = isAssignableFrom(DisplayLayoutDiagram::class.java)
        override fun getDescription() = "Drag a manual screen region inside the local layout diagram"
        override fun perform(uiController: UiController, view: View) {
            val time = SystemClock.uptimeMillis()
            listOf(Triple(MotionEvent.ACTION_DOWN, view.width * .25f, view.height * .5f),
                    Triple(MotionEvent.ACTION_MOVE, view.width * .3f, view.height * .55f),
                    Triple(MotionEvent.ACTION_UP, view.width * .3f, view.height * .55f)).forEachIndexed { index, (action, x, y) ->
                MotionEvent.obtain(time, time + index * 20L, action, x, y, 0).let { event -> view.dispatchTouchEvent(event); event.recycle() }
            }
            uiController.loopMainThreadUntilIdle()
        }
    }
}
