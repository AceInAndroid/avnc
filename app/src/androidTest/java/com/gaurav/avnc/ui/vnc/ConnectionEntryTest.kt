/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.widget.TextView
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.viewmodel.VncViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ConnectionEntryTest {
    @JvmField @Rule val prefs = CleanPrefsRule()
    @JvmField @Rule val database = com.gaurav.avnc.EmptyDatabaseRule()

    @Test fun failureFromExternalLaunchReturnsHomeWithoutStartupReconnect() {
        targetPrefs.edit { putBoolean("discovery_autorun", false) }
        val port = ServerSocket(0).use { it.localPort }
        kotlinx.coroutines.runBlocking {
            database.db.serverProfileDao.save(ServerProfile(host = "127.0.0.1", port = port).apply {
                fConnectOnAppStart = true
            })
        }
        ActivityScenario.launch<VncActivity>(createVncIntent(targetContext,
                ServerProfile(host = "127.0.0.1", port = port))).use { scenario ->
            pollingAssert { scenario.onActivity { assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value) } }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.connection_entry_home).performClick() }
            try {
                pollingAssert {
                    com.gaurav.avnc.instrumentation.runOnMainSync {
                        val home = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                                .filterIsInstance<com.gaurav.avnc.ui.home.HomeActivity>().single()
                        assertTrue(home.findViewById<android.view.View>(R.id.home_tab_connections).isSelected)
                        // Wait for the asynchronous startup lookup as well; it must not emit a connect event.
                        assertFalse(home.isFinishing)
                    }
                }
                com.gaurav.avnc.instrumentation.waitForIdleSync()
                pollingAssert {
                    com.gaurav.avnc.instrumentation.runOnMainSync {
                        val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        assertTrue(resumed.any { it is com.gaurav.avnc.ui.home.HomeActivity })
                        assertFalse(resumed.any { it is VncActivity })
                    }
                }
            } finally {
                com.gaurav.avnc.instrumentation.runOnMainSync {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<com.gaurav.avnc.ui.home.HomeActivity>().forEach { it.finish() }
                }
            }
        }
    }

    @Test fun refusedConnectionShowsTargetActionsAndExpandableDetails() {
        val port = ServerSocket(0).use { it.localPort }
        val profile = ServerProfile(name = "Test Mac", host = "127.0.0.1", port = port)
        ActivityScenario.launch<VncActivity>(createVncIntent(targetContext, profile)).use { scenario ->
            pollingAssert {
                scenario.onActivity {
                    assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value)
                    assertTrue(it.findViewById<android.view.View>(R.id.reconnect_btn).isShown)
                    assertTrue(it.findViewById<android.view.View>(R.id.connection_entry_edit).isShown)
                    assertTrue(it.findViewById<android.view.View>(R.id.connection_entry_home).isShown)
                    assertFalse(it.findViewById<android.view.View>(R.id.disconnect_reason).isShown)
                    assertEquals(targetContext.getString(R.string.entry_failed), it.findViewById<TextView>(R.id.client_state).text.toString())
                }
            }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.connection_entry_details).performClick() }
            scenario.onActivity { assertTrue(it.findViewById<android.view.View>(R.id.disconnect_reason).isShown) }
        }
    }

    @Test fun cancellingSilentHandshakeFinishesViewerAndClosesTransport() {
        val listener = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var socket: java.net.Socket? = null
        val peer = thread(isDaemon = true) {
            try {
                listener.accept().use { client ->
                    socket = client
                    accepted.countDown()
                    client.soTimeout = 5000
                    if (client.getInputStream().read() == -1) closed.countDown()
                }
            } catch (_: Exception) { }
        }
        try {
            val scenario = ActivityScenario.launch<VncActivity>(createVncIntent(targetContext,
                    ServerProfile(host = "127.0.0.1", port = listener.localPort)))
            try {
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertTrue(it.findViewById<android.view.View>(R.id.connection_entry_cancel).isShown)
                    it.findViewById<android.view.View>(R.id.connection_entry_cancel).performClick()
                }
                assertTrue("Cancel must close the stalled socket", closed.await(3, TimeUnit.SECONDS))
            } finally { scenario.close() }
        } finally { socket?.close(); listener.close(); peer.join(1000) }
    }

    @Test fun userCanStopAutoReconnectWithoutStartingAnotherViewer() {
        targetPrefs.edit { putBoolean("auto_reconnect", true) }
        val port = ServerSocket(0).use { it.localPort }
        ActivityScenario.launch<VncActivity>(createVncIntent(targetContext,
                ServerProfile(host = "127.0.0.1", port = port))).use { scenario ->
            pollingAssert {
                scenario.onActivity {
                    assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value)
                    assertTrue(it.findViewById<android.view.View>(R.id.connection_entry_countdown).isShown)
                }
            }
            scenario.onActivity {
                val root = it.binding.statusContainer
                fun find(view: android.view.View): android.view.View? {
                    if (view is TextView && view.text == targetContext.getString(R.string.entry_stop_retry)) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                assertNotNull(find(root))
                find(root)!!.performClick()
            }
            Thread.sleep(5500) // Exceed the first scheduled retry, proving it was cancelled.
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value)
                assertEquals(targetContext.getString(R.string.entry_retry_stopped), it.findViewById<TextView>(R.id.connection_entry_countdown).text.toString())
            }
        }
    }

    @Test fun recreationPreservesActualReconnectDeadline() {
        targetPrefs.edit { putBoolean("auto_reconnect", true) }
        val port = ServerSocket(0).use { it.localPort }
        ActivityScenario.launch<VncActivity>(createVncIntent(targetContext,
                ServerProfile(host = "127.0.0.1", port = port))).use { scenario ->
            pollingAssert { scenario.onActivity {
                assertEquals(VncViewModel.State.Disconnected, it.viewModel.state.value)
                assertTrue(it.findViewById<android.view.View>(R.id.auto_reconnect_progress).isShown)
            } }
            Thread.sleep(2100)
            scenario.recreate()
            scenario.onActivity {
                val value = it.findViewById<TextView>(R.id.connection_entry_countdown).text.toString()
                val seconds = Regex("[0-9]+").find(value)?.value?.toInt()
                assertNotNull("A recreated viewer must retain the running countdown", seconds)
                assertTrue("Recreation must not restart the five-second timer: $value", seconds!! in 0..3)
                // Back/cancel terminates the existing session, including its scheduled retry.
                it.disconnectSession()
            }
        }
    }

    @Test fun realVncPasswordPromptUsesWaitingStateAndConnectsAfterSubmission() {
        val session = VncSessionScenario()
        session.server.setupVncAuth("hello")
        session.startServer().startActivity()
        try {
            pollingAssert {
                session.onActivity {
                    assertTrue(it.viewModel.awaitingUser.value == true)
                    assertNotNull(it.supportFragmentManager.findFragmentByTag("LoginDialog"))
                }
            }
            session.onActivity {
                val dialog = (it.supportFragmentManager.findFragmentByTag("LoginDialog") as LoginFragment).dialog as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                assertTrue(it.viewModel.awaitingUser.value == true)
                dialog.findViewById<android.widget.EditText>(R.id.password)!!.setText("hello")
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            }
            session.assertConnected()
            session.onActivity { assertFalse(it.viewModel.awaitingUser.value == true) }
        } finally { session.stop() }
    }
}
