/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.vnc.XKeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verify coordinates and button masks after they cross the actual RFB socket. */
@RunWith(AndroidJUnit4::class)
class GestureMouseTest {
    @JvmField @Rule val prefsRule = CleanPrefsRule()

    private fun withSession(inertia: Boolean = true, block: (VncSessionScenario, Dispatcher) -> Unit) {
        targetPrefs.edit {
            putBoolean("show_touchpad", false)
            putBoolean("remote_scroll_inertia", inertia)
        }
        val session = VncSessionScenario(TestServer(width = 1600, height = 1200))
        var dispatcher: Dispatcher? = null
        try {
            session.start()
            pollingAssert {
                session.onActivity { assertTrue(it.binding.inputView.isLaidOut) }
            }
            session.onActivity {
                it.viewModel.setZoom(3f, 3f)
                dispatcher = Dispatcher(it)
            }
            session.server.receivedPointerEvents.clear()
            block(session, checkNotNull(dispatcher))
        } finally {
            if (session.activityScenario != null) session.onActivity { dispatcher?.cancelInput() }
            session.stop()
        }
    }

    private fun center(activity: VncActivity): PointF = activity.viewModel.frameState.safeArea.let {
        PointF(it.centerX(), it.centerY())
    }

    private fun assertAt(point: PointF, event: TestServer.PointerEvent) {
        assertEquals(point.x.toInt(), event.x)
        assertEquals(point.y.toInt(), event.y)
    }

    /** Deliver the complete stream through the session's real InputView/InputHandler. */
    private fun touch(session: VncSessionScenario, downTime: Long, action: Int, vararg points: PointF) {
        session.onActivity { activity ->
            val properties = Array(points.size) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
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
                assertTrue(activity.binding.inputView.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    /** A key sent after cancellation acts as a sender/socket barrier for the pointer log. */
    private fun flush(session: VncSessionScenario, dispatcher: Dispatcher) {
        session.onActivity { dispatcher.onXKey(XKeySym.XK_F12, 0, true) }
        pollingAssert { assertTrue(session.server.receivedKeyDowns.contains(XKeySym.XK_F12)) }
        session.server.receivedKeySyms.clear()
    }

    @Test fun longPressDragStartsAtAnchorAndReleasesAtLastPosition() = withSession { session, dispatcher ->
        lateinit var anchor: PointF
        lateinit var finish: PointF
        session.onActivity {
            val start = center(it)
            val end = PointF(start.x + 70, start.y + 40)
            anchor = checkNotNull(it.viewModel.frameState.toFb(start))
            finish = checkNotNull(it.viewModel.frameState.toFb(end))
            dispatcher.onGestureStart()
            dispatcher.onLongPress(start)
            dispatcher.onDragMove(end, 70f, 40f)
            dispatcher.onGestureStop(end)
        }
        pollingAssert { assertEquals(0, session.server.receivedPointerEvents.lastOrNull()?.mask) }
        val events = session.server.receivedPointerEvents.toList()
        assertTrue(events.size >= 3)
        assertTrue(events.dropLast(1).all { it.mask == 1 })
        assertAt(anchor, events.first())
        assertAt(finish, events.last())
    }

    @Test fun inputViewLongPressMovesAndReleasesLeftWithoutRightClick() = withSession { session, _ ->
        lateinit var start: PointF
        lateinit var end: PointF
        lateinit var anchor: PointF
        lateinit var finish: PointF
        session.onActivity {
            start = center(it)
            end = PointF(start.x + 100f, start.y + 70f)
            anchor = checkNotNull(it.viewModel.frameState.toFb(start))
            finish = checkNotNull(it.viewModel.frameState.toFb(end))
        }
        val downTime = SystemClock.uptimeMillis()
        touch(session, downTime, MotionEvent.ACTION_DOWN, start)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
        pollingAssert { assertEquals(1, session.server.receivedPointerEvents.firstOrNull()?.mask) }
        touch(session, downTime, MotionEvent.ACTION_MOVE, end)
        touch(session, downTime, MotionEvent.ACTION_UP, end)
        pollingAssert { assertEquals(0, session.server.receivedPointerEvents.lastOrNull()?.mask) }
        // Allow delayed single/double-tap callbacks to reveal any unwanted trailing click.
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 100L)
        val events = session.server.receivedPointerEvents.toList()
        assertTrue(events.size >= 3)
        assertTrue(events.dropLast(1).all { it.mask == 1 })
        assertEquals(0, events.last().mask)
        assertAt(anchor, events.first())
        assertAt(finish, events.last())
    }

    @Test fun inputViewAdditionalFingerCancelsDragAndRemainingFingerCannotClick() = withSession { session, _ ->
        lateinit var start: PointF
        session.onActivity { start = center(it) }
        val second = PointF(start.x + 120f, start.y + 40f)
        val remaining = PointF(start.x + 60f, start.y + 30f)
        val downTime = SystemClock.uptimeMillis()
        touch(session, downTime, MotionEvent.ACTION_DOWN, start)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
        pollingAssert { assertEquals(1, session.server.receivedPointerEvents.firstOrNull()?.mask) }
        touch(session, downTime, MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start, second)
        pollingAssert { assertEquals(0, session.server.receivedPointerEvents.lastOrNull()?.mask) }
        touch(session, downTime, MotionEvent.ACTION_POINTER_UP or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), start, second)
        touch(session, downTime, MotionEvent.ACTION_MOVE, remaining)
        touch(session, downTime, MotionEvent.ACTION_UP, remaining)
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 100L)
        val events = session.server.receivedPointerEvents.toList()
        assertEquals(listOf(1, 0), events.map { it.mask })
        assertEquals(events.first().x, events.last().x)
        assertEquals(events.first().y, events.last().y)
    }

    @Test fun doubleTapHoldDragUsesOriginalAnchorWithoutDoubleClick() = withSession { session, dispatcher ->
        lateinit var anchor: PointF
        session.onActivity {
            val start = center(it)
            val end = PointF(start.x + 90, start.y + 50)
            anchor = checkNotNull(it.viewModel.frameState.toFb(start))
            dispatcher.onGestureStart()
            dispatcher.onDoubleTapSwipe(start, end, 90f, 50f)
            dispatcher.onGestureStop(end)
        }
        pollingAssert { assertEquals(0, session.server.receivedPointerEvents.lastOrNull()?.mask) }
        val events = session.server.receivedPointerEvents.toList()
        assertTrue(events.size >= 2)
        assertTrue(events.dropLast(1).all { it.mask == 1 })
        assertAt(anchor, events.first())
    }

    @Test fun draggingOutsideViewportStillReleasesWithinFramebuffer() = withSession { session, dispatcher ->
        session.onActivity {
            val start = center(it)
            val outside = PointF(-10000f, -10000f)
            dispatcher.onGestureStart()
            dispatcher.onLongPress(start)
            dispatcher.onDragMove(outside, outside.x - start.x, outside.y - start.y)
            dispatcher.onGestureStop(outside)
        }
        pollingAssert { assertEquals(0, session.server.receivedPointerEvents.lastOrNull()?.mask) }
        val events = session.server.receivedPointerEvents.toList()
        assertTrue(events.first().mask == 1)
        assertTrue(events.all { it.x in 0..1599 && it.y in 0..1199 })
        assertEquals(events[events.lastIndex - 1].x, events.last().x)
        assertEquals(events[events.lastIndex - 1].y, events.last().y)
    }

    @Test fun canceledDragReleasesOnceAndDoesNotContinueMoving() = withSession { session, dispatcher ->
        session.onActivity {
            val start = center(it)
            dispatcher.onGestureStart()
            dispatcher.onLongPress(start)
            dispatcher.onGestureCancel()
            dispatcher.cancelInput()
        }
        flush(session, dispatcher)
        val events = session.server.receivedPointerEvents.toList()
        assertEquals(listOf(1, 0), events.map { it.mask })
        Thread.sleep(100)
        assertEquals(events, session.server.receivedPointerEvents.toList())
    }

    @Test fun twoFingerTapRightClicksAtRequestedLocation() = withSession { session, dispatcher ->
        lateinit var anchor: PointF
        session.onActivity {
            val start = center(it)
            anchor = checkNotNull(it.viewModel.frameState.toFb(start))
            dispatcher.onGestureStart()
            dispatcher.onTap2(start)
            dispatcher.onGestureStop(start)
        }
        pollingAssert { assertEquals(2, session.server.receivedPointerEvents.size) }
        val events = session.server.receivedPointerEvents.toList()
        assertEquals(listOf(4, 0), events.map { it.mask })
        events.forEach { assertAt(anchor, it) }
    }

    @Test fun stationaryEdgeDragPansAndUpdatesHeldRemoteCoordinate() = withSession { session, dispatcher ->
        var initialFrameX = 0f
        lateinit var edge: PointF
        session.onActivity {
            val fs = it.viewModel.frameState
            it.viewModel.setZoom(5f, 5f)
            it.viewModel.moveFrameTo((fs.safeArea.width() - fs.fbWidth * fs.scale) / 2,
                                    (fs.safeArea.height() - fs.fbHeight * fs.scale) / 2)
            initialFrameX = fs.frameX
            edge = PointF(fs.safeArea.right - 2, fs.safeArea.centerY())
            dispatcher.onGestureStart()
            dispatcher.onLongPress(edge)
        }
        // No MOVE is delivered: animation frames must continue the drag themselves.
        pollingAssert {
            session.onActivity { assertTrue(it.viewModel.frameState.frameX < initialFrameX - 10) }
            val events = session.server.receivedPointerEvents.toList()
            assertTrue(events.size > 2)
            assertTrue(events.last().x > events.first().x)
        }
        session.onActivity { dispatcher.onGestureStop(edge) }
        flush(session, dispatcher)
        val events = session.server.receivedPointerEvents.toList()
        assertEquals(0, events.last().mask)
        assertTrue(events.dropLast(1).all { it.mask == 1 })
        assertTrue(events.all { it.x in 0..1599 && it.y in 0..1199 })
        val stoppedCount = events.size
        Thread.sleep(100)
        assertEquals(stoppedCount, session.server.receivedPointerEvents.size)
    }

    @Test fun shortTwoFingerSwipesScrollInAllFourDirections() = withSession(inertia = false) { session, dispatcher ->
        val masks = mutableListOf<Int>()
        for ((delta, mask) in listOf(PointF(0f, 9f) to 8, PointF(0f, -9f) to 16,
                                    PointF(9f, 0f) to 32, PointF(-9f, 0f) to 64)) {
            session.onActivity {
                val start = center(it)
                val density = it.resources.displayMetrics.density
                dispatcher.onGestureStart()
                dispatcher.onSwipe2(start, start, delta.x * density, delta.y * density)
                dispatcher.onGestureStop(start)
            }
            masks.add(mask)
            masks.add(0)
            pollingAssert { assertEquals(masks, session.server.receivedPointerEvents.map { it.mask }) }
        }
    }

    @Test fun touchpadScrollKeepsSavedSensitivityAndVerticalInversion() {
        targetPrefs.edit {
            putInt("gesture_swipe_sensitivity", 20)
            putBoolean("invert_vertical_scrolling", true)
        }
        withSession(inertia = false) { session, dispatcher ->
            session.onActivity {
                it.viewModel.profile.remoteInputOverrides = "{\"axisLock\":false}"
                it.applyRemoteInputSettings()
                dispatcher.onVirtualPointerStart()
                // 5 dp at 2x sensitivity exceeds the new 8 dp tick threshold.
                dispatcher.onVirtualPointerScroll(5f, 5f)
                dispatcher.onVirtualPointerStop()
            }
            pollingAssert {
                assertEquals(listOf(32, 0, 16, 0), session.server.receivedPointerEvents.map { it.mask })
            }
        }
    }

    @Test fun remoteScrollEmitsBothAxesAndDropsExcessBacklog() = withSession { session, dispatcher ->
        session.onActivity {
            val start = center(it)
            val density = it.resources.displayMetrics.density
            dispatcher.onGestureStart()
            dispatcher.onSwipe2(start, start, 100000f * density, 100000f * density)
            dispatcher.onRemoteScrollEnd(0f, 0f)
            dispatcher.onGestureStop(start)
        }
        pollingAssert { assertTrue(session.server.receivedPointerEvents.any { it.mask != 0 }) }
        Thread.sleep(120)
        val ticks = session.server.receivedPointerEvents.filter { it.mask != 0 }
        assertTrue(ticks.any { it.mask == 32 }) // WheelLeft
        assertTrue(ticks.any { it.mask == 8 }) // WheelUp
        assertTrue(ticks.size <= 4)
        assertTrue(session.server.receivedPointerEvents.all { it.x in 0..1599 && it.y in 0..1199 })
        val count = session.server.receivedPointerEvents.size
        Thread.sleep(100)
        assertEquals(count, session.server.receivedPointerEvents.size)
    }

    @Test fun scrollInertiaContinuesAfterReleaseAndNewTouchStopsIt() = withSession { session, dispatcher ->
        session.onActivity {
            val start = center(it)
            val density = it.resources.displayMetrics.density
            dispatcher.onGestureStart()
            dispatcher.onSwipe2(start, start, 40f * density, 40f * density)
            dispatcher.onRemoteScrollEnd(3500f * density, 3500f * density)
            dispatcher.onGestureStop(start)
        }
        pollingAssert { assertTrue(session.server.receivedPointerEvents.count { it.mask != 0 } > 4) }
        session.onActivity { dispatcher.onGestureStart() }
        flush(session, dispatcher)
        val stopped = session.server.receivedPointerEvents.size
        Thread.sleep(150)
        assertEquals(stopped, session.server.receivedPointerEvents.size)
    }

    @Test fun scrollKeepsItsRemoteAnchorWhenViewportMoves() = withSession { session, dispatcher ->
        lateinit var start: PointF
        lateinit var anchor: PointF
        session.onActivity {
            start = center(it)
            anchor = checkNotNull(it.viewModel.frameState.toFb(start))
            val density = it.resources.displayMetrics.density
            dispatcher.onGestureStart()
            dispatcher.onSwipe2(start, start, 40f * density, 0f)
        }
        pollingAssert { assertTrue(session.server.receivedPointerEvents.any { it.mask == 32 }) }
        val previousCount = session.server.receivedPointerEvents.size
        session.onActivity {
            it.viewModel.panFrame(-80f, -40f)
            val density = it.resources.displayMetrics.density
            dispatcher.onSwipe2(start, PointF(start.x + 50, start.y + 50), 40f * density, 0f)
            dispatcher.onRemoteScrollEnd(0f, 0f)
            dispatcher.onGestureStop(start)
        }
        pollingAssert { assertTrue(session.server.receivedPointerEvents.size > previousCount) }
        session.server.receivedPointerEvents.forEach { assertAt(anchor, it) }
    }

    @Test fun disabledInertiaStopsAfterTheGesture() = withSession(inertia = false) { session, dispatcher ->
        session.onActivity {
            val start = center(it)
            val density = it.resources.displayMetrics.density
            dispatcher.onGestureStart()
            dispatcher.onSwipe2(start, start, 40f * density, 0f)
            dispatcher.onRemoteScrollEnd(5000f * density, 0f)
            dispatcher.onGestureStop(start)
        }
        pollingAssert { assertTrue(session.server.receivedPointerEvents.any { it.mask == 32 }) }
        Thread.sleep(100)
        val stopped = session.server.receivedPointerEvents.size
        Thread.sleep(150)
        assertEquals(stopped, session.server.receivedPointerEvents.size)
    }
}
