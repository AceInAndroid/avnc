/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc.input

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.VncSessionScenario
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.ui.vnc.VncActivity
import com.gaurav.avnc.vnc.PointerButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacPointerResponseTest {
    @JvmField @Rule val prefs = CleanPrefsRule()
    private val field = VncActivity::class.java.getDeclaredField("inputHandler").apply { isAccessible = true }

    @Test fun fastMacPointerKeepsDirectionAndHeldButtonDragStaysUnaccelerated() {
        val session = VncSessionScenario(TestServer(width = 2000, height = 1500))
        session.profile.remotePlatform = "mac"
        session.run {
            session.onActivity { activity ->
                assertTrue(activity.viewModel.remoteInputConfig.acceleration)
                val input = field.get(activity) as InputHandler
                input.onVirtualPointerStart()
                val now = SystemClock.uptimeMillis()
                val density = activity.resources.displayMetrics.density
                for ((time, action, x, y) in listOf(
                        listOf(0f, MotionEvent.ACTION_DOWN.toFloat(), 0f, 0f),
                        listOf(16f, MotionEvent.ACTION_MOVE.toFloat(), 40f * density, 20f * density))) {
                    MotionEvent.obtain(now, now + time.toLong(), action.toInt(), x, y, 0).let {
                        input.onVirtualPointerMotion(it); it.recycle()
                    }
                }
                input.onVirtualPointerMove(40f, 20f)
            }
            pollingAssert {
                val event = session.server.receivedPointerEvents.last()
                assertEquals(0, event.mask)
                assertTrue("Fast motion should gain distance", event.x > 120)
                assertEquals("A single vector gain must preserve the 2:1 slope", event.x.toFloat(), 2f * event.y, 2f)
            }
            val before = session.server.receivedPointerEvents.last()
            session.onActivity { activity ->
                val input = field.get(activity) as InputHandler
                input.onVirtualPointerButton(PointerButton.Left, true)
                input.onVirtualPointerMove(40f, 20f)
                input.onVirtualPointerButton(PointerButton.Left, false)
                input.onVirtualPointerStop()
            }
            pollingAssert {
                val after = session.server.receivedPointerEvents.last()
                assertEquals(0, after.mask)
                assertEquals(120, after.x - before.x)
                assertEquals(60, after.y - before.y)
                assertTrue(session.server.receivedPointerEvents.any { it.mask == 1 && it.x == after.x && it.y == after.y })
            }
        }
    }
}
