/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.model.RemoteAction
import com.gaurav.avnc.model.RemoteInputOverrides
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks the actual RFB messages, independently of the keyboard and gesture recognizers. */
@RunWith(AndroidJUnit4::class)
class PlatformInputTest {
    @JvmField @Rule val prefsRule = CleanPrefsRule()
    private lateinit var session: VncSessionScenario
    private val inputField = VncActivity::class.java.getDeclaredField("inputHandler").apply { isAccessible = true }
    private val VncActivity.sessionInput get() = inputField.get(this) as InputHandler

    @Before fun createSession() {
        session = VncSessionScenario(TestServer(width = 1600, height = 1200))
    }

    @Test fun appleHandshakeSelectsMacAndRetainsNativeOptionMapping() {
        session.server.setProtocolString("RFB 003.889\n")
        session.run {
            session.onActivity {
                assertTrue(it.viewModel.client!!.isAppleServer())
                assertEquals(com.gaurav.avnc.model.RemotePlatform.MAC, it.viewModel.remoteInputConfig.platform)
                assertTrue(it.sessionInput.onRemoteAction(RemoteAction.PREVIOUS_WORD))
            }
            assertKeys(chord(XKeySym.XK_Left, XKeySym.XK_Meta_L))
        }
    }

    @Test fun ordinaryHandshakeKeepsAutomaticPlatformGeneric() {
        session.run {
            session.onActivity {
                assertFalse(it.viewModel.client!!.isAppleServer())
                assertEquals(com.gaurav.avnc.model.RemotePlatform.GENERIC, it.viewModel.remoteInputConfig.platform)
                assertFalse(it.sessionInput.systemGesturesEnabled)
            }
        }
    }

    private fun configure(platform: String, overrides: RemoteInputOverrides = RemoteInputOverrides()) {
        session.onActivity {
            it.viewModel.profile.remotePlatform = platform
            it.viewModel.profile.remoteInputOverrides = overrides.encode()
            it.applyRemoteInputSettings()
        }
    }

    private fun chord(key: Int, vararg modifiers: Int): List<Pair<Int, Boolean>> =
            modifiers.map { it to true } + listOf(key to true, key to false) +
                    modifiers.reversed().map { it to false }

    private fun assertKeys(expected: List<Pair<Int, Boolean>>) {
        pollingAssert { assertEquals(expected, session.server.receivedKeySyms.toList()) }
    }

    @Test fun copyUsesPlatformPrimaryModifierEvenWhenPhysicalCtrlMetaSwapIsEnabled() {
        val expected = mutableListOf<Pair<Int, Boolean>>()
        session.run {
            for ((platform, modifier) in listOf("mac" to XKeySym.XK_Super_L,
                    "windows" to XKeySym.XK_Control_L)) {
                for (swap in listOf(false, true)) {
                    configure(platform, RemoteInputOverrides(swapCtrlMeta = swap))
                    session.onActivity { assertTrue(it.sessionInput.onRemoteAction(RemoteAction.COPY)) }
                    expected += chord('c'.code, modifier)
                    assertKeys(expected)
                }
            }
        }
        assertEquals(expected, session.server.receivedKeySyms.toList())
    }

    private fun assertSystemGestures(platform: String, chords: List<List<Pair<Int, Boolean>>>) {
        session.profile.remotePlatform = platform
        val expected = mutableListOf<Pair<Int, Boolean>>()
        session.run {
            listOf("left", "right", "up", "down").zip(chords).forEach { (direction, events) ->
                session.onActivity {
                    assertTrue(it.sessionInput.systemGesturesEnabled)
                    it.sessionInput.onThreeFingerAction(direction)
                }
                expected += events
                assertKeys(expected)
            }
        }
        // Exact sequences include reverse-order modifier releases, with no stuck keys.
        assertEquals(expected, session.server.receivedKeySyms.toList())
        assertEquals(expected.filter { it.second }.map { it.first }.sorted(),
                expected.filter { !it.second }.map { it.first }.sorted())
    }

    @Test fun macThreeFingerActionsSendControlArrowsAndF11() {
        assertSystemGestures("mac", listOf(
                chord(XKeySym.XK_Right, XKeySym.XK_Control_L),
                chord(XKeySym.XK_Left, XKeySym.XK_Control_L),
                chord(XKeySym.XK_Up, XKeySym.XK_Control_L),
                chord(XKeySym.XK_F11)))
    }

    @Test fun windowsThreeFingerActionsSendDesktopTaskViewAndShowDesktopChords() {
        assertSystemGestures("windows", listOf(
                chord(XKeySym.XK_Right, XKeySym.XK_Super_L, XKeySym.XK_Control_L),
                chord(XKeySym.XK_Left, XKeySym.XK_Super_L, XKeySym.XK_Control_L),
                chord(XKeySym.XK_Tab, XKeySym.XK_Super_L),
                chord('d'.code, XKeySym.XK_Super_L)))
    }

    @Test fun physicalCtrlAndMetaSwapOnlyWhenEnabledIncludingTheirReleaseEvents() {
        val expected = mutableListOf<Pair<Int, Boolean>>()
        session.run {
            for (swap in listOf(false, true)) {
                configure("mac", RemoteInputOverrides(swapCtrlMeta = swap))
                session.onActivity { activity ->
                    for (code in listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_META_LEFT)) {
                        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                            val now = SystemClock.uptimeMillis()
                            val event = KeyEvent(now, now, action, code, 0, 0,
                                    123 /* non-virtual hardware device */, 0, 0, InputDevice.SOURCE_KEYBOARD)
                            assertTrue(activity.sessionInput.onKeyEvent(event))
                        }
                    }
                }
                val ctrl = if (swap) XKeySym.XK_Super_L else XKeySym.XK_Control_L
                val meta = if (swap) XKeySym.XK_Control_L else XKeySym.XK_Super_L
                expected += chord(ctrl) + chord(meta)
                assertKeys(expected)
            }
        }
        assertEquals(expected, session.server.receivedKeySyms.toList())
    }

    @Test fun viewOnlyBlocksSemanticActionsHardwareKeysAndAllVirtualPointerInput() {
        session.profile.remotePlatform = "mac"
        session.profile.viewMode = ServerProfile.VIEW_MODE_NO_INPUT
        session.run {
            session.onActivity {
                val input = it.sessionInput
                assertFalse(input.systemGesturesEnabled)
                assertFalse(input.onRemoteAction(RemoteAction.COPY))
                listOf("left", "right", "up", "down").forEach(input::onThreeFingerAction)
                assertFalse(input.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT)))
                assertFalse(input.onVkKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C)))
                input.onVirtualPointerStart()
                input.onVirtualPointerMove(40f, 20f)
                input.onVirtualPointerButton(PointerButton.Left, true)
                input.onVirtualPointerButton(PointerButton.Left, false)
                input.onVirtualPointerClick(PointerButton.Right)
                input.onVirtualPointerScroll(16f, 16f)
                input.onVirtualPointerScrollEnd(1000f, 1000f)
                input.onVirtualPointerStop()
            }
        }
        assertTrue(session.server.receivedKeySyms.isEmpty())
        assertTrue(session.server.receivedPointerEvents.isEmpty())
    }

    @Test fun virtualPointerUsesThreeRemotePixelsPerDpAndThePerServerMultiplier() {
        session.profile.remotePlatform = "windows"
        session.profile.remoteInputOverrides = RemoteInputOverrides(acceleration = false).encode()
        session.run {
            session.onActivity {
                it.sessionInput.onVirtualPointerStart()
                it.sessionInput.onVirtualPointerMove(40f, 20f)
                it.sessionInput.onVirtualPointerStop()
            }
            pollingAssert {
                assertEquals(listOf(TestServer.PointerEvent(0, 120, 60)),
                        session.server.receivedPointerEvents.toList())
            }
            configure("windows", RemoteInputOverrides(pointerSpeed = 2f, acceleration = false))
            session.onActivity {
                it.sessionInput.onVirtualPointerStart()
                it.sessionInput.onVirtualPointerMove(40f, 20f)
                it.sessionInput.onVirtualPointerStop()
            }
            pollingAssert {
                assertEquals(listOf(TestServer.PointerEvent(0, 120, 60),
                        TestServer.PointerEvent(0, 360, 180)), session.server.receivedPointerEvents.toList())
            }
        }
    }

    private fun scroll(dx: Float, dy: Float) = session.onActivity {
        it.sessionInput.onVirtualPointerStart()
        it.sessionInput.onVirtualPointerScroll(dx, dy)
        it.sessionInput.onVirtualPointerScrollEnd(0f, 0f)
    }

    private fun assertMasks(expected: List<Int>) {
        pollingAssert {
            assertEquals(expected, session.server.receivedPointerEvents.map { it.mask })
            assertTrue(session.server.receivedPointerEvents.all { it.x == 0 && it.y == 0 })
        }
    }

    private fun wheelMasks(ticks: List<Int>) = ticks.flatMap { listOf(it, 0) }

    private fun mouseWheel(horizontal: Float, vertical: Float) = session.onActivity {
        val point = it.viewModel.frameState.toVP(PointF(800f, 600f))
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            x = point.x; y = point.y
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                properties, coordinates, 0, 0, 1f, 1f, 123, 0, InputDevice.SOURCE_MOUSE, 0)
        try {
            assertTrue(it.sessionInput.onGenericMotionEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test fun physicalWheelPreservesDefaultDirectionsAndUsesConfiguredSpeedsAndInversions() {
        session.run {
            configure("windows")
            mouseWheel(1f, 1f)
            pollingAssert {
                assertEquals(listOf(64, 0, 8, 0), session.server.receivedPointerEvents.map { it.mask })
            }
            // Physical wheel axes remain independent even when touch scrolling uses axis lock.
            val settings = RemoteInputOverrides(horizontalSpeed = 2f, scrollSpeed = .5f,
                    axisLock = true, inertia = "off")
            configure("windows", settings)
            mouseWheel(1f, 2f)
            pollingAssert {
                assertEquals(listOf(64, 0, 8, 0, 64, 0, 64, 0, 8, 0),
                        session.server.receivedPointerEvents.map { it.mask })
            }
            configure("windows", settings.copy(invertHorizontal = true, invertVertical = true))
            mouseWheel(1f, 2f)
            pollingAssert {
                assertEquals(listOf(64, 0, 8, 0, 64, 0, 64, 0, 8, 0, 32, 0, 32, 0, 16, 0),
                        session.server.receivedPointerEvents.map { it.mask })
            }
        }
        assertEquals(16, session.server.receivedPointerEvents.size)
        assertTrue(session.server.receivedPointerEvents.all { it.x in 0..1599 && it.y in 0..1199 })
    }

    @Test fun scrollAppliesIndependentHorizontalAndVerticalMultipliersAndBothInversions() {
        session.run {
            val settings = RemoteInputOverrides(scrollSpeed = .5f, horizontalSpeed = 2f,
                    axisLock = false, inertia = "off")
            configure("mac", settings)
            scroll(8f, 16f)
            val forward = List(4) { listOf(32, 8) }.flatten() + List(4) { 32 }
            assertMasks(wheelMasks(forward))
            configure("mac", settings.copy(invertVertical = true, invertHorizontal = true))
            scroll(8f, 16f)
            val reverse = List(4) { listOf(64, 16) }.flatten() + List(4) { 64 }
            assertMasks(wheelMasks(forward + reverse))
        }
        assertEquals(48, session.server.receivedPointerEvents.size)
    }

    @Test fun scrollAxisLockSuppressesMinorAxisAndStaysLockedForTheWholeGesture() {
        session.run {
            configure("windows", RemoteInputOverrides(axisLock = true, inertia = "off"))
            session.onActivity {
                it.sessionInput.onVirtualPointerStart()
                it.sessionInput.onVirtualPointerScroll(8f, 24f)
            }
            val locked = List(12) { 8 }
            assertMasks(wheelMasks(locked))
            val frame = CountDownLatch(1)
            session.onActivity {
                it.sessionInput.onVirtualPointerScroll(24f, 0f)
                it.sessionInput.onVirtualPointerScrollEnd(0f, 0f)
                it.binding.inputView.postOnAnimation { frame.countDown() }
            }
            assertTrue("Scroll animation did not run", frame.await(5, TimeUnit.SECONDS))
            // A subsequent key chord is a protocol barrier for the preceding scroll frame.
            session.onActivity { assertTrue(it.sessionInput.onRemoteAction(RemoteAction.COPY)) }
            assertKeys(chord('c'.code, XKeySym.XK_Control_L))
            assertMasks(wheelMasks(locked))
            // Reloading cancels the preceding stream; a new unlocked stream keeps both axes.
            configure("windows", RemoteInputOverrides(axisLock = false, inertia = "off"))
            scroll(8f, 24f)
            val unlocked = List(4) { listOf(8, 32) }.flatten() + List(8) { 8 }
            assertMasks(wheelMasks(locked + unlocked))
        }
        assertEquals(56, session.server.receivedPointerEvents.size)
    }
}
