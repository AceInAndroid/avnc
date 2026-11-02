/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.checkWillBeDisplayed
import com.gaurav.avnc.inDialog
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharingControlsUiTest : VncSessionTest() {
    @JvmField @Rule val prefs = CleanPrefsRule()

    @Test fun sharingMenuOpensClipboardWithoutRemoteInput() {
        vncSession.profile.remotePlatform = "mac"
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.toolbar.open()
                val root = activity.findViewById<View>(R.id.controls_menu)
                assertTrue(root.findViewWithTag<View>("controls_send_file").isEnabled)
                root.findViewWithTag<View>("controls_clipboard").performClick()
                assertFalse(activity.toolbar.isOpen)
                assertFalse(activity.computerKeyboard.isVisible)
            }
            onView(withText(R.string.clipboard_title)).inDialog().checkWillBeDisplayed()
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun readonlyKeepsClipboardHistoryAccessibleButDisablesFileSending() {
        vncSession.run {
            vncSession.onActivity { it.viewModel.setViewMode(ServerProfile.VIEW_MODE_NO_INPUT) }
            pollingAssert {
                vncSession.onActivity { activity ->
                    activity.toolbar.open()
                    val root = activity.findViewById<View>(R.id.controls_menu)
                    assertFalse(root.findViewWithTag<View>("controls_send_file").isEnabled)
                    assertTrue(root.findViewWithTag<View>("controls_clipboard").isEnabled)
                }
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun fileEntryOpensConnectionSetupWithDownloadsDestination() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.toolbar.open()
                activity.findViewById<View>(R.id.controls_menu)
                        .findViewWithTag<View>("controls_send_file").performClick()
                assertFalse(activity.toolbar.isOpen)
                assertFalse(activity.computerKeyboard.isVisible)
            }
            onView(withText(R.string.file_transfer_title)).inDialog().checkWillBeDisplayed()
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }
}
