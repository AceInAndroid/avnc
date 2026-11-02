/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.core.content.edit
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.viewmodel.VncViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionFailureUiTest {
    @JvmField @Rule val prefs = CleanPrefsRule()

    @Test fun unexpectedConnectionExceptionHasAVisibleReason() {
        val intent = createVncIntent(targetContext, ServerProfile(channelType = 12345))
        ActivityScenario.launch<VncActivity>(intent).use { scenario ->
            pollingAssert {
                scenario.onActivity {
                    assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value)
                    assertTrue(it.viewModel.disconnectReason.value.orEmpty().contains("Unknown Channel"))
                }
            }
        }
    }

    @Test fun automaticReconnectRecoversAfterServerRestartAndCanSendInput() {
        targetPrefs.edit { putBoolean("auto_reconnect", true); putBoolean("run_info_has_shown_viewer_help", true) }
        val first = TestServer()
        var replacement: TestServer? = null
        var restarted: VncActivity? = null
        first.start()
        val intent = createVncIntent(targetContext, ServerProfile(host = first.host, port = first.port))
        try {
            ActivityScenario.launch<VncActivity>(intent).use { scenario ->
                var original: VncActivity? = null
                pollingAssert {
                    scenario.onActivity {
                        original = it
                        assertEquals(VncViewModel.State.Connected, it.viewModel.state.value)
                    }
                }
                first.stop()
                replacement = TestServer(listenPort = first.port).also { it.start() }
                pollingAssert {
                    instrumentation.runOnMainSync {
                        restarted = ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<VncActivity>()
                                .firstOrNull { it !== original }
                        assertNotNull("Automatic reconnect did not create a fresh viewer", restarted)
                        assertEquals(VncViewModel.State.Connected, restarted!!.viewModel.state.value)
                    }
                }
                instrumentation.runOnMainSync {
                    assertTrue(restarted!!.viewModel.messenger!!.sendKey('z'.code, 0, true))
                    assertTrue(restarted!!.viewModel.messenger!!.sendKey('z'.code, 0, false))
                }
                pollingAssert { assertEquals(listOf('z'.code to true, 'z'.code to false), replacement!!.receivedKeySyms.toList()) }
            }
        } finally {
            instrumentation.runOnMainSync { restarted?.disconnectSession() }
            replacement?.stop()
            first.stop()
        }
    }
}
