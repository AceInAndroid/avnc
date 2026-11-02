/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.session

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.vnc.VncClient
import com.gaurav.avnc.vnc.VncClientTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real peers deliberately stall at protocol boundaries, without changing the device's network. */
@RunWith(AndroidJUnit4::class)
class ConnectionReliabilityTest {
    private class Peer(private val partialHandshake: Boolean = false, private val tls: Boolean = false) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = listener.localPort
        val ready = CountDownLatch(1)
        val socket = AtomicReference<Socket?>()
        private val finished = CountDownLatch(1)
        private val worker = thread(name = "stalled-rfb-peer", isDaemon = true) {
            try {
                listener.accept().use { peer ->
                    socket.set(peer)
                    peer.receiveBufferSize = 1024
                    peer.soTimeout = 5000
                    val out = DataOutputStream(peer.getOutputStream())
                    val input = DataInputStream(peer.getInputStream())
                    if (partialHandshake) out.writeBytes("RFB 003.")
                    else {
                        out.writeBytes("RFB 003.008\n")
                        input.readFully(ByteArray(12))
                        out.write(byteArrayOf(1, if (tls) 19 else 1))
                        check(input.readUnsignedByte() == if (tls) 19 else 1)
                        if (tls) {
                            out.write(byteArrayOf(0, 2)) // VeNCrypt 0.2
                            check(input.readUnsignedShort() == 2)
                            out.writeByte(0) // Version accepted
                            out.writeByte(1); out.writeInt(257) // TLSNone
                            check(input.readInt() == 257)
                            out.writeByte(1) // Start TLS, then deliberately never answer ClientHello.
                            out.flush()
                            input.readFully(ByteArray(5))
                        } else {
                            out.writeInt(0)
                            input.readUnsignedByte()
                            out.writeShort(16); out.writeShort(16)
                            out.write(byteArrayOf(32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
                            out.writeInt(4); out.writeBytes("Test")
                        }
                    }
                    out.flush()
                    ready.countDown()
                    // Do not read any client output: enough clipboard data will fill its send buffer.
                    finished.await(20, TimeUnit.SECONDS)
                }
            } catch (_: IOException) {
                // Test cleanup can interrupt accept, handshake or writes.
            }
        }

        fun sendPartialMessage() {
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            // Clipboard message type followed by only one of its three padding bytes.
            socket.get()!!.getOutputStream().apply { write(byteArrayOf(3, 0)); flush() }
        }

        fun resetConnection() {
            socket.get()!!.apply { setSoLinger(true, 0); close() }
        }

        override fun close() {
            finished.countDown()
            runCatching { socket.get()?.close() }
            listener.close()
            worker.join(1000)
        }
    }

    private fun exerciseStall(handshake: Boolean, cancel: Boolean, write: Boolean = false, tls: Boolean = false) {
        Peer(partialHandshake = handshake && !tls, tls = tls).use { peer ->
            val client = VncClient(VncClientTest.TestObserver())
            client.configureTimeouts(connectSeconds = 2, readSeconds = if (cancel) 15 else 1,
                    writeSeconds = if (cancel) 15 else 1)
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            val worker = thread(name = "stalled-vnc-client", isDaemon = true) {
                try {
                    client.connect("127.0.0.1", peer.port)
                    if (!handshake) {
                        if (!write) peer.sendPartialMessage()
                        entered.countDown()
                        if (write) client.sendCutText("x".repeat(8 * 1024 * 1024))
                        else client.processServerMessage()
                    }
                } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }
            try {
                assertTrue("Peer handshake did not start", peer.ready.await(3, TimeUnit.SECONDS))
                if (!handshake) assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (cancel) {
                    assertFalse("Operation did not actually stall", done.await(200, TimeUnit.MILLISECONDS))
                    val start = SystemClock.elapsedRealtime()
                    instrumentation.runOnMainSync { client.cancel() }
                    assertTrue("Cancel blocked the UI", SystemClock.elapsedRealtime() - start < 1000)
                    assertTrue("Cancel did not unblock I/O", done.await(2, TimeUnit.SECONDS))
                } else {
                    assertTrue("Stalled I/O exceeded its configured timeout", done.await(4, TimeUnit.SECONDS))
                    assertTrue("Expected a timeout, got ${failure.get()}",
                            failure.get()?.message?.contains("timed out", ignoreCase = true) == true)
                }
                assertTrue("Expected IOException, got ${failure.get()}", failure.get() is IOException)
                if (cancel) assertFalse(client.connected)
            } finally {
                client.cancel()
                peer.close()
                worker.join(2000)
                assertFalse("Connection worker leaked", worker.isAlive)
                client.cleanup()
            }
        }
    }

    @Test(timeout = 10_000) fun partialHandshakeCanBeCancelled() = exerciseStall(true, true)
    @Test(timeout = 10_000) fun partialHandshakeTimesOut() = exerciseStall(true, false)
    @Test(timeout = 10_000) fun silentTlsHandshakeTimesOutWithoutCrashing() = exerciseStall(true, false, tls = true)
    @Test(timeout = 10_000) fun silentTlsHandshakeCanBeCancelledWithoutCrashing() = exerciseStall(true, true, tls = true)
    @Test(timeout = 10_000) fun partialMessageCanBeCancelled() = exerciseStall(false, true)
    @Test(timeout = 10_000) fun partialMessageTimesOut() = exerciseStall(false, false)
    @Test(timeout = 12_000) fun stalledWriteCanBeCancelled() = exerciseStall(false, true, true)
    @Test(timeout = 12_000) fun stalledWriteTimesOut() = exerciseStall(false, false, true)

    @Test(timeout = 10_000) fun idleDesktopDoesNotTripReadTimeout() {
        Peer().use { peer ->
            val client = VncClient(VncClientTest.TestObserver())
            try {
                client.configureTimeouts(readSeconds = 1)
                client.connect("127.0.0.1", peer.port)
                val until = SystemClock.elapsedRealtime() + 1500
                while (SystemClock.elapsedRealtime() < until) client.processServerMessage()
                assertTrue(client.connected)
            } finally { client.cleanup() }
        }
    }

    @Test(timeout = 10_000) fun cancelledClientCannotBeConnectedAgainOrRevivedByCleanup() {
        val client = VncClient(VncClientTest.TestObserver())
        client.cancel()
        assertTrue(runCatching { client.connect("127.0.0.1", 5900) }.exceptionOrNull() is IOException)
        client.cleanup()
        client.cancel() // Repeated cancellation after free must not access the native pointer.
        client.cleanup()
        assertFalse(client.connected)
    }

    private class SessionObserver : VncClientTest.TestObserver(), RemoteSession.Observer, Closeable {
        val disconnected = CountDownLatch(1)
        val connections = AtomicInteger()
        val errors = AtomicInteger()
        var throwOnError = false
        private val hosts = File.createTempFile("session-hosts", ".txt", targetContext.cacheDir)
        override fun onConnecting() {}
        override fun onConnected(vncClient: VncClient, messenger: Messenger) { connections.incrementAndGet() }
        override fun onDisconnected() { disconnected.countDown() }
        override fun onConnectionError(error: Throwable) {
            errors.incrementAndGet()
            if (throwOnError) throw IllegalStateException("broken observer")
        }
        override fun onWakeOnLanBroadcastError(e: Throwable) {}
        override fun getKnownSshHostsFile() = hosts
        override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean) = false
        override fun getSshPassword() = "unused"
        override fun getSshKeyPassword() = "unused"
        override fun close() { hosts.delete() }
    }

    @Test(timeout = 10_000) fun sessionStopCancelsHandshakeWithoutReportingUserCancellationAsAnError() {
        Peer(partialHandshake = true).use { peer ->
            SessionObserver().use { observer ->
                val session = RemoteSession(observer)
                try {
                    session.start(ServerProfile(host = "127.0.0.1", port = peer.port))
                    assertTrue(peer.ready.await(3, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync { session.stop() }
                    assertTrue("Session failed to finish cancellation", observer.disconnected.await(2, TimeUnit.SECONDS))
                    assertEquals(0, observer.connections.get())
                    assertEquals(0, observer.errors.get())
                } finally { session.stop() }
            }
        }
    }

    @Test(timeout = 10_000) fun brokenErrorObserverDoesNotSuppressDisconnectedOrCleanup() {
        SessionObserver().use { observer ->
            observer.throwOnError = true
            val session = RemoteSession(observer)
            session.start(ServerProfile(channelType = 12345))
            assertTrue(observer.disconnected.await(3, TimeUnit.SECONDS))
            assertEquals(1, observer.errors.get())
            assertEquals(0, observer.connections.get())
            session.stop()
        }
    }

    @Test(timeout = 10_000) fun fullSendQueueFailsOnceAndDoesNotReplayQueuedKeys() {
        Peer().use { peer ->
            val client = VncClient(VncClientTest.TestObserver())
            val errors = AtomicInteger()
            val release = CountDownLatch(1)
            var messenger: Messenger? = null
            try {
                client.connect("127.0.0.1", peer.port)
                val sender = Messenger(client) { errors.incrementAndGet() }.also { messenger = it }
                val executor = Messenger::class.java.getDeclaredField("sender").apply { isAccessible = true }
                        .get(sender) as ExecutorService
                val blocked = CountDownLatch(1)
                executor.execute { blocked.countDown(); runCatching { release.await() } }
                assertTrue(blocked.await(2, TimeUnit.SECONDS))
                repeat(600) { sender.sendKey('a'.code, 0, it % 2 == 0) }
                assertEquals(1, errors.get())
                assertFalse(client.connected)
                assertFalse(sender.sendKey('a'.code, 0, false))
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            } finally { release.countDown(); messenger?.shutdown(); client.cleanup() }
        }
    }

    @Test(timeout = 10_000) fun writeFailureNotifiesMessengerWithoutAReceiverRunning() {
        Peer().use { peer ->
            val client = VncClient(VncClientTest.TestObserver())
            val failure = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            var messenger: Messenger? = null
            try {
                client.connect("127.0.0.1", peer.port)
                assertTrue(peer.ready.await(2, TimeUnit.SECONDS))
                peer.resetConnection()
                val sender = Messenger(client) { error.set(it); failure.countDown() }.also { messenger = it }
                sender.sendKey('a'.code, 0, true)
                assertTrue("Sender failure was ignored", failure.await(3, TimeUnit.SECONDS))
                assertTrue(error.get() is IOException)
                assertFalse(client.connected)
            } finally { messenger?.shutdown(); client.cleanup() }
        }
    }

    @Test(timeout = 10_000) fun staleQueuedInputFailsInsteadOfBeingReplayed() {
        Peer().use { peer ->
            val client = VncClient(VncClientTest.TestObserver())
            val failure = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            val release = CountDownLatch(1)
            var messenger: Messenger? = null
            try {
                client.connect("127.0.0.1", peer.port)
                val sender = Messenger(client) { error.set(it); failure.countDown() }.also { messenger = it }
                val executor = Messenger::class.java.getDeclaredField("sender").apply { isAccessible = true }
                        .get(sender) as ExecutorService
                val blocked = CountDownLatch(1)
                executor.execute { blocked.countDown(); runCatching { release.await() } }
                assertTrue(blocked.await(2, TimeUnit.SECONDS))
                assertTrue(sender.sendKey('a'.code, 0, true))
                assertFalse(failure.await(2200, TimeUnit.MILLISECONDS))
                release.countDown()
                assertTrue(failure.await(2, TimeUnit.SECONDS))
                assertTrue(error.get()?.message.orEmpty().contains("queue stalled"))
                assertFalse(client.connected)
            } finally { release.countDown(); messenger?.shutdown(); client.cleanup() }
        }
    }
}
