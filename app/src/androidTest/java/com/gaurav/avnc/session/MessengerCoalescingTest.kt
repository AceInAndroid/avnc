/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.session

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.VncClient
import com.gaurav.avnc.vnc.VncClientTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MessengerCoalescingTest {
    @Test fun onlyConsecutiveUnsentButtonlessMovesMergeAcrossNoInputBarriers() {
        val server = TestServer(width = 400, height = 100)
        val client = VncClient(VncClientTest.TestObserver())
        val unblock = CountDownLatch(1)
        var messenger: Messenger? = null
        try {
            server.start()
            client.connect(server.host, server.port)
            client.processServerMessage()
            val sender = Messenger(client).also { messenger = it }
            val executor = Messenger::class.java.getDeclaredField("sender").apply { isAccessible = true }
                    .get(sender) as ExecutorService
            val blocked = CountDownLatch(1)
            executor.execute { blocked.countDown(); unblock.await(5, TimeUnit.SECONDS) }
            assertTrue("Sender barrier did not start", blocked.await(2, TimeUnit.SECONDS))

            for (x in 1..100) sender.sendPointerMove(PointF(x.toFloat(), 1f))
            assertTrue(sender.sendKey('a'.code, 0, true))
            for (x in 101..200) sender.sendPointerMove(PointF(x.toFloat(), 2f))
            sender.sendPointerButtonDown(PointerButton.Left, PointF(201f, 3f))
            // Drag samples, button-up and key-up must never be dropped or reordered.
            sender.sendPointerMove(PointF(202f, 4f))
            sender.sendPointerMove(PointF(203f, 5f))
            sender.sendPointerButtonUp(PointerButton.Left, PointF(203f, 5f))
            for (x in 204..300) sender.sendPointerMove(PointF(x.toFloat(), 6f))
            assertTrue(sender.sendKey('a'.code, 0, false))
            unblock.countDown()

            pollingAssert {
                assertEquals(listOf("pointer:0:100:1", "key:1:97", "pointer:0:200:2",
                        "pointer:1:201:3", "pointer:1:202:4", "pointer:1:203:5",
                        "pointer:0:203:5", "pointer:0:300:6", "key:0:97"), server.receivedInputEvents.toList())
            }
            assertEquals(0, server.receivedPointerEvents.last().mask)
        } finally {
            unblock.countDown()
            messenger?.shutdown()
            client.cleanup()
            server.stop()
        }
    }
}
