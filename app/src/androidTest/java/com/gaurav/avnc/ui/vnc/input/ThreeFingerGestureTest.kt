/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreeFingerGestureTest {
    private val actions = mutableListOf<String>()
    private var captures = 0
    private val detector = ThreeFingerGesture(1f, 8f, { captures++ }, { actions += it })
    private val starts = listOf(PointF(100f, 100f), PointF(200f, 100f), PointF(300f, 100f))
    private val ids = listOf(7, 11, 20)
    private val downTime = SystemClock.uptimeMillis()

    private fun event(action: Int, points: List<PointF>, pointerIds: List<Int> = ids.take(points.size),
                      enabled: Boolean = true, blocked: Boolean = false): Boolean {
        val properties = Array(points.size) { i -> MotionEvent.PointerProperties().apply {
            id = pointerIds[i]
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply {
            x = points[i].x; y = points[i].y
            pressure = 1f; size = 1f
        } }
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { detector.onTouchEvent(e, enabled, blocked) } finally { e.recycle() }
    }

    private fun begin(blocked: Boolean = false) {
        assertFalse(event(MotionEvent.ACTION_DOWN, starts.take(1)))
        assertFalse(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), starts.take(2)))
        assertTrue(event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), starts, blocked = blocked))
    }

    private fun move(dx: Float, dy: Float) = event(MotionEvent.ACTION_MOVE,
            starts.map { PointF(it.x + dx, it.y + dy) })

    @Test fun fourDirectionsTriggerExactlyOncePerTouchStream() {
        for ((delta, direction) in listOf(PointF(-40f, 0f) to "left", PointF(40f, 0f) to "right",
                                        PointF(0f, -40f) to "up", PointF(0f, 40f) to "down")) {
            begin()
            assertTrue(move(delta.x, delta.y))
            assertTrue(move(-delta.x * 3, -delta.y * 3))
            assertEquals(direction, actions.last())
            assertTrue(event(MotionEvent.ACTION_UP, starts.take(1)))
        }
        assertEquals(listOf("left", "right", "up", "down"), actions)
        assertEquals(4, captures)
    }

    @Test fun jitterAndAmbiguousDiagonalDoNotTrigger() {
        begin()
        move(20f, 0f)
        move(40f, 40f)
        assertTrue(actions.isEmpty())
        move(70f, 40f)
        assertEquals(listOf("right"), actions)
    }

    @Test fun stablePointerIdsSurviveIndexReordering() {
        begin()
        event(MotionEvent.ACTION_MOVE, listOf(starts[2], starts[0], starts[1]).map {
            PointF(it.x, it.y - 40f)
        }, listOf(20, 7, 11))
        assertEquals(listOf("up"), actions)
    }

    @Test fun committedScrollOrDragOnlyCancelsAndConsumesRemainingFingers() {
        begin(blocked = true)
        assertTrue(move(0f, -200f))
        assertTrue(event(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), starts))
        assertTrue(event(MotionEvent.ACTION_MOVE, starts.take(2)))
        assertTrue(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), starts.take(2)))
        assertTrue(event(MotionEvent.ACTION_UP, starts.take(1)))
        assertTrue(actions.isEmpty())
        assertEquals(1, captures)
    }

    @Test fun fourthFingerCancelsBeforeAnyAction() {
        begin()
        assertTrue(event(MotionEvent.ACTION_POINTER_DOWN or (3 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                starts + PointF(400f, 100f), ids + 24))
        assertTrue(move(100f, 0f))
        assertTrue(actions.isEmpty())
    }

    @Test fun cancellationBlocksCurrentStreamButNewDownStartsFresh() {
        begin()
        detector.cancel()
        assertTrue(move(100f, 0f))
        assertTrue(actions.isEmpty())
        assertTrue(event(MotionEvent.ACTION_UP, starts.take(1)))
        begin()
        move(-40f, 0f)
        assertEquals(listOf("left"), actions)
    }

    @Test fun pointerLiftBeforeThresholdCannotBecomeAnotherGesture() {
        begin()
        assertTrue(event(MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), starts))
        assertTrue(move(100f, 0f))
        assertTrue(actions.isEmpty())
    }

    @Test fun disabledModeLeavesLegacyGestureStreamUntouched() {
        assertFalse(event(MotionEvent.ACTION_DOWN, starts.take(1), enabled = false))
        assertFalse(event(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), starts, enabled = false))
        assertFalse(event(MotionEvent.ACTION_MOVE, starts, enabled = false))
        assertEquals(0, captures)
        assertTrue(actions.isEmpty())
    }

    @Test fun cancellationWithoutThreeFingerOwnershipDoesNotFreezePointerMovement() {
        event(MotionEvent.ACTION_DOWN, starts.take(1))
        detector.cancel()
        assertFalse(event(MotionEvent.ACTION_MOVE, listOf(PointF(150f, 100f))))
        assertFalse(event(MotionEvent.ACTION_UP, starts.take(1)))
        assertTrue(actions.isEmpty())
    }

    @Test fun thresholdRespectsTwiceSystemTouchSlop() {
        val directions = mutableListOf<String>()
        val largeSlop = ThreeFingerGesture(1f, 24f, {}, { directions += it })
        fun send(action: Int, dx: Float = 0f) {
            val properties = Array(3) { i -> MotionEvent.PointerProperties().apply { id = ids[i] } }
            val coords = Array(3) { i -> MotionEvent.PointerCoords().apply { x = starts[i].x + dx; y = starts[i].y } }
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 3,
                    properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { largeSlop.onTouchEvent(e, true, false) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
        send(MotionEvent.ACTION_MOVE, 40f)
        assertTrue(directions.isEmpty())
        send(MotionEvent.ACTION_MOVE, 49f)
        assertEquals(listOf("right"), directions)
    }
}
