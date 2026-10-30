/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.graphics.PointF
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.model.RemoteInputConfig
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.vnc.PointerButton
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake display frames and clock prove pacing without depending on the device's refresh rate. */
@RunWith(AndroidJUnit4::class)
class RemoteScrollControllerTest {
    private data class Tick(val button: PointerButton, val point: PointF)

    private class Scroll(
        var config: RemoteInputConfig = RemoteInputConfig(axisLock = false, inertia = "off"),
    ) {
        var time = 1000L
        var acceptsInput = true
        var inertiaEnabled = true
        var queued: Runnable? = null
        val ticks = mutableListOf<Tick>()
        val view = mockk<View>()
        val controller: RemoteScrollController

        init {
            every { view.context } returns targetContext
            every { view.resources } returns targetContext.resources
            every { view.postOnAnimation(any()) } answers {
                check(queued == null) { "More than one animation callback scheduled" }
                queued = firstArg()
            }
            every { view.removeCallbacks(any()) } answers {
                val callback = firstArg<Runnable>()
                val removed = queued === callback
                if (removed) queued = null
                removed
            }
            controller = RemoteScrollController(view, { inertiaEnabled }, { acceptsInput },
                    { button, point -> ticks += Tick(button, PointF(point.x, point.y)) },
                    { config }, { time })
            controller.begin(PointF(300f, 400f))
        }

        fun frame(elapsed: Long = 16L): Boolean {
            val callback = queued ?: return false
            time += elapsed
            queued = null
            callback.run()
            return true
        }

        fun drain(step: Long = 16L) {
            repeat(80) { if (!frame(step)) return }
            fail("Scroll callbacks did not finish")
        }

        fun buttons() = ticks.map { it.button }
    }

    @Test fun normalDistanceSurvivesDifferentBatchSizesAndFrameRates() {
        val batched = Scroll().apply { controller.scroll(0f, 96f); drain() }
        val samples = Scroll().apply {
            repeat(12) { controller.scroll(0f, 8f); frame() }
            drain()
        }
        val fastDisplay = Scroll().apply { controller.scroll(0f, 96f); drain(8L) }
        assertEquals(List(48) { PointerButton.WheelUp }, batched.buttons())
        assertEquals(batched.ticks, samples.ticks)
        assertEquals(batched.ticks, fastDisplay.ticks)
    }

    @Test fun oneTwoAndThreeTimesGainProduceProportionalNormalMotion() {
        val counts = (1..3).map { gain ->
            Scroll(RemoteInputConfig(axisLock = false, inertia = "off", scrollSpeed = gain.toFloat())).apply {
                controller.scroll(0f, 24f)
                controller.end(0f, 0f, false)
                drain()
                assertTrue(buttons().all { it == PointerButton.WheelUp })
            }.ticks.size
        }
        assertEquals(listOf(12, 24, 36), counts)
    }

    @Test fun unsentWholeTicksDrainAcrossFramesInsteadOfDisappearing() {
        val scroll = Scroll()
        scroll.controller.scroll(0f, 64f)
        assertTrue(scroll.frame())
        assertEquals(16, scroll.ticks.size)
        assertNotNull("Whole ticks must schedule another display frame", scroll.queued)
        assertTrue(scroll.frame(8L))
        assertTrue(scroll.ticks.size in 17..20)
        scroll.drain()
        assertEquals(List(32) { PointerButton.WheelUp }, scroll.buttons())
    }

    @Test fun fullNormalBatchDrainsAfterReleaseWithinTheShortTail() {
        val scroll = Scroll()
        scroll.controller.scroll(0f, 96f)
        scroll.controller.end(0f, 0f, false)
        scroll.drain()
        assertEquals(48, scroll.ticks.size)
        assertTrue("Touch displacement must finish within 100ms", scroll.time - 1000L <= 100L)
        assertNull(scroll.queued)
    }

    @Test fun pathologicalBacklogIsBoundedAndStalledReleaseHasNoDelayedBurst() {
        val active = Scroll()
        active.controller.scroll(100000f, 100000f)
        active.drain()
        assertEquals(48, active.ticks.size)
        assertEquals(24, active.buttons().count { it == PointerButton.WheelLeft })
        assertEquals(24, active.buttons().count { it == PointerButton.WheelUp })

        val released = Scroll()
        released.controller.scroll(100000f, 0f)
        released.controller.end(0f, 0f, false)
        released.frame()
        assertEquals(16, released.ticks.size)
        released.frame(1000L)
        assertEquals("A stalled frame must discard stale release motion", 16, released.ticks.size)
        assertNull(released.queued)
    }

    @Test fun reversingDirectionDropsOldBacklogAndRespondsOnTheNextFrame() {
        val scroll = Scroll()
        scroll.controller.scroll(0f, 64f)
        scroll.frame()
        assertEquals(List(16) { PointerButton.WheelUp }, scroll.buttons())
        scroll.controller.scroll(0f, -8f)
        scroll.drain()
        assertEquals(List(16) { PointerButton.WheelUp } + List(4) { PointerButton.WheelDown }, scroll.buttons())
    }

    @Test fun cancelAndReadOnlyTransitionPreventAlreadyPostedCallbacksFromEmitting() {
        val cancelled = Scroll()
        cancelled.controller.scroll(0f, 64f)
        val stale = checkNotNull(cancelled.queued)
        cancelled.controller.cancel()
        stale.run()
        assertTrue(cancelled.ticks.isEmpty())
        assertNull(cancelled.queued)

        val readOnly = Scroll()
        readOnly.controller.scroll(0f, 64f)
        readOnly.acceptsInput = false
        readOnly.frame()
        readOnly.acceptsInput = true
        readOnly.controller.scroll(0f, 64f)
        readOnly.drain()
        assertTrue(readOnly.ticks.isEmpty())
        assertNull(readOnly.queued)
    }

    @Test fun inertiaContinuesAfterLiftAndNewGestureStopsItAtTheNewAnchor() {
        val scroll = Scroll(RemoteInputConfig(axisLock = false, inertia = "standard"))
        scroll.controller.scroll(0f, 2f)
        scroll.frame()
        assertEquals(1, scroll.ticks.size)
        scroll.controller.end(0f, 1000f, false)
        scroll.frame()
        assertTrue("Momentum must add ticks beyond touch displacement", scroll.ticks.size > 1)
        assertNotNull(scroll.queued)
        scroll.controller.begin(PointF(700f, 800f))
        assertNull("New contact must remove the old momentum callback", scroll.queued)
        val previous = scroll.ticks.size
        scroll.frame(1000L)
        assertEquals(previous, scroll.ticks.size)
        scroll.controller.scroll(2f, 0f)
        scroll.drain()
        assertEquals(previous + 1, scroll.ticks.size)
        assertEquals(Tick(PointerButton.WheelLeft, PointF(700f, 800f)), scroll.ticks.last())
    }

    @Test fun fractionsAccumulateWithoutMovingTheAnchorOrStartingDisabledInertia() {
        val scroll = Scroll()
        repeat(3) {
            scroll.controller.scroll(0f, .5f)
            scroll.frame()
            assertTrue(scroll.ticks.isEmpty())
        }
        scroll.controller.scroll(0f, .5f)
        scroll.controller.end(0f, 10000f, false)
        scroll.drain()
        assertEquals(listOf(Tick(PointerButton.WheelUp, PointF(300f, 400f))), scroll.ticks)
        assertNull(scroll.queued)
    }

    @Test fun axisLockStaysStableUntilNewContactAndClearsTheMinorAxis() {
        val scroll = Scroll(RemoteInputConfig(axisLock = true, inertia = "off"))
        scroll.controller.scroll(4f, 24f)
        scroll.drain()
        assertEquals(List(12) { PointerButton.WheelUp }, scroll.buttons())
        scroll.controller.scroll(24f, 0f)
        scroll.drain()
        assertEquals(12, scroll.ticks.size)
        scroll.controller.begin(PointF(300f, 400f))
        scroll.controller.scroll(24f, 0f)
        scroll.drain()
        assertEquals(List(12) { PointerButton.WheelUp } + List(12) { PointerButton.WheelLeft }, scroll.buttons())
    }
}
