/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.edit
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.util.ClipboardHistory
import com.gaurav.avnc.util.getClipboard
import com.gaurav.avnc.util.setClipboardText
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardControlsTest : VncSessionTest() {
    @JvmField @Rule val cleanPrefs = CleanPrefsRule()
    private val history = ClipboardHistory(targetContext)
    @Before fun prepare() {
        history.clear()
        targetPrefs.edit { putBoolean("clipboard_sync", true); putBoolean("clipboard_auto_send", false) }
    }
    @After fun clean() { history.clear() }

    /** Read and restore full ClipData while the target UID is foreground, preserving sensitivity metadata. */
    private fun inSession(test: () -> Unit) {
        vncSession.run {
            var original: ClipData? = null
            vncSession.onActivity { activity ->
                val manager = getClipboard(activity)
                original = manager.primaryClip
                // Resetting URI/Intent clips can revoke their original transient permission grants.
                org.junit.Assume.assumeTrue("Clipboard tests require restorable text-only ClipData",
                        original?.let { clip -> (0 until clip.itemCount).all {
                            clip.getItemAt(it).uri == null && clip.getItemAt(it).intent == null
                        } } ?: true)
                manager.setPrimaryClip(ClipData.newPlainText(null, ""))
            }
            try { test() }
            finally {
                vncSession.onActivity { activity ->
                    activity.clipboardControls.onPause()
                    val manager = getClipboard(activity)
                    original?.let { manager.setPrimaryClip(it) } ?: run {
                        if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip()
                        else manager.setPrimaryClip(ClipData.newPlainText(null, ""))
                    }
                }
            }
        }
    }

    @Test fun manualSendWorksWithoutAutoAndDoesNotPasteOrClick() {
        inSession {
            runBlocking { setClipboardText(targetContext, "manual text") }
            vncSession.onActivity { it.clipboardControls.show() }
            onView(withTagValue(equalTo("clipboard_send_current"))).inRoot(isDialog()).perform(scrollTo(), click())
            pollingAssert { assertEquals("manual text", vncSession.server.receivedCutText) }
            assertFalse(targetPrefs.getBoolean("clipboard_auto_send", false))
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun enablingAutoSendsCurrentThenFocusedChangesAndRemoteUpdatesDoNotEnterHistory() {
        inSession {
            runBlocking { setClipboardText(targetContext, "first cut") }
            vncSession.onActivity { it.clipboardControls.show() }
            onView(withTagValue(equalTo("clipboard_auto_send"))).inRoot(isDialog()).perform(click())
            pollingAssert { assertEquals("first cut", vncSession.server.receivedCutText) }
            // The app's own manager dialog is foreground too; its window must keep sync active.
            runBlocking { setClipboardText(targetContext, "second cut") }
            pollingAssert { assertEquals("second cut", vncSession.server.receivedCutText) }
            pollingAssert { assertTrue(history.entries().any { it.text == "second cut" }) }
            androidx.test.espresso.Espresso.pressBack()
            vncSession.server.sendCutText("remote-only")
            pollingAssert {
                vncSession.onActivity { assertEquals("remote-only", it.viewModel.lastRemoteClipboardText) }
            }
            pollingAssert { assertEquals("remote-only", runBlocking { com.gaurav.avnc.util.getClipboardText(targetContext) }) }
            // A later local change proves the remote clipboard update did not poison the listener.
            runBlocking { setClipboardText(targetContext, "third cut") }
            pollingAssert {
                assertEquals("third cut", vncSession.server.receivedCutText)
                assertEquals(listOf("first cut", "second cut", "third cut"), vncSession.server.receivedCutTexts)
            }
            assertFalse(history.entries().any { it.text == "remote-only" })
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun remoteUpdateAllowsAnEarlierLocalValueToBeSentAgain() {
        inSession {
            vncSession.onActivity {
                targetPrefs.edit { putBoolean("clipboard_auto_send", true) }
            }
            runBlocking { setClipboardText(targetContext, "local A") }
            pollingAssert { assertEquals(listOf("local A"), vncSession.server.receivedCutTexts) }
            vncSession.server.sendCutText("remote B")
            pollingAssert { assertEquals("remote B", runBlocking { com.gaurav.avnc.util.getClipboardText(targetContext) }) }
            runBlocking { setClipboardText(targetContext, "local A") }
            pollingAssert { assertEquals(listOf("local A", "local A"), vncSession.server.receivedCutTexts) }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun localSendSupersedesRemoteOriginMarkerForLaterCopies() {
        inSession {
            vncSession.onActivity { targetPrefs.edit { putBoolean("clipboard_auto_send", true) } }
            vncSession.server.sendCutText("remote A")
            pollingAssert { assertEquals("remote A", runBlocking { com.gaurav.avnc.util.getClipboardText(targetContext) }) }
            runBlocking { setClipboardText(targetContext, "local B") }
            pollingAssert { assertEquals(listOf("local B"), vncSession.server.receivedCutTexts) }
            runBlocking { setClipboardText(targetContext, "remote A") }
            pollingAssert { assertEquals(listOf("local B", "remote A"), vncSession.server.receivedCutTexts) }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun disablingAutoRevokesPendingCaptureButManualSendStillWorks() {
        inSession {
            vncSession.onActivity { activity ->
                getClipboard(activity).setPrimaryClip(ClipData.newPlainText(null, "pending automatic text"))
                // Both edits run in one main-thread turn; any capture must suspend before it can send.
                targetPrefs.edit { putBoolean("clipboard_auto_send", true) }
                targetPrefs.edit { putBoolean("clipboard_auto_send", false) }
            }
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.gaurav.avnc.R.id.frame_view)).perform(
                    object : androidx.test.espresso.ViewAction {
                        override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.view.View::class.java)
                        override fun getDescription() = "Advance past the initial clipboard negotiation window"
                        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                            controller.loopMainThreadForAtLeast(1200)
                        }
                    })
            assertTrue(vncSession.server.receivedCutTexts.isEmpty())
            vncSession.onActivity { it.clipboardControls.sendCurrentClipboard() }
            pollingAssert { assertEquals(listOf("pending automatic text"), vncSession.server.receivedCutTexts) }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
    }

    @Test fun returningWithClipboardDialogOpenResumesAutomaticSync() {
        inSession {
            vncSession.onActivity {
                targetPrefs.edit { putBoolean("clipboard_auto_send", true) }
                it.clipboardControls.show()
            }
            runBlocking { setClipboardText(targetContext, "before pause") }
            pollingAssert { assertEquals("before pause", vncSession.server.receivedCutText) }
            vncSession.onActivity { it.clipboardControls.onPause() }
            runBlocking { setClipboardText(targetContext, "copied while away") }
            assertEquals(listOf("before pause"), vncSession.server.receivedCutTexts)
            vncSession.onActivity {
                // The manager dialog owns window focus while the activity itself does not.
                assertFalse(it.hasWindowFocus())
                it.clipboardControls.onResume()
            }
            pollingAssert { assertEquals(listOf("before pause", "copied while away"), vncSession.server.receivedCutTexts) }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun recentTextActionsCopyResendDeleteAndClearWithoutAutomaticPaste() {
        inSession {
            vncSession.onActivity { it.clipboardControls.onWindowFocusChanged(false) }
            history.clear()
            val saved = history.record("saved text", sensitive = false).first()
            history.record("another text", sensitive = false)
            vncSession.onActivity { it.clipboardControls.show() }
            pollingAssert {
                onView(withTagValue(equalTo("clipboard_copy_${saved.id}"))).inRoot(isDialog()).check(
                        androidx.test.espresso.assertion.ViewAssertions.matches(
                                androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.Button::class.java)))
            }
            onView(withTagValue(equalTo("clipboard_copy_${saved.id}"))).inRoot(isDialog()).perform(scrollTo(), click())
            pollingAssert { assertEquals("saved text", runBlocking { com.gaurav.avnc.util.getClipboardText(targetContext) }) }
            onView(withTagValue(equalTo("clipboard_send_${saved.id}"))).inRoot(isDialog()).perform(scrollTo(), click())
            pollingAssert { assertEquals("saved text", vncSession.server.receivedCutText) }
            onView(withTagValue(equalTo("clipboard_delete_${saved.id}"))).inRoot(isDialog()).perform(scrollTo(), click())
            pollingAssert { assertFalse(history.entries().any { it.id == saved.id }) }
            onView(withTagValue(equalTo("clipboard_clear"))).inRoot(isDialog()).perform(scrollTo(), click())
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            pollingAssert { assertTrue(history.entries().isEmpty()) }
        }
        assertTrue(vncSession.server.receivedKeySyms.isEmpty())
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun masterOffReadOnlyAndPipRejectExplicitSends() {
        inSession {
            runBlocking { setClipboardText(targetContext, "blocked") }
            vncSession.onActivity { activity ->
                activity.viewModel.setViewMode(ServerProfile.VIEW_MODE_NO_INPUT)
                assertFalse(activity.viewModel.sendClipboardText("read only"))
                activity.viewModel.setViewMode(ServerProfile.VIEW_MODE_NORMAL)
                targetPrefs.edit { putBoolean("clipboard_sync", false) }
                assertFalse(activity.viewModel.sendClipboardText("master off"))
                targetPrefs.edit { putBoolean("clipboard_sync", true) }
                activity.viewModel.inPiPMode.value = true
                assertFalse(activity.viewModel.sendClipboardText("pip"))
                activity.viewModel.inPiPMode.value = false
            }
        }
        assertEquals("", vncSession.server.receivedCutText)
    }

    @Test fun pauseOrFocusLossStopsAutomaticSendsAndSensitiveClipsAreNotSaved() {
        inSession {
            vncSession.onActivity { activity ->
                activity.clipboardControls.onPause()
                activity.clipboardControls.onWindowFocusChanged(false)
                targetPrefs.edit { putBoolean("clipboard_auto_send", true) }
            }
            runBlocking { setClipboardText(targetContext, "hidden") }
            vncSession.onActivity { activity ->
                assertEquals("", vncSession.server.receivedCutText)
                activity.clipboardControls.onResume()
                activity.clipboardControls.onWindowFocusChanged(true)
            }
            pollingAssert { assertEquals("hidden", vncSession.server.receivedCutText) }
            if (Build.VERSION.SDK_INT >= 24) {
                vncSession.onActivity {
                    val sensitive = ClipData.newPlainText(null, "private clip")
                    sensitive.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    getClipboard(it).setPrimaryClip(sensitive)
                }
                pollingAssert { assertEquals("private clip", vncSession.server.receivedCutText) }
                assertFalse(history.entries().any { it.text == "private clip" })
            }
        }
    }
}
