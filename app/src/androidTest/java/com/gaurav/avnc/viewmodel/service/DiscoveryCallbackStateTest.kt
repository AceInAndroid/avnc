/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.viewmodel.service

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Control NSD callbacks directly to cover races without depending on a Wi-Fi network. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class DiscoveryCallbackStateTest {
    private class Scan(private val resolutionDelayMs: Long = 10_000L,
                       private val cancellationSupported: Boolean = true,
                       private val stopResolutionDelayMs: Long = 1_000L) : AutoCloseable {
        val nsd = mockk<NsdManager>()
        val wifi = mockk<WifiManager>()
        val lock = mockk<WifiManager.MulticastLock>(relaxed = true)
        val starts = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()
        val stops = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()
        val resolves = CopyOnWriteArrayList<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
        val cancelledResolves = CopyOnWriteArrayList<NsdManager.ResolveListener>()
        val phases = CopyOnWriteArrayList<Discovery.UiState>()
        val profiles = AtomicReference<List<ServerProfile>>(emptyList())
        val running = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadScheduledExecutor()
        val engine: Discovery.Impl
        init {
            every { nsd.discoverServices(any<String>(), any<Int>(), any<NsdManager.DiscoveryListener>()) } answers {
                starts += thirdArg<NsdManager.DiscoveryListener>()
            }
            every { nsd.stopServiceDiscovery(any()) } answers { stops += firstArg<NsdManager.DiscoveryListener>() }
            every { nsd.resolveService(any(), any()) } answers {
                resolves += firstArg<NsdServiceInfo>() to secondArg<NsdManager.ResolveListener>()
            }
            every { wifi.createMulticastLock(any()) } returns lock
            every { lock.isHeld } returns true
            engine = Discovery.Impl(nsd, wifi, executor, 30L,
                    { profiles.set(it) }, { running.set(it) }, { phases += it },
                    resolutionDelayMs, stopResolutionDelayMs, { _, listener ->
                        if (cancellationSupported) cancelledResolves += listener
                        cancellationSupported
                    })
        }
        fun drain() { executor.submit {}.get(2, TimeUnit.SECONDS) }
        fun start(): NsdManager.DiscoveryListener {
            val count = starts.size
            engine.start(targetContext)
            pollingAssert { assertEquals(count + 1, starts.size) }
            return starts.last()
        }
        fun phase() = phases.last().phase
        override fun close() { engine.stop(); drain(); executor.shutdownNow() }
    }

    private fun service(name: String, host: String = "192.0.2.7", port: Int = 5900) = NsdServiceInfo().apply {
        serviceName = name
        serviceType = "_rfb._tcp."
        this.host = InetAddress.getByName(host)
        this.port = port
    }

    @Test fun pendingStartCanStopAndOldStartStopCallbacksCannotChangeNewRun() {
        Scan().use { scan ->
            val old = scan.start()
            assertTrue(scan.running.get())
            assertEquals(Discovery.Phase.STARTING, scan.phase())
            scan.engine.stop(); scan.drain()
            assertFalse(scan.running.get())
            assertEquals(Discovery.Phase.STOPPED, scan.phase())
            old.onDiscoveryStarted("_rfb._tcp"); scan.drain()
            verify(exactly = 0) { scan.lock.acquire() }
            val current = scan.start()
            old.onDiscoveryStopped("_rfb._tcp"); scan.drain()
            assertTrue(scan.running.get())
            assertEquals(Discovery.Phase.STARTING, scan.phase())
            current.onDiscoveryStarted("_rfb._tcp"); scan.drain()
            verify(exactly = 1) { scan.lock.acquire() }
            scan.engine.stop(); scan.drain()
            verify(exactly = 1) { scan.lock.release() }
            assertFalse(scan.running.get())
        }
    }

    @Test fun stoppedRunResolutionCannotRepopulateAndNewRunWaitsForCancellation() {
        Scan().use { scan ->
            val old = scan.start()
            old.onDiscoveryStarted("_rfb._tcp")
            old.onServiceFound(service("Old")); scan.drain()
            assertEquals(1, scan.resolves.size)
            val oldResolution = scan.resolves.single().second
            scan.engine.stop(); scan.drain()
            assertEquals(listOf(oldResolution), scan.cancelledResolves)
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            current.onServiceFound(service("New", "192.0.2.8")); scan.drain()
            assertEquals("The older physical NSD stop is still pending", 1, scan.resolves.size)
            oldResolution.onServiceResolved(service("Old")); scan.drain()
            assertTrue(scan.profiles.get().isEmpty())
            assertEquals(2, scan.resolves.size)
            scan.resolves.last().second.onServiceResolved(service("New", "192.0.2.8")); scan.drain()
            old.onServiceFound(service("Late old callback")); scan.drain()
            assertEquals(listOf("New"), scan.profiles.get().map { it.name })
            assertEquals("192.0.2.8", scan.profiles.get().single().host)
            assertEquals(Discovery.Phase.RESULTS, scan.phase())
        }
    }

    @Test fun modernTimeoutCancelsPhysicalLookupRejectsStaleResultAndAllowsSameServiceRetry() {
        Scan(resolutionDelayMs = 300L).use { scan ->
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            current.onServiceFound(service("Computer")); scan.drain()
            val expired = scan.resolves.single().second
            pollingAssert { assertEquals(listOf(expired), scan.cancelledResolves) }
            assertEquals(Discovery.Phase.ERROR, scan.phase())
            assertTrue(scan.phases.last().error!!.contains("timed out"))
            assertTrue(scan.running.get())
            current.onServiceFound(service("Computer", "192.0.2.8")); scan.drain()
            assertEquals("Do not overlap physical requests before stop acknowledgement", 1, scan.resolves.size)
            expired.onResolutionStopped(service("Computer")); scan.drain()
            assertEquals(2, scan.resolves.size)
            expired.onServiceResolved(service("Computer")); scan.drain()
            assertTrue(scan.profiles.get().isEmpty())
            scan.resolves.last().second.onServiceResolved(service("Computer", "192.0.2.8")); scan.drain()
            assertEquals("192.0.2.8", scan.profiles.get().single().host)
            assertEquals(Discovery.Phase.RESULTS, scan.phase())
        }
    }

    @Test fun missingCancellationCallbackDoesNotStarveANewerRun() {
        Scan(stopResolutionDelayMs = 150L).use { scan ->
            val old = scan.start()
            old.onDiscoveryStarted("_rfb._tcp")
            old.onServiceFound(service("Old")); scan.drain()
            val abandoned = scan.resolves.single().second
            scan.engine.stop(); scan.drain()
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            current.onServiceFound(service("New", "192.0.2.8")); scan.drain()
            pollingAssert { assertEquals(2, scan.resolves.size) }
            abandoned.onResolutionStopped(service("Old"))
            abandoned.onServiceResolved(service("Old")); scan.drain()
            assertTrue(scan.profiles.get().isEmpty())
            scan.resolves.last().second.onServiceResolved(service("New", "192.0.2.8")); scan.drain()
            assertEquals(listOf("New"), scan.profiles.get().map { it.name })
        }
    }

    @Test fun legacyTimeoutReleasesLocalSlotAndBusySystemFailureRemainsRetryable() {
        Scan(resolutionDelayMs = 300L, cancellationSupported = false).use { scan ->
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            current.onServiceFound(service("Computer")); scan.drain()
            val abandoned = scan.resolves.single().second
            pollingAssert { assertEquals(Discovery.Phase.ERROR, scan.phase()) }
            assertTrue(scan.phases.last().error!!.contains("timed out"))
            assertTrue(scan.cancelledResolves.isEmpty())
            current.onServiceFound(service("Computer")); scan.drain()
            assertEquals(2, scan.resolves.size)
            scan.resolves.last().second.onResolveFailed(service("Computer"), NsdManager.FAILURE_ALREADY_ACTIVE)
            scan.drain()
            assertEquals(Discovery.Phase.ERROR, scan.phase())
            assertTrue(scan.phases.last().error!!.contains("still busy"))
            assertTrue(scan.profiles.get().isEmpty())
            abandoned.onServiceResolved(service("Computer")); scan.drain()
            assertTrue(scan.profiles.get().isEmpty())
            current.onServiceFound(service("Computer", "192.0.2.8")); scan.drain()
            assertEquals(3, scan.resolves.size)
            scan.resolves.last().second.onServiceResolved(service("Computer", "192.0.2.8")); scan.drain()
            assertEquals("192.0.2.8", scan.profiles.get().single().host)
            assertEquals(Discovery.Phase.RESULTS, scan.phase())
        }
    }

    @Test fun legacyStopAllowsFreshRunWithoutWaitingForMissingOldCallback() {
        Scan(cancellationSupported = false).use { scan ->
            val old = scan.start()
            old.onDiscoveryStarted("_rfb._tcp")
            old.onServiceFound(service("Old")); scan.drain()
            val abandoned = scan.resolves.single().second
            scan.engine.stop(); scan.drain()
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            current.onServiceFound(service("New", "192.0.2.8")); scan.drain()
            assertEquals(2, scan.resolves.size)
            abandoned.onServiceResolved(service("Old")); scan.drain()
            assertTrue(scan.profiles.get().isEmpty())
            scan.resolves.last().second.onServiceResolved(service("New", "192.0.2.8")); scan.drain()
            assertEquals(listOf("New"), scan.profiles.get().map { it.name })
            assertTrue(scan.cancelledResolves.isEmpty())
        }
    }

    @Test fun emptyTimeoutKeepsDiscoveryAliveAndLostRefoundServiceRejectsOldResolveTicket() {
        Scan().use { scan ->
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            pollingAssert { assertEquals(Discovery.Phase.EMPTY, scan.phase()) }
            assertTrue(scan.running.get())
            current.onServiceFound(service("Computer", "192.0.2.1")); scan.drain()
            current.onServiceLost(service("Computer"))
            current.onServiceFound(service("Computer", "192.0.2.2")); scan.drain()
            scan.resolves.first().second.onServiceResolved(service("Computer", "192.0.2.1")); scan.drain()
            assertTrue(scan.profiles.get().isEmpty())
            assertEquals(2, scan.resolves.size)
            scan.resolves.last().second.onServiceResolved(service("Computer", "192.0.2.2")); scan.drain()
            assertEquals("192.0.2.2", scan.profiles.get().single().host)
            assertEquals(Discovery.Phase.RESULTS, scan.phase())
            current.onServiceLost(service("Computer")); scan.drain()
            assertEquals(Discovery.Phase.EMPTY, scan.phase())
            assertTrue(scan.running.get())
        }
    }

    @Test fun resolveExceptionAndCallbackFailureAreVisibleAndDoNotBlockLaterResults() {
        Scan().use { scan ->
            val current = scan.start()
            current.onDiscoveryStarted("_rfb._tcp")
            every { scan.nsd.resolveService(match { it.serviceName == "Broken" }, any()) } throws IllegalStateException("Resolver unavailable")
            current.onServiceFound(service("Broken")); scan.drain()
            assertEquals(Discovery.Phase.ERROR, scan.phase())
            assertEquals("Resolver unavailable", scan.phases.last().error)
            current.onServiceFound(service("Failed")); scan.drain()
            scan.resolves.last().second.onResolveFailed(service("Failed"), 3); scan.drain()
            assertEquals(Discovery.Phase.ERROR, scan.phase())
            assertTrue(scan.phases.last().error!!.contains("3"))
            current.onServiceFound(service("Healthy")); scan.drain()
            scan.resolves.last().second.onServiceResolved(service("Healthy")); scan.drain()
            assertEquals(Discovery.Phase.RESULTS, scan.phase())
            assertEquals(listOf("Healthy"), scan.profiles.get().map { it.name })
        }
    }
}
