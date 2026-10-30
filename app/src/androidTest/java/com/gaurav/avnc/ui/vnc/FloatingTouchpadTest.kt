/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.os.SystemClock
import android.graphics.PointF
import android.graphics.Rect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.R
import com.gaurav.avnc.VncSessionTest
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.core.content.edit
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingTouchpadTest : VncSessionTest() {
    @JvmField @Rule val prefsRule = CleanPrefsRule()
    @Before fun enableTouchpadForInputTests() { targetPrefs.edit { putBoolean("show_touchpad", true); putBoolean("control_help_shown", true) } }

    private fun touch(view: View, action: Int, x: Float, y: Float, down: Long = SystemClock.uptimeMillis()) {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
            assertTrue(view.dispatchTouchEvent(it))
            it.recycle()
        }
    }

    /** One real gesture stream delivered only to the panel, including stable pointer IDs. */
    private class PanelTouchStream(private val panel: ViewGroup) {
        private val downTime = SystemClock.uptimeMillis()

        fun point(childId: Int, fractionX: Float, fractionY: Float): PointF {
            val child = panel.findViewById<View>(childId)
            val bounds = Rect(0, 0, child.width, child.height)
            panel.offsetDescendantRectToMyCoords(child, bounds)
            return PointF(bounds.left + bounds.width() * fractionX,
                          bounds.top + bounds.height() * fractionY)
        }

        fun send(action: Int, vararg points: PointF) {
            val properties = Array(points.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index + 4
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(points.size) { index ->
                MotionEvent.PointerCoords().apply {
                    x = points[index].x; y = points[index].y
                    pressure = 1f; size = 1f
                }
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    points.size, properties, coords, 0, 0, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                // ViewGroup must split pointer IDs between button and surface.
                assertTrue(panel.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    @Test fun buttonsAndSurfaceTapProduceOnlyExpectedClicks() {
        vncSession.run {
            vncSession.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.floating_touchpad).isVisible)
                activity.findViewById<View>(R.id.touchpad_left).performClick()
                activity.findViewById<View>(R.id.touchpad_right).performClick()
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                touch(surface, MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(surface, MotionEvent.ACTION_UP, 20f, 20f)
            }
            pollingAssert { assertEquals(6, vncSession.server.receivedPointerEvents.size) }
        }
        assertEquals(listOf(1, 0, 4, 0, 1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
        assertTrue(vncSession.server.receivedPointerEvents.all { it.x in 0..9 && it.y in 0..9 })
    }

    @Test fun smallSurfaceMotionBelowSystemTouchSlopStillMovesWithoutClicking() {
        vncSession = com.gaurav.avnc.VncSessionScenario(com.gaurav.avnc.TestServer(width = 1600, height = 1200))
        vncSession.run {
            vncSession.onActivity { activity ->
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                val density = activity.resources.displayMetrics.density
                val down = SystemClock.uptimeMillis()
                val travel = minOf(2f * density, ViewConfiguration.get(activity).scaledTouchSlop * .75f)
                assertTrue(travel > density)
                touch(surface, MotionEvent.ACTION_DOWN, 20f, 20f, down)
                touch(surface, MotionEvent.ACTION_MOVE, 20f + travel, 20f, down)
                touch(surface, MotionEvent.ACTION_UP, 20f + travel, 20f, down)
            }
            pollingAssert { assertTrue(vncSession.server.receivedPointerEvents.last().x in 4..6) }
        }
        assertTrue(vncSession.server.receivedPointerEvents.all { it.mask == 0 })
    }

    @Test fun surfaceMovementUsesThreeRemotePixelsPerDpWithoutClicking() {
        vncSession = com.gaurav.avnc.VncSessionScenario(com.gaurav.avnc.TestServer(width = 1600, height = 1200))
        vncSession.run {
            vncSession.onActivity { activity ->
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                val density = activity.resources.displayMetrics.density
                val down = SystemClock.uptimeMillis()
                touch(surface, MotionEvent.ACTION_DOWN, 10f, 10f, down)
                touch(surface, MotionEvent.ACTION_MOVE, 10f + 40f * density, 10f + 20f * density, down)
                touch(surface, MotionEvent.ACTION_UP, 10f + 40f * density, 10f + 20f * density, down)
            }
            pollingAssert {
                val last = vncSession.server.receivedPointerEvents.last()
                assertEquals(120, last.x)
                assertEquals(60, last.y)
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.all { it.mask == 0 })
    }

    @Test fun pointerMoveWhileLeftHeldPreservesDragUntilReleased() {
        vncSession.run {
            vncSession.onActivity { activity ->
                val panel = activity.findViewById<View>(R.id.floating_touchpad)
                val left = activity.findViewById<View>(R.id.touchpad_left)
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                touch(panel, MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(left, MotionEvent.ACTION_DOWN, 10f, 10f)
                touch(surface, MotionEvent.ACTION_DOWN, 10f, 10f)
                touch(surface, MotionEvent.ACTION_MOVE, 80f, 60f)
                touch(left, MotionEvent.ACTION_UP, 10f, 10f)
                touch(panel, MotionEvent.ACTION_CANCEL, 20f, 20f)
            }
        }
        val events = vncSession.server.receivedPointerEvents
        assertTrue(events.any { it.mask == 1 && it.x > 0 && it.y > 0 })
        assertEquals(0, events.last().mask)
        assertTrue(events.dropLast(1).all { it.mask == 1 })
    }

    @Test fun panelRoutesSecondFingerToSurfaceWhileFirstFingerHoldsLeftButton() {
        vncSession.run {
            // Wait before injecting once: newly added overlay children may not be laid out yet.
            pollingAssert {
                vncSession.onActivity { activity ->
                    val panel = activity.findViewById<ViewGroup>(R.id.floating_touchpad)
                    assertTrue(panel.isShown && panel.isLaidOut)
                    assertTrue(activity.findViewById<View>(R.id.touchpad_left).width > 0)
                    assertTrue(activity.findViewById<View>(R.id.touchpad_surface).height > 0)
                }
            }
            vncSession.onActivity { activity ->
                val panel = activity.findViewById<ViewGroup>(R.id.floating_touchpad)
                val stream = PanelTouchStream(panel)
                val left = stream.point(R.id.touchpad_left, .5f, .5f)
                val start = stream.point(R.id.touchpad_surface, .25f, .25f)
                val end = stream.point(R.id.touchpad_surface, .75f, .75f)
                stream.send(MotionEvent.ACTION_DOWN, left)
                stream.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, start)
                stream.send(MotionEvent.ACTION_MOVE, left, end)
                stream.send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), left, end)
                stream.send(MotionEvent.ACTION_UP, left)
            }
        }
        val events = vncSession.server.receivedPointerEvents
        assertTrue("Expected button-down, dragged pointer, and button-up", events.size >= 3)
        assertEquals(1, events.first().mask)
        assertTrue("Left button must stay held until the first finger lifts", events.dropLast(1).all { it.mask == 1 })
        assertTrue("Second finger must move the cursor, not scroll or click", events.dropLast(1).any {
            it.x > events.first().x && it.y > events.first().y
        })
        assertEquals(0, events.last().mask)
        assertEquals(events[events.lastIndex - 1].x, events.last().x)
        assertEquals(events[events.lastIndex - 1].y, events.last().y)
    }

    @Test fun surfaceTapWhileLeftIsHeldDoesNotReleaseDragBeforeNextMovement() {
        vncSession.run {
            pollingAssert {
                vncSession.onActivity { activity ->
                    val panel = activity.findViewById<ViewGroup>(R.id.floating_touchpad)
                    assertTrue(panel.isShown && panel.isLaidOut)
                    assertTrue(activity.findViewById<View>(R.id.touchpad_surface).height > 0)
                }
            }
            vncSession.onActivity { activity ->
                val stream = PanelTouchStream(activity.findViewById(R.id.floating_touchpad))
                val left = stream.point(R.id.touchpad_left, .5f, .5f)
                val start = stream.point(R.id.touchpad_surface, .25f, .25f)
                val end = stream.point(R.id.touchpad_surface, .75f, .75f)
                val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                stream.send(MotionEvent.ACTION_DOWN, left)
                // Tap and lift the second finger while the left-button finger stays down.
                stream.send(secondDown, left, start)
                stream.send(secondUp, left, start)
                // A subsequent touch must still drag with the left button held.
                stream.send(secondDown, left, start)
                stream.send(MotionEvent.ACTION_MOVE, left, end)
                stream.send(secondUp, left, end)
                stream.send(MotionEvent.ACTION_UP, left)
            }
        }
        val events = vncSession.server.receivedPointerEvents
        assertTrue("Expected button-down, subsequent drag, and button-up", events.size >= 3)
        assertTrue("Surface tap must not release or replace the held button", events.dropLast(1).all { it.mask == 1 })
        assertTrue("Movement after the surface tap must still drag", events.dropLast(1).any {
            it.x > events.first().x && it.y > events.first().y
        })
        assertEquals("Only the final first-finger lift may release the button", 1, events.count { it.mask == 0 })
        assertEquals(0, events.last().mask)
    }

    @Test fun sideScrollSendsWheelEventsAndNoClicks() {
        vncSession.run {
            val down = SystemClock.uptimeMillis()
            vncSession.onActivity { activity ->
                val scroll = activity.findViewById<View>(R.id.touchpad_scroll)
                touch(scroll, MotionEvent.ACTION_DOWN, 10f, 10f, down)
                touch(scroll, MotionEvent.ACTION_MOVE, 10f, 10f + 80f * activity.resources.displayMetrics.density, down)
            }
            // Wheels are drained on display frames; let the first direction reach the
            // server before injecting the opposite direction or closing the session.
            pollingAssert { assertTrue(vncSession.server.receivedPointerEvents.any { it.mask == 8 }) }
            vncSession.onActivity { activity ->
                touch(activity.findViewById(R.id.touchpad_scroll), MotionEvent.ACTION_MOVE, 10f, 10f, down)
            }
            pollingAssert { assertTrue(vncSession.server.receivedPointerEvents.any { it.mask == 16 }) }
            vncSession.onActivity { activity ->
                touch(activity.findViewById(R.id.touchpad_scroll), MotionEvent.ACTION_UP, 10f, 10f, down)
            }
        }
        val masks = vncSession.server.receivedPointerEvents.map { it.mask }
        assertTrue(masks.contains(8))
        assertTrue(masks.contains(16))
        assertTrue(masks.all { it == 0 || it == 8 || it == 16 })
    }

    @Test fun repositioningAndCollapseDoNotSendRemoteEvents() {
        vncSession.run {
            vncSession.onActivity { activity ->
                val panel = activity.findViewById<View>(R.id.floating_touchpad)
                val oldX = panel.x
                val handle = activity.findViewById<View>(R.id.touchpad_handle)
                touch(handle, MotionEvent.ACTION_DOWN, 80f, 20f)
                touch(handle, MotionEvent.ACTION_MOVE, -80f, -80f)
                touch(handle, MotionEvent.ACTION_UP, -80f, -80f)
                assertTrue(panel.x < oldX)
                assertTrue(targetPrefs.getFloat("touchpad_x", 1f) < 1f)
                activity.findViewById<View>(R.id.touchpad_collapse).performClick()
                assertFalse(activity.floatingTouchpad.isExpanded)
                activity.floatingTouchpad.toggle()
                assertTrue(activity.findViewById<View>(R.id.touchpad_surface).isVisible)
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

    @Test fun keyboardReservesSpaceAndTouchpadStaysInsideViewport() {
        vncSession.run {
            var oldHeight = 0
            vncSession.onActivity { oldHeight = it.binding.frameView.height; it.showKeyboard() }
            pollingAssert {
                vncSession.onActivity { activity ->
                    assertTrue(activity.binding.frameView.height < oldHeight)
                    val panel = activity.findViewById<View>(R.id.floating_touchpad)
                    assertTrue(panel.y >= 0)
                    assertTrue(panel.y + panel.height <= (if (activity.floatingTouchpad.isDocked) activity.binding.touchpadDock else activity.binding.touchpadHost).height)
                }
            }
            vncSession.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                assertFalse(it.computerKeyboard.isVisible)
                assertFalse(it.isFinishing)
            }
        }
    }

    @Test fun focusLossReleasesMouseAndLockedModifier() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_CTRL_LEFT}").performLongClick()
                activity.findViewById<View>(R.id.touchpad_left).let { touch(it, MotionEvent.ACTION_DOWN, 10f, 10f) }
                activity.onWindowFocusChanged(false)
            }
            pollingAssert {
                assertEquals(0, vncSession.server.receivedPointerEvents.last().mask)
                assertTrue(vncSession.server.receivedKeySyms.contains(XKeySym.XK_Control_L to false))
            }
        }
    }

    @Test fun hidingPanelReleasesLockedKeyAndMouseButton() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_CTRL_LEFT}").performLongClick()
                touch(activity.findViewById(R.id.touchpad_left), MotionEvent.ACTION_DOWN, 10f, 10f)
                activity.floatingTouchpad.toggle()
                assertFalse(activity.findViewById<View>(R.id.floating_touchpad).isVisible)
            }
        }
        assertEquals(listOf(1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
        assertEquals(listOf(XKeySym.XK_Control_L to true, XKeySym.XK_Control_L to false),
                vncSession.server.receivedKeySyms)
    }

    @Test fun switchingToViewOnlyReleasesHeldInputBeforeBlockingNewEvents() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_CTRL_LEFT}").performLongClick()
                touch(activity.findViewById(R.id.touchpad_left), MotionEvent.ACTION_DOWN, 10f, 10f)
                activity.viewModel.setViewMode(ServerProfile.VIEW_MODE_NO_INPUT)
                activity.findViewById<View>(R.id.touchpad_right).performClick()
                activity.showKeyboard()
                assertFalse(activity.computerKeyboard.isVisible)
                assertFalse(activity.findViewById<View>(R.id.floating_touchpad).isVisible)
            }
        }
        assertEquals(listOf(1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
        assertEquals(listOf(XKeySym.XK_Control_L to true, XKeySym.XK_Control_L to false),
                vncSession.server.receivedKeySyms)
    }

    @Test fun pipHidesControlsAndReturningDoesNotRestoreHeldKeys() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_ALT_LEFT}").performLongClick()
                activity.viewModel.inPiPMode.value = true
                assertFalse(activity.computerKeyboard.isVisible)
                assertFalse(activity.findViewById<View>(R.id.floating_touchpad).isVisible)
                activity.viewModel.inPiPMode.value = false
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_A}").performClick()
            }
        }
        assertEquals(listOf(XKeySym.XK_Alt_L to true, XKeySym.XK_Alt_L to false,
                'a'.code to true, 'a'.code to false), vncSession.server.receivedKeySyms)
    }

    @Test fun cancellingUnusedTouchpadDoesNotMoveAnExternalDragToItsOldPosition() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.viewModel.messenger!!.sendPointerButtonDown(com.gaurav.avnc.vnc.PointerButton.Left, PointF(7f, 8f))
                activity.floatingTouchpad.cancel()
                activity.onWindowFocusChanged(false)
            }
        }
        assertEquals(listOf(1, 0), vncSession.server.receivedPointerEvents.map { it.mask })
        assertTrue(vncSession.server.receivedPointerEvents.all { it.x == 7 && it.y == 8 })
    }

    @Test fun remoteScrollCoversBothAxesAndDiscardsRemainderBetweenGestures() {
        // Exercise wheel fractions independently of direction-lock activation and momentum.
        vncSession.profile.remoteInputOverrides = com.gaurav.avnc.model.RemoteInputOverrides(
                axisLock = false, inertia = "off").encode()
        vncSession.run {
            lateinit var dispatcher: com.gaurav.avnc.ui.vnc.input.Dispatcher
            lateinit var point: PointF
            var density = 1f
            vncSession.onActivity { activity ->
                dispatcher = com.gaurav.avnc.ui.vnc.input.Dispatcher(activity)
                density = activity.resources.displayMetrics.density
                point = activity.viewModel.frameState.toVP(PointF(4f, 4f))
                dispatcher.onGestureStart()
                dispatcher.onSwipe2(point, point, 3f * density, 3f * density)
            }
            pollingAssert { assertEquals(listOf(32, 0, 8, 0), vncSession.server.receivedPointerEvents.map { it.mask }) }
            vncSession.onActivity {
                dispatcher.onSwipe2(point, point, -2f * density, -2f * density)
                dispatcher.onGestureStop(point)
            }
            pollingAssert { assertEquals(listOf(32, 0, 8, 0, 64, 0, 16, 0), vncSession.server.receivedPointerEvents.map { it.mask }) }
            // 1 dp is below the 2 dp threshold: separate completed sessions must
            // discard their fraction rather than accumulating an extra wheel event.
            repeat(2) {
                vncSession.onActivity {
                    dispatcher.onGestureStart()
                    dispatcher.onSwipe2(point, point, 1f * density, 1f * density)
                    dispatcher.onGestureStop(point)
                }
                // Each normal end must get its animation frame before the next begin.
                Thread.sleep(80)
            }
            assertEquals(listOf(32, 0, 8, 0, 64, 0, 16, 0),
                    vncSession.server.receivedPointerEvents.map { it.mask })
        }
    }

    @Test fun capsLockStaysConsistentAcrossViewOnlyTransition() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_page_functions").performClick()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_CAPS_LOCK}").performClick()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_page_common").performClick()
                activity.viewModel.setViewMode(ServerProfile.VIEW_MODE_NO_INPUT)
                activity.viewModel.setViewMode(ServerProfile.VIEW_MODE_NORMAL)
                activity.showKeyboard()
                activity.computerKeyboard.container.findViewWithTag<View>("keyboard_key_${KeyEvent.KEYCODE_A}").performClick()
            }
        }
        assertEquals(1, vncSession.server.receivedKeyDowns.count { it == XKeySym.XK_Caps_Lock })
        assertTrue(vncSession.server.receivedKeyDowns.contains('A'.code))
    }

    @Test fun surfaceDoubleTapSendsExactlyTwoClicks() {
        vncSession.run {
            vncSession.onActivity { activity ->
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                repeat(2) {
                    touch(surface, MotionEvent.ACTION_DOWN, 20f, 20f)
                    touch(surface, MotionEvent.ACTION_UP, 20f, 20f)
                }
            }
            pollingAssert { assertEquals(listOf(1, 0, 1, 0), vncSession.server.receivedPointerEvents.map { it.mask }) }
            Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            assertEquals(4, vncSession.server.receivedPointerEvents.size)
        }
    }

    @Test fun surfaceLongPressStartsDragAndLiftReleasesWithoutClick() {
        vncSession.run {
            val down = SystemClock.uptimeMillis()
            vncSession.onActivity { activity ->
                touch(activity.findViewById(R.id.touchpad_surface), MotionEvent.ACTION_DOWN, 20f, 20f, down)
            }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            pollingAssert { assertEquals(1, vncSession.server.receivedPointerEvents.last().mask) }
            vncSession.onActivity { activity ->
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                assertTrue(surface.isSelected)
                touch(surface, MotionEvent.ACTION_MOVE, 100f, 70f, down)
                touch(surface, MotionEvent.ACTION_UP, 100f, 70f, down)
                assertFalse(surface.isSelected)
            }
            pollingAssert { assertEquals(0, vncSession.server.receivedPointerEvents.last().mask) }
            val events = vncSession.server.receivedPointerEvents
            assertTrue(events.dropLast(1).all { it.mask == 1 })
            assertTrue(events.any { it.mask == 1 && it.x > 0 && it.y > 0 })
            assertEquals(1, events.count { it.mask == 0 })
        }
    }

    @Test fun surfaceDoubleTapHoldDragsWithoutExtraClicks() {
        vncSession.run {
            vncSession.onActivity { activity ->
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                touch(surface, MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(surface, MotionEvent.ACTION_UP, 20f, 20f)
                touch(surface, MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(surface, MotionEvent.ACTION_MOVE, 100f, 70f)
                touch(surface, MotionEvent.ACTION_UP, 100f, 70f)
            }
            pollingAssert { assertEquals(0, vncSession.server.receivedPointerEvents.last().mask) }
            Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            val events = vncSession.server.receivedPointerEvents
            assertTrue(events.dropLast(1).all { it.mask == 1 })
            assertTrue(events.any { it.mask == 1 && it.x > 0 && it.y > 0 })
            assertEquals(1, events.count { it.mask == 0 })
        }
    }

    @Test fun surfaceTwoFingerTapRightClicksWithoutFollowingLeftClick() {
        vncSession.run {
            pollingAssert {
                vncSession.onActivity { assertTrue(it.findViewById<View>(R.id.touchpad_surface).isLaidOut) }
            }
            vncSession.onActivity { activity ->
                val stream = PanelTouchStream(activity.findViewById(R.id.floating_touchpad))
                val first = stream.point(R.id.touchpad_surface, .25f, .5f)
                val second = stream.point(R.id.touchpad_surface, .75f, .5f)
                stream.send(MotionEvent.ACTION_DOWN, first)
                stream.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
                stream.send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
                stream.send(MotionEvent.ACTION_UP, first)
            }
            pollingAssert { assertEquals(listOf(4, 0), vncSession.server.receivedPointerEvents.map { it.mask }) }
            Thread.sleep(ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            assertEquals(listOf(4, 0), vncSession.server.receivedPointerEvents.map { it.mask })
        }
    }

    @Test fun surfaceTwoFingerScrollDoesNotClickOrMoveAfterFirstLift() {
        vncSession.run {
            pollingAssert {
                vncSession.onActivity { assertTrue(it.findViewById<View>(R.id.touchpad_surface).isLaidOut) }
            }
            vncSession.onActivity { activity ->
                val stream = PanelTouchStream(activity.findViewById(R.id.floating_touchpad))
                val first = stream.point(R.id.touchpad_surface, .15f, .35f)
                val second = stream.point(R.id.touchpad_surface, .35f, .65f)
                val firstEnd = stream.point(R.id.touchpad_surface, .55f, .35f)
                val secondEnd = stream.point(R.id.touchpad_surface, .75f, .65f)
                stream.send(MotionEvent.ACTION_DOWN, first)
                stream.send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
                stream.send(MotionEvent.ACTION_MOVE, firstEnd, secondEnd)
                stream.send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), firstEnd, secondEnd)
                stream.send(MotionEvent.ACTION_MOVE, first)
                stream.send(MotionEvent.ACTION_UP, first)
            }
            pollingAssert { assertTrue(vncSession.server.receivedPointerEvents.any { it.mask == 32 }) }
            Thread.sleep(800) // Allow the bounded inertia window and any erroneous tap to finish.
            val events = vncSession.server.receivedPointerEvents
            assertTrue(events.all { it.mask == 0 || it.mask == 32 })
            assertTrue(events.all { it.x == events.first().x && it.y == events.first().y })
        }
    }

    @Test fun cancellingSurfaceDropsPendingTapAndLongPress() {
        vncSession.run {
            vncSession.onActivity { activity ->
                val surface = activity.findViewById<View>(R.id.touchpad_surface)
                touch(surface, MotionEvent.ACTION_DOWN, 20f, 20f)
                touch(surface, MotionEvent.ACTION_UP, 20f, 20f)
                activity.floatingTouchpad.cancel()
            }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
            vncSession.onActivity { activity ->
                touch(activity.findViewById(R.id.touchpad_surface), MotionEvent.ACTION_DOWN, 20f, 20f)
                activity.floatingTouchpad.cancel()
            }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
            assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
        }
    }

    @Test fun expandingCompactedTouchpadNeverClosesKeyboard() {
        vncSession.run {
            vncSession.onActivity { activity ->
                activity.showKeyboard()
                activity.floatingTouchpad.setDocked(false)
                activity.binding.touchpadHost.setPadding(0, 0, 0, activity.binding.touchpadHost.height)
                activity.floatingTouchpad.reposition()
                activity.floatingTouchpad.toggle()
                activity.floatingTouchpad.toggle()
                assertTrue(activity.computerKeyboard.isVisible)
                assertFalse(activity.findViewById<View>(R.id.floating_touchpad).isVisible)
                activity.binding.touchpadHost.setPadding(0, 0, 0, 0)
                activity.floatingTouchpad.reposition()
                assertTrue(activity.floatingTouchpad.isExpanded)
                assertTrue(activity.computerKeyboard.isVisible)
            }
        }
    }
    @Test fun shortViewportAutomaticallyCompactsWithoutChangingSavedPreference() {
        vncSession.run {
            vncSession.onActivity { activity ->
                val host = activity.binding.touchpadHost
                val panel = activity.findViewById<View>(R.id.floating_touchpad)
                assertTrue(panel.isVisible)
                host.setPadding(host.paddingLeft, host.paddingTop, host.paddingRight, host.height)
                activity.floatingTouchpad.reposition()
                assertFalse(panel.isVisible)
                assertFalse(activity.viewModel.pref.input.touchpadCollapsed)
                assertTrue(activity.viewModel.pref.input.showTouchpad)
                assertTrue(activity.findViewById<View>(R.id.controls_capsule).isShown)
                host.setPadding(0, 0, 0, 0)
                activity.floatingTouchpad.reposition()
                assertTrue(panel.isVisible)
            }
        }
        assertTrue(vncSession.server.receivedPointerEvents.isEmpty())
    }

}
