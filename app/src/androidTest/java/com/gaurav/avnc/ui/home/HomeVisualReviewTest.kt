/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.graphics.Bitmap
import android.widget.EditText
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.R
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.prefs.PrefsActivity
import com.gaurav.avnc.ui.prefs.SettingsRootFragment
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.ui.vnc.createVncIntent
import com.gaurav.avnc.viewmodel.VncViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket

/** Opt-in reproducible visual evidence. All profiles are synthetic; rules isolate test data. */
@RunWith(AndroidJUnit4::class)
class HomeVisualReviewTest {
    @JvmField @Rule val prefs = CleanPrefsRule()
    @JvmField @Rule val database = EmptyDatabaseRule()

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(250) // Allow the bounded entrance/IME animations to finish.
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix") ?: "review"
        val file = File(targetContext.cacheDir, "visual/$prefix-$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun captureHomeSettingsEditorAndFailure() {
        targetPrefs.edit { putBoolean("discovery_autorun", false) }
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            pollingAssert { scenario.onActivity { assertFalse(it.viewModel.profilesState.value!!.loading) } }
            capture("home-empty")
            runBlocking {
                database.db.serverProfileDao.save(listOf(
                    ServerProfile(name = "Studio Mac", host = "192.0.2.10", remotePlatform = "mac"),
                    ServerProfile(name = "Office Windows", host = "192.0.2.20", remotePlatform = "windows")))
            }
            pollingAssert { scenario.onActivity { assertEquals(2, it.viewModel.profilesState.value!!.profiles.size) } }
            capture("home")
            scenario.onActivity { it.findViewById<EditText>(R.id.home_search).setText("no-such-device") }
            capture("search-empty")
            scenario.onActivity {
                it.findViewById<EditText>(R.id.home_search).setText("")
                it.selectTab(HomeActivity.SETTINGS)
            }
            capture("settings")
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag(HomeActivity.SETTINGS) as SettingsRootFragment)
                    .navigate(PrefsActivity.InputOverview::class.java.name, "touchpad_size")
            }
            capture("settings-input")
            scenario.onActivity {
                (it.supportFragmentManager.findFragmentByTag(HomeActivity.SETTINGS) as SettingsRootFragment).handleBack()
                it.selectTab(HomeActivity.CONNECTIONS)
                startProfileEditor(it, ServerProfile(), false)
            }
            capture("editor")
            scenario.onActivity {
                val editor = it.supportFragmentManager.findFragmentByTag("profileEditor") as ProfileEditorSheet
                editor.requireView().findViewById<android.view.View>(R.id.save_btn).performClick()
            }
            capture("editor-invalid")
            scenario.onActivity {
                val editor = it.supportFragmentManager.findFragmentByTag("profileEditor") as ProfileEditorSheet
                val host = editor.requireView().findViewById<EditText>(R.id.host)
                host.requestFocus()
                androidx.core.view.WindowInsetsControllerCompat(editor.requireDialog().window!!, host)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            var imeFrame = android.graphics.Rect()
            var imeFrameChangedAt = android.os.SystemClock.uptimeMillis()
            pollingAssert {
                scenario.onActivity {
                    val editor = it.supportFragmentManager.findFragmentByTag("profileEditor") as ProfileEditorSheet
                    val decor = editor.requireDialog().window!!.decorView
                    assertTrue("Editor IME must actually be visible for its screenshot",
                            androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
                                    .isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                    val available = android.graphics.Rect().also(decor::getWindowVisibleDisplayFrame)
                    if (available != imeFrame) {
                        imeFrame = available
                        imeFrameChangedAt = android.os.SystemClock.uptimeMillis()
                    }
                    assertTrue("Wait until the IME viewport settles", android.os.SystemClock.uptimeMillis() - imeFrameChangedAt >= 350)
                    listOf(R.id.host, R.id.save_btn, R.id.save_connect_btn).forEach { id ->
                        val field = editor.requireView().findViewById<android.view.View>(id)
                        val visible = android.graphics.Rect()
                        assertTrue("Editor control $id must remain visible above the IME", field.getGlobalVisibleRect(visible))
                        val location = IntArray(2).also(field::getLocationOnScreen)
                        assertEquals("Editor control $id must not be clipped: location=${location.toList()}, visible=$visible, " +
                                "root=${editor.requireView().height}, scroll=${editor.requireView().findViewById<android.widget.ScrollView>(R.id.scroll_view).let { scroll -> "${scroll.height}/${scroll.scrollY}" }}, " +
                                "focus=${editor.requireView().findFocus()?.id}", field.height, visible.height())
                        assertTrue("Editor control $id must not be covered by the IME: $visible outside $available",
                                available.contains(visible))
                    }
                }
            }
            capture("editor-ime")
            // Opt-in cleanup for the synthetic address left by the earlier copy-test version.
            if (InstrumentationRegistry.getArguments().getString("clearSyntheticClipboard") == "true") {
                scenario.onActivity {
                    val clipboard = it.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = clipboard.primaryClip
                    val synthetic = clip?.itemCount == 1 && clip.getItemAt(0).uri == null &&
                            clip.getItemAt(0).intent == null && clip.getItemAt(0).text?.toString() == "123.123.123.123:5900"
                    if (synthetic) {
                        if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                        else clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, ""))
                    }
                    File(it.cacheDir, "visual/clipboard-cleanup.txt").writeText(
                            "synthetic_address_removed=$synthetic; primary_clip_present=${clipboard.hasPrimaryClip()}")
                }
            }
        }
        val port = ServerSocket(0).use { it.localPort }
        ActivityScenario.launch<VncActivity>(createVncIntent(targetContext,
            ServerProfile(name = "Studio Mac", host = "127.0.0.1", port = port))).use { scenario ->
            pollingAssert { scenario.onActivity { assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value) } }
            capture("connection-failed")
        }
    }
}
