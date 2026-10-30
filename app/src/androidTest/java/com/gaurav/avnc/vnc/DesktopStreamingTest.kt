/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.vnc

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.pollingAssert
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the JNI transport against actual RFB messages, without a real Mac. */
@RunWith(AndroidJUnit4::class)
class DesktopStreamingTest {
    private lateinit var server: TestServer
    private lateinit var client: VncClient
    private val layouts = CopyOnWriteArrayList<Triple<Int, Int, List<Int>>>()
    private var framebufferSize = 0 to 0
    private val presets = CopyOnWriteArrayList<Pair<String, Boolean>>()

    @Before fun connect() {
        server = TestServer(width = 200, height = 100)
        client = VncClient(object : VncClientTest.TestObserver() {
            override fun onFramebufferSizeChanged(width: Int, height: Int) {
                framebufferSize = width to height
            }
            override fun onDisplayLayoutChanged(width: Int, height: Int, screens: IntArray) {
                layouts.add(Triple(width, height, screens.toList()))
            }
            override fun onStreamingPresetApplied(preset: String, success: Boolean) {
                presets.add(preset to success)
            }
        })
        server.start()
        client.connect(server.host, server.port)
        repeat(3) { client.processServerMessage() }
        pollingAssert { assertTrue(server.receivedEncodingLists.isNotEmpty()) }
    }

    @After fun disconnect() {
        client.cleanup()
        server.stop()
    }

    private fun drainUntil(condition: () -> Boolean) {
        repeat(30) {
            if (condition()) return
            client.processServerMessage()
        }
        assertTrue("Expected transport condition was not observed", condition())
    }

    @Test fun completeExtendedDesktopSizeContainsBothDisplaysAndUnsignedIdBits() {
        val screens = intArrayOf(7, 0, 0, 100, 100, -1, 100, 0, 100, 100)
        server.sendDisplayLayout(200, 100, screens)
        drainUntil { layouts.any { it.third == screens.toList() } }
        assertEquals(Triple(200, 100, screens.toList()), layouts.last())
    }

    @Test fun duplicateAndOutOfBoundsScreensInvalidateLayoutWithoutDisconnect() {
        server.sendDisplayLayout(200, 100, intArrayOf(7, 0, 0, 100, 100, 7, 100, 0, 100, 100))
        drainUntil { layouts.isNotEmpty() }
        assertTrue(layouts.last().third.isEmpty())
        layouts.clear()
        server.sendDisplayLayout(200, 100, intArrayOf(7, 199, 0, 2, 100))
        drainUntil { layouts.isNotEmpty() }
        assertTrue(layouts.last().third.isEmpty())
        assertTrue(client.connected)
    }

    @Test fun croppedRegionRequestsCorrectGlobalRectangleAndDisablesCopyRect() {
        val previousEncodings = server.receivedEncodingLists.size
        client.setDesktopRegion(100, 0, 100, 100)
        drainUntil { server.receivedUpdateRequests.any { !it.incremental && it.x == 100 && it.width == 100 } }
        pollingAssert { assertTrue(server.receivedEncodingLists.size > previousEncodings) }
        assertFalse(server.receivedEncodingLists.last().contains(1)) // CopyRect
        val request = server.receivedUpdateRequests.last { !it.incremental }
        assertEquals(TestServer.UpdateRequest(false, 100, 0, 100, 100), request)
        server.receivedUpdateRequests.clear()
        client.setDesktopRegion(0, 0, 0, 0)
        drainUntil { server.receivedUpdateRequests.any { !it.incremental && it.width == 200 } }
        assertEquals(TestServer.UpdateRequest(false, 0, 0, 200, 100), server.receivedUpdateRequests.last { !it.incremental })
    }

    @Test fun smoothAndHdSwitchOnLiveConnectionWithCorrectEncodingPriority() {
        assertTrue(client.setStreamingPreset("smooth"))
        drainUntil { presets.contains("smooth" to true) && server.receivedEncodingLists.lastOrNull()?.contains(-29) == true }
        val smooth = server.receivedEncodingLists.last()
        assertTrue(smooth.indexOf(7) >= 0 && smooth.indexOf(7) < smooth.indexOf(16)) // Tight before ZRLE
        assertTrue(smooth.contains(-255)) // Compression 1
        assertTrue(smooth.contains(-29)) // JPEG quality 3
        assertTrue(smooth.contains(5)) // Hextile fallback
        assertTrue(smooth.contains(0)) // Raw fallback
        assertTrue(client.setStreamingPreset("hd"))
        drainUntil { presets.contains("hd" to true) && server.receivedEncodingLists.lastOrNull()?.contains(-23) == true }
        val hd = server.receivedEncodingLists.last()
        assertTrue(hd.indexOf(16) >= 0 && hd.indexOf(16) < hd.indexOf(7))
        assertTrue(hd.contains(-23)) // JPEG quality 9 for Tight fallback
        assertTrue(client.connected)
        assertEquals(200 to 100, framebufferSize)
    }

    @Test fun invalidStreamingPresetDoesNotNegotiate() {
        val before = server.receivedEncodingLists.size
        assertFalse(client.setStreamingPreset("automatic"))
        repeat(2) { client.processServerMessage() }
        assertEquals(before, server.receivedEncodingLists.size)
        assertTrue(presets.isEmpty())
    }
}
