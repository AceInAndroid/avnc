/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.graphics.RectF
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.model.DisplayViewMode
import com.gaurav.avnc.model.RemoteDisplay
import com.gaurav.avnc.model.RemoteDisplayLayout
import com.gaurav.avnc.model.RemoteInputOverrides
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacDisplaySessionTest {
    @JvmField @Rule val prefsRule = CleanPrefsRule()
    private lateinit var session: VncSessionScenario
    private val layout = RemoteDisplayLayout(1600, 800, listOf(
            RemoteDisplay("left", "Mac", 0, 0, 800, 800),
            RemoteDisplay("right", "External", 800, 0, 800, 800)), "manual")
    private val inputField = VncActivity::class.java.getDeclaredField("inputHandler").apply { isAccessible = true }
    private val VncActivity.sessionInput get() = inputField.get(this) as InputHandler

    @Before fun createSession() {
        session = VncSessionScenario(TestServer(width = 1600, height = 800))
        session.profile.remotePlatform = "mac"
        session.profile.remoteInputOverrides = RemoteInputOverrides(acceleration = false).encode()
    }

    private fun calibrate() = session.onActivity { it.saveManualDisplayLayout(layout) }

    @Test fun selectingDisplayMovesToItsCenterWithoutClickAndClampsRelativeMotion() {
        session.run {
            calibrate()
            session.onActivity {
                assertEquals("left", it.viewModel.selectedDisplay?.id)
                it.selectDisplay("right")
                assertEquals(RectF(800f, 0f, 1600f, 800f), it.viewModel.frameState.contentRect)
            }
            pollingAssert { assertEquals(TestServer.PointerEvent(0, 1200, 400), session.server.receivedPointerEvents.last()) }
            session.onActivity {
                it.sessionInput.onVirtualPointerStart()
                it.sessionInput.onVirtualPointerMove(-10000f, 10000f)
                it.sessionInput.onVirtualPointerStop()
            }
            pollingAssert { assertEquals(TestServer.PointerEvent(0, 800, 799), session.server.receivedPointerEvents.last()) }
            assertTrue(session.server.receivedPointerEvents.all { it.mask == 0 && it.x in 800..1599 && it.y in 0..799 })
        }
    }

    @Test fun displaySwitchReleasesHeldMouseBeforeMovingAndRestoresLastPointerPosition() {
        session.run {
            calibrate()
            session.onActivity {
                it.sessionInput.onVirtualPointerStart()
                it.sessionInput.onVirtualPointerMove(100f, 50f)
                it.sessionInput.onVirtualPointerButton(PointerButton.Left, true)
            }
            pollingAssert { assertEquals(TestServer.PointerEvent(1, 300, 150), session.server.receivedPointerEvents.last()) }
            session.onActivity { it.selectDisplay("right") }
            pollingAssert { assertEquals(TestServer.PointerEvent(0, 1200, 400), session.server.receivedPointerEvents.last()) }
            val events = session.server.receivedPointerEvents.toList()
            val down = events.indexOf(TestServer.PointerEvent(1, 300, 150))
            val release = events.indices.firstOrNull { it > down && events[it] == TestServer.PointerEvent(0, 300, 150) } ?: -1
            val switch = events.indexOf(TestServer.PointerEvent(0, 1200, 400))
            assertTrue(down >= 0 && release > down && switch > release)
            session.onActivity { it.selectDisplay("left") }
            pollingAssert { assertEquals(TestServer.PointerEvent(0, 300, 150), session.server.receivedPointerEvents.last()) }
        }
    }

    @Test fun readOnlyAllowsDisplayBrowsingWithoutAnyRemoteInput() {
        session.profile.viewMode = ServerProfile.VIEW_MODE_NO_INPUT
        session.run {
            calibrate()
            session.onActivity {
                it.selectDisplay("right")
                assertEquals("right", it.viewModel.selectedDisplay?.id)
                it.performMacDock()
                it.setDisplayViewMode(DisplayViewMode.ALL)
                assertEquals(RectF(0f, 0f, 1600f, 800f), it.viewModel.frameState.contentRect)
            }
        }
        assertTrue(session.server.receivedPointerEvents.isEmpty())
        assertTrue(session.server.receivedKeySyms.isEmpty())
    }

    @Test fun savedProtocolDisplaySurvivesInitialEmptyFramebufferMetadata() {
        session.profile.selectedDisplayId = "42"
        session.run {
            session.onActivity {
                assertTrue(it.viewModel.displayLayout.value!!.displays.isEmpty())
                assertEquals("42", it.viewModel.profile.selectedDisplayId)
            }
            session.server.sendDisplayLayout(1600, 800,
                    intArrayOf(11, 0, 0, 800, 800, 42, 800, 0, 800, 800))
            pollingAssert {
                session.onActivity {
                    assertEquals("42", it.viewModel.selectedDisplay?.id)
                    assertEquals(RectF(800f, 0f, 1600f, 800f), it.viewModel.frameState.contentRect)
                }
            }
        }
    }

    @Test fun resizedFramebufferInvalidatesManualCalibrationAndShowsCompleteDesktop() {
        session.run {
            calibrate()
            session.onActivity { it.selectDisplay("right") }
            // A larger framebuffer allows this tiny server's original Raw rectangles to remain valid.
            session.server.sendDisplayLayout(1800, 800, intArrayOf())
            pollingAssert {
                session.onActivity {
                    assertEquals("manual_invalid", it.viewModel.displayLayout.value!!.source)
                    assertTrue(it.viewModel.displayLayout.value!!.displays.isEmpty())
                    assertNull(it.viewModel.selectedDisplay)
                    assertEquals(RectF(0f, 0f, 1800f, 800f), it.viewModel.frameState.contentRect)
                    assertEquals(1600, RemoteDisplayLayout.decode(it.viewModel.profile.manualDisplayLayout).framebufferWidth)
                    assertEquals("manual_invalid", RemoteDisplayLayout.decode(it.viewModel.profile.manualDisplayLayout).source)
                }
            }
            // Returning to the old dimensions must not silently reactivate stale calibration.
            session.server.sendDisplayLayout(1600, 800, intArrayOf())
            pollingAssert {
                session.onActivity {
                    assertEquals(1600f, it.viewModel.frameState.fbWidth)
                    assertEquals("manual_invalid", it.viewModel.displayLayout.value!!.source)
                    assertNull(it.viewModel.selectedDisplay)
                }
            }
        }
    }

    @Test fun selectingDisplayReleasesHeldKeyboardModifiersAsWellAsMouseButton() {
        session.run {
            calibrate()
            session.onActivity {
                assertTrue(it.sessionInput.onVkKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT)))
                it.sessionInput.onVirtualPointerStart()
                it.sessionInput.onVirtualPointerButton(PointerButton.Left, true)
            }
            pollingAssert {
                assertEquals(listOf(XKeySym.XK_Control_L to true), session.server.receivedKeySyms.toList())
                assertEquals(1, session.server.receivedPointerEvents.last().mask)
            }
            session.onActivity { it.selectDisplay("right") }
            pollingAssert {
                assertEquals(listOf(XKeySym.XK_Control_L to true, XKeySym.XK_Control_L to false), session.server.receivedKeySyms.toList())
                assertEquals(TestServer.PointerEvent(0, 1200, 400), session.server.receivedPointerEvents.last())
            }
            assertFalse(session.server.receivedPointerEvents.last().mask and 1 != 0)
        }
    }

    @Test fun eachDisplayKeepsItsOwnSessionZoomAndAllViewRetainsRemoteArrangement() {
        session.run {
            calibrate()
            session.onActivity {
                it.viewModel.setZoom(2f, 2f)
                it.selectDisplay("right")
                assertEquals(1f, it.viewModel.frameState.zoomScale, 0f)
                it.viewModel.setZoom(3f, 3f)
                it.selectDisplay("left")
                assertEquals(2f, it.viewModel.frameState.zoomScale, 0f)
                it.selectDisplay("right")
                assertEquals(3f, it.viewModel.frameState.zoomScale, 0f)
                it.setDisplayViewMode(DisplayViewMode.ALL)
                assertNull(it.viewModel.selectedDisplay)
                assertEquals(RectF(0f, 0f, 1600f, 800f), it.viewModel.frameState.contentRect)
                assertEquals(listOf(0, 800), it.viewModel.displayLayout.value!!.displays.map { screen -> screen.x })
            }
        }
    }
}
