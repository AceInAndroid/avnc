/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.viewmodel.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.MulticastLock
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.gaurav.avnc.model.ServerProfile
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Discovers VNC services. Every NSD run owns its listeners and resolve queue. */
object Discovery {
    private const val TAG = "VncServiceDiscovery"
    enum class Phase { IDLE, STARTING, SEARCHING, RESULTS, EMPTY, STOPPED, ERROR }
    data class UiState(val phase: Phase, val error: String? = null)

    val servers = MutableLiveData<List<ServerProfile>>(emptyList())
    /** Includes a pending start so leaving Home also stops an in-flight discovery request. */
    val isRunning = MutableLiveData(false)
    private val mutableState = MutableLiveData(UiState(Phase.IDLE))
    val state: LiveData<UiState> = mutableState
    private val impl by lazy { Impl() }
    fun start(context: Context) = impl.start(context)
    fun stop() = impl.stop()

    /** Dependencies and timeout are injectable for callback/race tests without network discovery. */
    @Suppress("DEPRECATION")
    internal class Impl(
        private val nsdOverride: NsdManager? = null,
        private val wifiOverride: WifiManager? = null,
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
        private val emptyDelayMs: Long = 10_000L,
        private val updateServers: (List<ServerProfile>) -> Unit = { servers.postValue(it) },
        private val updateRunning: (Boolean) -> Unit = { isRunning.postValue(it) },
        private val updateState: (UiState) -> Unit = { mutableState.postValue(it) },
        private val resolutionDelayMs: Long = 10_000L,
        private val stopResolutionDelayMs: Long = 1_000L,
        private val stopResolution: (NsdManager, NsdManager.ResolveListener) -> Boolean = { manager, listener ->
            if (Build.VERSION.SDK_INT >= 34) {
                manager.stopServiceResolution(listener)
                true
            } else false
        },
    ) {
        private val serviceType = "_rfb._tcp"
        private var nsdManager: NsdManager? = null
        private var wifiManager: WifiManager? = null
        private var generation = 0L
        private var current: Run? = null
        // Serialize physical requests while active, but never let a missing platform callback
        // retain this logical slot forever. Pre-34 cannot cancel the underlying request.
        private var activeResolution: Resolution? = null

        private inner class Run(val generation: Long) {
            var started = false
            var stopRequested = false
            var stopIssued = false
            var phase = Phase.STARTING
            var lock: MulticastLock? = null
            var emptyTimer: ScheduledFuture<*>? = null
            var emptyElapsed = false
            var nextTicket = 0L
            val present = mutableMapOf<String, Long>()
            val profiles = linkedMapOf<String, ServerProfile>()
            val resolves = linkedMapOf<Resolution, NsdServiceInfo>()
            var resolving: Resolution? = null
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) = execute { onStarted(this@Run) }
                override fun onDiscoveryStopped(serviceType: String?) = execute { onStopped(this@Run) }
                override fun onServiceFound(info: NsdServiceInfo) = execute { onFound(this@Run, info) }
                override fun onServiceLost(info: NsdServiceInfo) = execute { onLost(this@Run, info) }
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = execute {
                    if (current !== this@Run) return@execute
                    finish(this@Run, if (stopRequested) Phase.STOPPED else Phase.ERROR,
                            if (stopRequested) null else "Discovery could not start ($errorCode)")
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = execute {
                    // NSD drops this listener after reporting stop failure; it cannot be retried.
                    if (current === this@Run) finish(this@Run, Phase.ERROR,
                            "Discovery could not stop ($errorCode)")
                    else release(this@Run)
                }
            }
        }

        private inner class Resolution(val run: Run, val name: String, val ticket: Long) : NsdManager.ResolveListener {
            var accepting = true
            var cancelling = false
            var timeout: ScheduledFuture<*>? = null
            override fun onServiceResolved(info: NsdServiceInfo) = execute {
                if (accepting && accepts(run) && run.present[name] == ticket && run.resolving === this) {
                    val host = info.host?.hostAddress
                    if (!host.isNullOrBlank() && info.port in 1..65535) {
                        run.profiles[name] = ServerProfile(name = name, host = host, port = info.port)
                        publishProfiles(run)
                    } else resolutionFailed(this, "The discovered server has an invalid address")
                }
                resolved(this)
            }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = execute {
                if (accepting && accepts(run) && run.present[name] == ticket && run.resolving === this)
                    resolutionFailed(this, if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE)
                        "The system resolver is still busy. Retry discovery ($errorCode)"
                    else "Server address lookup failed ($errorCode)")
                resolved(this)
            }
            override fun onResolutionStopped(info: NsdServiceInfo) = execute { resolved(this) }
            override fun onStopResolutionFailed(info: NsdServiceInfo, errorCode: Int) = execute {
                if (activeResolution === this && accepts(run))
                    resolutionFailed(this, "Server lookup could not stop. Retry discovery ($errorCode)")
                resolved(this)
            }
        }

        private fun execute(action: () -> Unit) {
            try { executor.execute(action) }
            catch (error: RuntimeException) { Log.e(TAG, "Cannot execute discovery action", error) }
        }

        private fun accepts(run: Run) = current === run && run.generation == generation && !run.stopRequested

        fun start(context: Context) = execute {
            if (current?.let { !it.stopRequested } == true) return@execute
            val run = Run(++generation)
            current = run
            updateServers(emptyList())
            updateRunning(true)
            publish(run, Phase.STARTING)
            try {
                val app = context.applicationContext
                nsdManager = nsdOverride ?: ContextCompat.getSystemService(app, NsdManager::class.java)
                wifiManager = wifiOverride ?: ContextCompat.getSystemService(app, WifiManager::class.java)
                checkNotNull(nsdManager) { "Service discovery is unavailable" }
                        .discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, run.listener)
            } catch (error: Exception) {
                finish(run, Phase.ERROR, error.localizedMessage ?: "Service discovery is unavailable")
            }
        }

        fun stop() = execute {
            val run = current
            if (run == null) {
                updateRunning(false)
                updateState(UiState(Phase.STOPPED))
                return@execute
            }
            run.stopRequested = true
            run.resolves.clear()
            run.present.clear()
            release(run)
            updateRunning(false)
            publish(run, Phase.STOPPED)
            run.resolving?.let(::cancelResolution)
            requestStop(run)
        }

        private fun requestStop(run: Run) {
            if (run.stopIssued) return
            try {
                nsdManager?.stopServiceDiscovery(run.listener)
                run.stopIssued = true
            } catch (error: RuntimeException) {
                // A pending start may not yet be registered. Its onStarted callback retries.
                if (run.started && current === run) publish(run, Phase.ERROR,
                        error.localizedMessage ?: "Service discovery could not stop")
                Log.w(TAG, "Could not stop discovery yet", error)
            }
        }

        private fun onStarted(run: Run) {
            if (run.started) {
                if (!accepts(run)) requestStop(run)
                return
            }
            run.started = true
            if (!accepts(run)) { requestStop(run); return }
            try {
                run.lock = wifiManager?.createMulticastLock(TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                publish(run, if (run.profiles.isEmpty()) Phase.SEARCHING else Phase.RESULTS)
                run.emptyTimer = executor.schedule({
                    if (accepts(run)) {
                        run.emptyElapsed = true
                        if (run.profiles.isEmpty() && run.phase != Phase.ERROR) publish(run, Phase.EMPTY)
                    }
                }, emptyDelayMs, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                finish(run, Phase.ERROR, error.localizedMessage ?: "Network discovery is unavailable")
                requestStop(run)
            }
        }

        private fun onStopped(run: Run) {
            release(run)
            if (current === run) finish(run, Phase.STOPPED)
        }

        private fun finish(run: Run, phase: Phase, error: String? = null) {
            run.stopRequested = true
            run.resolves.clear()
            run.present.clear()
            release(run)
            run.resolving?.let(::cancelResolution)
            if (current === run) {
                updateRunning(false)
                publish(run, phase, error)
            }
        }

        private fun release(run: Run) {
            run.emptyTimer?.cancel(false)
            run.emptyTimer = null
            try { run.lock?.let { if (it.isHeld) it.release() } }
            catch (error: RuntimeException) { Log.w(TAG, "Could not release multicast lock", error) }
            run.lock = null
        }

        private fun publish(run: Run, phase: Phase, error: String? = null) {
            if (current !== run) return
            run.phase = phase
            updateState(UiState(phase, error))
        }

        private fun publishProfiles(run: Run) {
            if (!accepts(run)) return
            updateServers(run.profiles.values.toList())
            publish(run, if (run.profiles.isNotEmpty()) Phase.RESULTS
                    else if (run.emptyElapsed) Phase.EMPTY else Phase.SEARCHING)
        }

        private fun onFound(run: Run, info: NsdServiceInfo) {
            if (!accepts(run) || run.present.containsKey(info.serviceName)) return
            val ticket = ++run.nextTicket
            run.present[info.serviceName] = ticket
            run.resolves[Resolution(run, info.serviceName, ticket)] = info
            resolveNext(run)
        }

        private fun onLost(run: Run, info: NsdServiceInfo) {
            if (!accepts(run)) return
            run.present.remove(info.serviceName)
            run.profiles.remove(info.serviceName)
            // Keep an active NSD request until it completes, but reject its ticket afterward.
            run.resolves.keys.removeAll { it !== run.resolving && it.name == info.serviceName }
            publishProfiles(run)
        }

        private fun resolved(resolution: Resolution) {
            val run = resolution.run
            if (activeResolution !== resolution) return
            resolution.accepting = false
            resolution.timeout?.cancel(false)
            resolution.timeout = null
            run.resolves.remove(resolution)
            run.resolving = null
            activeResolution = null
            current?.let { if (accepts(it)) resolveNext(it) }
        }

        private fun resolutionFailed(resolution: Resolution, message: String) {
            val run = resolution.run
            if (run.present[resolution.name] == resolution.ticket) run.present.remove(resolution.name)
            if (accepts(run)) publish(run, Phase.ERROR, message)
        }

        private fun cancelResolution(resolution: Resolution) {
            if (activeResolution !== resolution || resolution.cancelling) return
            resolution.accepting = false
            resolution.cancelling = true
            resolution.timeout?.cancel(false)
            try {
                if (stopResolution(checkNotNull(nsdManager), resolution)) {
                    // Do not start the next physical request until stop is acknowledged. A
                    // broken platform must still release the queue after a bounded grace.
                    resolution.timeout = executor.schedule({
                        if (activeResolution === resolution) {
                            resolutionFailed(resolution, "Server lookup cancellation timed out. Retry discovery")
                            resolved(resolution)
                        }
                    }, stopResolutionDelayMs, TimeUnit.MILLISECONDS)
                    return
                }
            } catch (error: RuntimeException) {
                resolutionFailed(resolution, error.localizedMessage ?: "Server lookup could not stop. Retry discovery")
            }
            // Older Android cannot cancel a physical lookup. A subsequent request may
            // explicitly fail ALREADY_ACTIVE; expose that error instead of starving locally.
            resolved(resolution)
        }

        private fun resolveNext(run: Run) {
            if (!accepts(run) || activeResolution != null) return
            while (run.resolves.isNotEmpty()) {
                val next = run.resolves.entries.first()
                run.resolving = next.key
                activeResolution = next.key
                try {
                    checkNotNull(nsdManager).resolveService(next.value, next.key)
                    next.key.timeout = executor.schedule({
                        if (activeResolution === next.key) {
                            resolutionFailed(next.key, "Server address lookup timed out. Retry discovery")
                            cancelResolution(next.key)
                        }
                    }, resolutionDelayMs, TimeUnit.MILLISECONDS)
                    return
                } catch (error: RuntimeException) {
                    run.resolves.remove(next.key)
                    run.resolving = null
                    activeResolution = null
                    resolutionFailed(next.key, error.localizedMessage ?: "Server address lookup failed")
                }
            }
        }
    }
}
