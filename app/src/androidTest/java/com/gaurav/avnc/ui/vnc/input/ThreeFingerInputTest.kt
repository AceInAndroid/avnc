/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.targetConfigContext
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.ui.vnc.FrameView
import com.gaurav.avnc.util.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class ThreeFingerInputTest {
    @JvmField @Rule val cleanPrefs = CleanPrefsRule()
    private lateinit var dispatcher: Dispatcher
    private lateinit var touch: TouchHandler
    private val points = listOf(PointF(100f, 200f), PointF(300f, 200f), PointF(500f, 200f))
    private val density get() = targetConfigContext.resources.displayMetrics.density

    @Before fun setup() {
        instrumentation.runOnMainSync {
            dispatcher = mockk(relaxed = true)
            every { dispatcher.systemGesturesEnabled } returns true
            touch = TouchHandler(FrameView(targetConfigContext), dispatcher, AppPreferences(targetContext))
        }
        dispatcher.onXKey(0, 0, false)
    }

    private fun send(event: MotionEvent) {
        try { instrumentation.runOnMainSync { touch.onTouchEvent(event) } }
        finally { event.recycle() }
    }

    @Test fun threeFingerSwipeCancelsOldDetectorsAndNeverClicksOrPansAfterLift() {
        val down = TouchHandlerTest.Factory.obtainDownEvent(points[0], InputDevice.SOURCE_TOUCHSCREEN)
        // Factory uses down only for its time/source; keep the original event until the stream ends.
        try {
            send(MotionEvent.obtain(down))
            send(TouchHandlerTest.Factory.obtainPointerDownEvent(down, *points.take(2).toTypedArray()))
            send(TouchHandlerTest.Factory.obtainPointerDownEvent(down, *points.toTypedArray()))
            val moved = points.map { PointF(it.x + 100f * density, it.y) }.toTypedArray()
            send(TouchHandlerTest.Factory.obtainMoveEvent(down, *moved))
            send(TouchHandlerTest.Factory.obtainMoveEvent(down, *points.toTypedArray()))
            send(TouchHandlerTest.Factory.obtainPointerUpEvent(down, *moved))
            send(TouchHandlerTest.Factory.obtainPointerUpEvent(down, *moved.take(2).toTypedArray()))
            send(TouchHandlerTest.Factory.obtainMoveEvent(down, moved[0]))
            send(TouchHandlerTest.Factory.obtainUpEvent(down, moved[0]))
        } finally { down.recycle() }
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 80L)
        verify(exactly = 1) { dispatcher.onThreeFingerAction("right") }
        verify(exactly = 0) { dispatcher.onSwipe1(any(), any(), any(), any()) }
        verify(exactly = 0) { dispatcher.onSwipe2(any(), any(), any(), any()) }
        verify(exactly = 0) { dispatcher.onSwipe3(any(), any(), any(), any()) }
        verify(exactly = 0) { dispatcher.onScale(any(), any(), any()) }
        verify(exactly = 0) { dispatcher.onTap1(any()) }
        verify(exactly = 0) { dispatcher.onTap2(any()) }
        verify(exactly = 0) { dispatcher.onTap3(any()) }
    }

    @Test fun thirdFingerAfterCommittedScrollCancelsWithoutSystemAction() {
        val down = TouchHandlerTest.Factory.obtainDownEvent(points[0], InputDevice.SOURCE_TOUCHSCREEN)
        try {
            send(MotionEvent.obtain(down))
            send(TouchHandlerTest.Factory.obtainPointerDownEvent(down, *points.take(2).toTypedArray()))
            val moved = points.map { PointF(it.x, it.y + 100f * density) }.toTypedArray()
            send(TouchHandlerTest.Factory.obtainMoveEvent(down, *moved.take(2).toTypedArray()))
            send(TouchHandlerTest.Factory.obtainPointerDownEvent(down, *moved))
            send(TouchHandlerTest.Factory.obtainMoveEvent(down,
                    *moved.map { PointF(it.x + 200f * density, it.y) }.toTypedArray()))
            send(TouchHandlerTest.Factory.obtainPointerUpEvent(down, *moved))
            send(TouchHandlerTest.Factory.obtainPointerUpEvent(down, *moved.take(2).toTypedArray()))
            send(TouchHandlerTest.Factory.obtainUpEvent(down, moved[0]))
        } finally { down.recycle() }
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 80L)
        verify(atLeast = 1) { dispatcher.onSwipe2(any(), any(), any(), any()) }
        verify(atLeast = 1) { dispatcher.onGestureCancel() }
        verify(exactly = 0) { dispatcher.onThreeFingerAction(any()) }
        verify(exactly = 0) { dispatcher.onTap1(any()) }
        verify(exactly = 0) { dispatcher.onTap2(any()) }
    }

    @Test fun thirdFingerAfterPinchCannotTriggerSystemAction() {
        val first = PointF(300f * density, 300f * density)
        val second = PointF(500f * density, 300f * density)
        val down = TouchHandlerTest.Factory.obtainDownEvent(first, InputDevice.SOURCE_TOUCHSCREEN)
        try {
            send(MotionEvent.obtain(down))
            send(TouchHandlerTest.Factory.obtainPointerDownEvent(down, first, second))
            repeat(4) {
                first.offset(-50f * density, 0f)
                second.offset(50f * density, 0f)
                send(TouchHandlerTest.Factory.obtainMoveEvent(down, first, second))
            }
            val third = PointF(second.x + 100f * density, second.y)
            send(TouchHandlerTest.Factory.obtainPointerDownEvent(down, first, second, third))
            send(TouchHandlerTest.Factory.obtainMoveEvent(down,
                    PointF(first.x, first.y - 100f * density),
                    PointF(second.x, second.y - 100f * density),
                    PointF(third.x, third.y - 100f * density)))
            send(TouchHandlerTest.Factory.obtainPointerUpEvent(down, first, second, third))
            send(TouchHandlerTest.Factory.obtainPointerUpEvent(down, first, second))
            send(TouchHandlerTest.Factory.obtainUpEvent(down, first))
        } finally { down.recycle() }
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 80L)
        verify(atLeast = 1) { dispatcher.onScale(any(), any(), any()) }
        verify(exactly = 0) { dispatcher.onThreeFingerAction(any()) }
        verify(exactly = 0) { dispatcher.onTap1(any()) }
        verify(exactly = 0) { dispatcher.onTap2(any()) }
    }
}
