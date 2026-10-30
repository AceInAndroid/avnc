/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.graphics.PointF
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayFrameStateTest {
    private fun state() = FrameState().apply {
        setWindowSize(200f, 100f)
        setViewportSize(200f, 100f)
        setFramebufferSize(600f, 200f)
        setContentRect(RectF(200f, 50f, 400f, 150f))
    }

    private fun assertPoint(x: Float, y: Float, point: PointF?) {
        assertNotNull(point)
        assertEquals(x, point!!.x, .01f)
        assertEquals(y, point.y, .01f)
    }

    @Test fun selectedDisplayFitsUsingItsOwnSizeAndGlobalOrigin() {
        val state = state()
        assertEquals(1f, state.baseScale, 0f)
        assertPoint(200f, 50f, state.toFb(PointF(0f, 0f)))
        assertPoint(399f, 149f, state.toFb(PointF(199f, 99f)))
        assertPoint(0f, 0f, state.toVP(PointF(200f, 50f)))
        assertPoint(100f, 50f, state.toVP(PointF(300f, 100f)))
    }

    @Test fun zoomAndPanCannotExposeAdjacentDisplay() {
        val state = state()
        state.updateZoom(2f, 100f, 50f)
        state.pan(10000f, 10000f)
        assertPoint(200f, 50f, state.toFb(PointF(0f, 0f)))
        state.pan(-10000f, -10000f)
        assertPoint(400f, 150f, state.toFbUnchecked(PointF(200f, 100f)))
        assertNull(state.toFb(PointF(200f, 100f)))
    }

    @Test fun letterboxAndPixelsOutsideSelectedDisplayCannotBeClicked() {
        val state = state()
        state.setZoom(.5f, .5f)
        assertNull(state.toFb(PointF(0f, 0f)))
        assertPoint(200f, 50f, state.toFb(PointF(50f, 25f)))
        assertNull(state.toFb(PointF(150f, 75f)))
        assertNull(state.toFb(PointF(49f, 25f)))
    }

    @Test fun resettingRegionRestoresFullDesktopAndGlobalCoordinates() {
        val state = state()
        state.setContentRect(null)
        assertEquals(RectF(0f, 0f, 600f, 200f), state.contentRect)
        val point = PointF(525f, 125f)
        assertPoint(point.x, point.y, state.toFb(state.toVP(point)))
    }

    @Test fun selectedRegionAndSnapshotDoNotShareMutableRectangles() {
        val state = state()
        val expected = RectF(200f, 50f, 400f, 150f)
        state.contentRect.setEmpty()
        assertEquals(expected, state.contentRect)
        val snapshot = state.getSnapshot()
        snapshot.contentRect.setEmpty()
        assertEquals(expected, state.contentRect)
    }

    @Test fun keyboardViewportChangesKeepGlobalMappingAndSelectedBounds() {
        val state = state()
        state.setViewportSize(200f, 60f)
        state.setSafeArea(RectF(0f, 0f, 200f, 60f))
        assertEquals(RectF(200f, 50f, 400f, 150f), state.contentRect)
        val point = state.toFb(PointF(100f, 30f))!!
        assertTrue(point.x >= 200f && point.x < 400f)
        assertTrue(point.y >= 50f && point.y < 150f)
        assertPoint(100f, 30f, state.toVP(point))
        assertEquals(1f, state.baseScale, 0f)
    }
}
