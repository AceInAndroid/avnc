/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.session

import android.util.Log
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.util.broadcastWoLPackets
import com.gaurav.avnc.viewmodel.service.SshClient
import com.gaurav.avnc.vnc.VncClient
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Controls a remote session.
 *
 * Session is started with a call to [start]. It will kickoff a background thread
 * to handle the entire session. [observer] is notified about events by this thread.
 *
 * It can be stopped by calling [stop]. It will be automatically stopped when
 * remote server closes the connection  or an error occurs.
 */
class RemoteSession(private val observer: Observer) {

    interface Observer : VncClient.Observer, SshClient.Observer {
        fun onConnecting()
        fun onConnected(vncClient: VncClient, messenger: Messenger)
        fun onDisconnected()

        fun onConnectionError(error: Throwable)
        fun onWakeOnLanBroadcastError(e: Throwable)
    }


    private val id = sessionCounter.incrementAndGet()
    private val tag = "RemoteSession[$id]"

    @Volatile private var vncClient: VncClient? = null
    @Volatile private var sshClient: SshClient? = null
    private var messenger: Messenger? = null
    private var sessionThread: Thread? = null

    @Volatile
    private var stopRequested = false
    private val sendFailure = AtomicReference<Throwable?>()

    /**
     * Starts remote session for given [profile].
     * For now, [start] can be called only once for an instance.
     */
    @Synchronized
    fun start(profile: ServerProfile) {
        check(sessionThread == null) { "Session re-start is not yet supported" }

        log("Requesting session start")
        stopRequested = false
        sessionThread = startSession(profile)
    }

    @Synchronized
    fun stop() {
        if (sessionThread?.isAlive != true)
            return // Already stopped

        log("Requesting session stop")
        stopRequested = true
        vncClient?.cancel()
        sshClient?.cancel()
        sessionThread?.interrupt() // Also release a worker awaiting a credential/confirmation.
    }


    /******************************************************************************************/

    private fun startSession(profile: ServerProfile): Thread {
        return thread(name = tag) {
            log("Session started")

            val result = runCatching { startConnection(profile) }
            Thread.interrupted() // Stop may have interrupted a native call rather than a JVM wait.
            try {
                stopSession()
            } finally {
                handleConnectionEnd(sendFailure.get()?.let { Result.failure(it) } ?: result)
            }
        }
    }


    private fun startConnection(profile: ServerProfile) {
        log("Preparing clients")
        vncClient = VncClient(observer)
        sshClient = SshClient(observer)
        checkActive()
        configureClient(vncClient!!, profile)

        handleWoL(profile)
        connect(profile, vncClient!!, sshClient!!)
    }


    private fun connect(profile: ServerProfile, vncClient: VncClient, sshClient: SshClient) {
        checkActive()
        log("Connecting to server")
        observer.onConnecting()

        when (profile.channelType) {
            ServerProfile.CHANNEL_TCP ->
                vncClient.connect(profile.host, profile.port)

            ServerProfile.CHANNEL_SSH_TUNNEL ->
                sshClient.openTunnel(profile).use {
                    vncClient.connect(it.host, it.port)
                }

            else -> throw IllegalStateException("Unknown Channel: ${profile.channelType}")
        }

        checkActive()
        messenger = Messenger(vncClient) { error ->
            if (sendFailure.compareAndSet(null, error)) {
                vncClient.cancel()
                sshClient.cancel()
            }
        }

        log("Connected to server")
        synchronized(this) {
            checkActive()
            observer.onConnected(vncClient, messenger!!)
        }

        messageLoop(vncClient)
    }

    private fun messageLoop(vncClient: VncClient) {
        log("Running message loop")
        while (!stopRequested)
            vncClient.processServerMessage()
    }

    private fun stopSession() {
        // Cancel both transports before joining the sender; every resource gets cleanup.
        vncClient?.cancel()
        sshClient?.cancel()
        runCatching { messenger?.shutdown() }.onFailure { logE("Sender cleanup failed", it) }
        runCatching { vncClient?.cleanup() }.onFailure { logE("VNC cleanup failed", it) }
        runCatching { sshClient?.close() }.onFailure { logE("SSH cleanup failed", it) }

        messenger = null
        vncClient = null
        sshClient = null
        log("Session stopped")
    }

    private fun checkActive() {
        if (stopRequested) throw IOException("Connection cancelled")
    }


    /*********************************************************************************/

    private fun configureClient(vncClient: VncClient, profile: ServerProfile) {
        vncClient.configure(profile.securityType, true  /* Hardcoded to true */,
                            profile.imageQuality, profile.useRawEncoding)
        vncClient.setStreamingPreset(profile.streamingPreset)

        if (profile.useRepeater)
            vncClient.setupRepeater(profile.idOnRepeater)

        vncClient.setInputDisabled(profile.viewMode == ServerProfile.VIEW_MODE_NO_INPUT)
        vncClient.setFrameBufferUpdatesPaused(profile.viewMode == ServerProfile.VIEW_MODE_NO_VIDEO)
    }

    private fun handleWoL(profile: ServerProfile) {
        if (!profile.enableWol)
            return

        log("Sending WoL magic packet")
        runCatching { broadcastWoLPackets(profile.wolMAC, profile.wolBroadcastAddress, profile.wolPort) }
                .onFailure {
                    logE("WoL broadcast error: ${it.message}", it)
                    observer.onWakeOnLanBroadcastError(it)
                }
    }

    private fun handleConnectionEnd(result: Result<Unit>) {
        result.onFailure {
            if (!stopRequested) {
                logE("Connection error", it)
                runCatching { observer.onConnectionError(it) }
                        .onFailure { error -> logE("Error reporting connection failure", error) }
            }
        }
        log("Disconnected from server")
        runCatching { observer.onDisconnected() }
                .onFailure { logE("Error handling connection end", it) }
    }

    private fun log(msg: String) = Log.i(tag, msg)
    private fun logE(msg: String, e: Throwable?) = Log.e(tag, msg, e)

    companion object {
        private val sessionCounter = AtomicInteger(0)
    }
}
