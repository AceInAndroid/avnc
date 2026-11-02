/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel.service

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.system.Os
import android.util.Base64
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.gaurav.avnc.model.ServerProfile
import com.trilead.ssh2.Connection
import com.trilead.ssh2.KnownHosts
import com.trilead.ssh2.LocalPortForwarder
import com.trilead.ssh2.ProxyData
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.crypto.OpenSSHKeyEncoder
import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.PEMStructure
import com.trilead.ssh2.crypto.cipher.BlockCipherFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Implements Host Key verification.
 *
 * Known hosts & keys are stored in a file inside app's private storage.
 * For unknown host, user is prompted to confirm the key.
 */
class HostKeyVerifier(private val observer: SshClient.Observer,
                      private val checkActive: () -> Unit = {},
                      private val awaitConfirmation: (() -> Boolean) -> Boolean = { it() }) : ServerHostKeyVerifier {

    override fun verifyServerHostKey(hostname: String, port: Int, keyAlgorithm: String, key: ByteArray): Boolean {
        checkActive()
        val knownHostsFile = observer.getKnownSshHostsFile()
        val knownHosts = KnownHosts(knownHostsFile)
        val verificationResult = knownHosts.verifyHostkey(hostname, keyAlgorithm, key)

        if (verificationResult == KnownHosts.HOSTKEY_IS_OK)
            return true

        // Unknown key, confirm with user
        val keyDigest = MessageDigest.getInstance("SHA-256").digest(key)
        val keyDigestStr = Base64.encodeToString(keyDigest, Base64.NO_PADDING)
        val isNew = (verificationResult == KnownHosts.HOSTKEY_IS_NEW)
        val message = """
                 |
                 |Host:   $hostname
                 |Key type:   ${keyAlgorithm.uppercase()}
                 |Key fingerprint: 
                 |  
                 |SHA256:$keyDigestStr
                 |
                 |Make sure your are connecting to correct SSH host.
                 |
                 |If you continue, this key will be added to trusted list.
                 """.trimMargin()

        val confirmed = awaitConfirmation { observer.confirmSshHostKeyWithUser(message, isNew) }
        checkActive()
        if (confirmed) {
            //User has confirmed the key, so remember it.
            KnownHosts.addHostkeyToFile(knownHostsFile, arrayOf(hostname), keyAlgorithm, key)
            return true
        }

        return false
    }
}

/**
 * Handler for PEM encoded private keys
 */
class PemKey(source: String) {
    private var keyPair: KeyPair? = null
    private val ps: PEMStructure?

    init {
        if (source.contains("-----BEGIN PRIVATE KEY-----")) {
            ps = null
            keyPair = parsePKCS8Key(source)
        } else {
            ps = PEMDecoder.parsePEM(source.toCharArray())
            if (!PEMDecoder.isPEMEncrypted(ps))
                keyPair = PEMDecoder.decode(ps, "")
        }

        check(keyPair != null || ps != null) { "Unsupported private key file" }
    }

    private fun parsePKCS8Key(key: String): KeyPair {
        val pkBytes = key.substringAfter("-----BEGIN PRIVATE KEY-----")
                .substringBefore("-----END PRIVATE KEY-----")
                .let { kotlin.io.encoding.Base64.Mime.decode(it) }

        return OpenSSHKeyEncoder.recoverKeyPair(pkBytes)
    }

    fun isEncrypted(): Boolean {
        return keyPair == null && PEMDecoder.isPEMEncrypted(ps)
    }

    fun decrypt(password: String) {
        checkNotNull(ps)
        keyPair = PEMDecoder.decode(ps, password)
    }

    fun getKeyPair() = keyPair!!
}

/**
 * Small wrapper around [LocalPortForwarder].
 *
 * Once connection has been successfully established via [host] & [port],
 * this gate should be closed to stop new connections.
 */
class TunnelGate(val host: String, val port: Int, private val forwarder: LocalPortForwarder) : Closeable {
    override fun close() {
        forwarder.close()
    }
}

class SshTunnelException(message: String = "", cause: Throwable? = null) : IOException(message, cause)

/**
 * Manager for SSH Tunnel
 */
class SshClient(private val observer: Observer) {

    interface Observer {
        fun getKnownSshHostsFile(): File
        fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean
        fun getSshPassword(): String
        fun getSshKeyPassword(): String
    }

    private val stateLock = Any()
    @Volatile private var cancelled = false
    private var started = false
    private var connection: Connection? = null
    private var transportSocket: Socket? = null
    private val localHost = "127.0.0.1"

    /** Opens an ordinary VNC tunnel with bounded TCP and SSH key-exchange handshakes. */
    fun openTunnel(profile: ServerProfile): TunnelGate {
        beginConnection()
        try {
            val connected = connect(profile)
            authenticate(connected, profile)
            checkActive()
            return createPortForwarder(connected, profile)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Independent SFTP connection; never shares or interrupts the viewer's live tunnel. */
    fun openFileTransfer(profile: ServerProfile): SFTPv3Client {
        beginConnection()
        try {
            val connected = connect(profile)
            authenticate(connected, profile)
            checkActive()
            val client = withTransportDeadline("SFTP initialization") { SFTPv3Client(connected) }
            try {
                checkActive()
                return client
            } catch (error: Throwable) {
                client.close()
                throw error
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /**
     * Safe on the UI thread, even while SSHLib holds Connection's monitor in connect/auth.
     * Cancellation is sticky: a cancelled client cannot begin or retry a connection later.
     * Never invoke Connection.close() here; closing our raw socket interrupts its blocking I/O.
     */
    fun cancel() {
        val socket = synchronized(stateLock) {
            cancelled = true
            transportSocket
        }
        runCatching { socket?.close() }
    }

    /** Worker-side cleanup. Call [cancel] instead when stopping from the UI thread. */
    fun close() {
        cancel()
        val connected = synchronized(stateLock) {
            connection.also { connection = null }
        }
        connected?.close()
        synchronized(stateLock) { transportSocket = null }
    }

    private fun beginConnection() {
        synchronized(stateLock) {
            checkActive()
            check(!started) { "Connection already started" }
            started = true
        }
    }

    private fun checkActive() {
        if (cancelled) throw SshTunnelException("SSH connection cancelled")
    }

    /**
     * Own the TCP socket through SSHLib's public ProxyData hook. The short state lock only
     * publishes ownership; DNS, connect, SSH handshakes, socket close and library cleanup
     * all happen outside it. Retry other addresses only before TCP is established: host-key
     * rejection, authentication failure and SSH protocol errors must never trigger retries.
     */
    private fun connect(profile: ServerProfile): Connection {
        checkActive()
        val addresses = InetAddress.getAllByName(profile.sshHost)
        var lastTransportFailure: IOException? = null
        for (address in addresses) {
            checkActive()
            val candidate = Connection(address.hostAddress, profile.sshPort)
            var socket: Socket? = null
            var tcpConnected = false
            try {
                removeUnsupportedCiphers(candidate)
                candidate.setProxyData(ProxyData { _, port, timeout ->
                    openSocket(address, port, timeout).also {
                        socket = it
                        tcpConnected = true
                    }
                })
                withTransportDeadline("key exchange", CONNECTION_TIMEOUT_MS) { awaitConfirmation ->
                    // SSHLib's own KEX timer includes the human host-trust callback. Own
                    // the deadline instead, so time spent reading that prompt is excluded.
                    candidate.connect(HostKeyVerifier(observer, ::checkActive, awaitConfirmation),
                            CONNECTION_TIMEOUT_MS, 0)
                }
                synchronized(stateLock) {
                    checkActive()
                    connection = candidate
                }
                return candidate
            } catch (error: Throwable) {
                socket?.let { discardSocket(it) }
                candidate.close() // Only the connection worker touches SSHLib's monitor.
                checkActive()
                if (error !is IOException || tcpConnected) throw unwrapLibraryException(error)
                lastTransportFailure = error
            }
        }
        throw lastTransportFailure?.let { unwrapLibraryException(it) }
                ?: SshTunnelException("Unreachable SSH host: ${profile.sshHost}")
    }

    private fun openSocket(address: InetAddress, port: Int, timeout: Int): Socket {
        val socket = Socket()
        try {
            synchronized(stateLock) {
                checkActive()
                transportSocket = socket
            }
            checkActive()
            socket.keepAlive = true
            socket.connect(InetSocketAddress(address, port), timeout)
            configureKeepAlive(socket)
            socket.soTimeout = 0
            checkActive()
            return socket
        } catch (error: Throwable) {
            discardSocket(socket)
            throw error
        }
    }

    private fun discardSocket(socket: Socket) {
        synchronized(stateLock) {
            if (transportSocket === socket) transportSocket = null
        }
        runCatching { socket.close() }
    }

    private fun configureKeepAlive(socket: Socket) {
        if (Build.VERSION.SDK_INT < 29) return // fromSocket() did not duplicate its fd before Q.
        runCatching {
            ParcelFileDescriptor.fromSocket(socket)?.use { descriptor ->
                val fd = descriptor.fileDescriptor
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 15)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 5)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3)
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, OPERATION_TIMEOUT_MS)
            }
        } // Unsupported kernels retain Socket.keepAlive; never close the socket's borrowed fd.
    }

    /**
     * Bound SSH wire I/O while allowing the receive thread to pause for human host trust.
     * The short local lock gives completion and timeout exclusive ownership. Socket close
     * happens outside both locks, and only targets the socket captured when timeout won.
     */
    private fun <T> withTransportDeadline(operation: String,
                                          timeoutMs: Int = OPERATION_TIMEOUT_MS,
                                          action: ((() -> Boolean) -> Boolean) -> T): T {
        checkActive()
        val deadlineLock = Any()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        var expiresAt = System.nanoTime() + timeoutNanos
        var pending = true
        var waitingForUser = false
        var timedOut = false
        val awaitConfirmation: (() -> Boolean) -> Boolean = { confirm ->
            synchronized(deadlineLock) {
                if (!pending) throw SshTunnelException("SSH $operation timed out")
                waitingForUser = true
            }
            try {
                checkActive()
                confirm()
            } finally {
                synchronized(deadlineLock) {
                    waitingForUser = false
                    expiresAt = System.nanoTime() + timeoutNanos
                }
            }
        }
        val deadline = deadlines.scheduleWithFixedDelay({
            val socket = synchronized(deadlineLock) {
                if (pending && !waitingForUser && System.nanoTime() >= expiresAt) {
                    pending = false
                    timedOut = true
                    synchronized(stateLock) { transportSocket }
                } else null
            }
            runCatching { socket?.close() }
        }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            val result = action(awaitConfirmation)
            synchronized(deadlineLock) {
                if (!pending) throw SshTunnelException("SSH $operation timed out")
                pending = false
            }
            checkActive()
            return result
        } catch (error: Throwable) {
            val expired = synchronized(deadlineLock) { pending = false; timedOut }
            if (expired) throw SshTunnelException("SSH $operation timed out", error)
            throw error
        } finally {
            synchronized(deadlineLock) { pending = false }
            deadline.cancel(false)
        }
    }

    private fun authenticate(connection: Connection, profile: ServerProfile) {
        checkActive()
        when (profile.sshAuthType) {
            ServerProfile.SSH_AUTH_PASSWORD -> {
                val password = observer.getSshPassword()  //Possibly blocking call
                checkActive()
                withTransportDeadline("password authentication") {
                    connection.authenticateWithPassword(profile.sshUsername, password)
                }
                if (!connection.isAuthenticationComplete)
                    throw SshTunnelException("SSH Password authentication failed")
            }
            ServerProfile.SSH_AUTH_KEY -> {
                val pk = profile.sshPrivateKey
                val cached = KeyCache.get(pk)
                if (cached != null) {
                    checkActive()
                    withTransportDeadline("public-key authentication") {
                        connection.authenticateWithPublicKey(profile.sshUsername, cached)
                    }
                } else {
                    val pemKey = PemKey(pk)
                    if (pemKey.isEncrypted()) {
                        val password = observer.getSshKeyPassword()  //Blocking call
                        checkActive()
                        pemKey.decrypt(password)
                    }
                    val keyPair = pemKey.getKeyPair()
                    checkActive()
                    withTransportDeadline("public-key authentication") {
                        connection.authenticateWithPublicKey(profile.sshUsername, keyPair)
                    }
                    KeyCache.put(pk, keyPair)
                }

                if (!connection.isAuthenticationComplete)
                    throw SshTunnelException("SSH Key authentication failed")
            }
            else -> throw SshTunnelException("Unknown SSH auth type: ${profile.sshAuthType}")
        }

        checkActive()
        if (!connection.isAuthenticationComplete)
            throw SshTunnelException("SSH authentication failed")
    }

    private fun createPortForwarder(connection: Connection, profile: ServerProfile): TunnelGate {
        // SSHLib does not expose internal ServerSocket used for local port forwarder.
        // Hence, if we pass 0 as local port to let the system pick a port for us, we have no way
        // to know the port system picked.
        // So we create a temporary ServerSocket, close it immediately and try to use its port.
        // But between the close-reuse, that port can be assigned to someone else, so we try again.
        repeat(50) {
            checkActive()
            try {
                val attemptedPort = ServerSocket(0).use { it.localPort }
                val attemptedAddress = InetSocketAddress(localHost, attemptedPort)
                val forwarder = connection.createLocalPortForwarder(attemptedAddress, profile.host, profile.port)
                if (cancelled) {
                    forwarder.close()
                    checkActive()
                }
                return TunnelGate(localHost, attemptedPort, forwarder)
            } catch (_: IOException) {
                checkActive()
                // Retry a local port collision only while this session is still active.
            } catch (e: Throwable) {
                throw unwrapLibraryException(e)
            }
        }
        throw SshTunnelException("Cannot find a local port for SSH Tunnel")
    }

    /**
     * In many error conditions, SSHLib throws [IOException] with a generic message
     * like 'An error happened when connecting'. This gives no indication to users about
     * actual cause of the error. So this function attempts to find the root cause of the
     * error, and provide a better message to the user.
     */
    private fun unwrapLibraryException(e: Throwable): Throwable {
        if (e is SshTunnelException) return e

        // collect error history
        val errors = mutableListOf(e)
        var cause = e.cause
        while (cause != null) {
            errors.add(cause)
            cause = cause.cause
        }

        errors.find { it is ErrnoException }?.let {
            val msg = when ((it as ErrnoException).errno) {
                OsConstants.ECONNREFUSED -> "SSH server is not running, or port is incorrect"
                OsConstants.ECONNABORTED -> "SSH connection aborted"
                OsConstants.ECONNRESET -> "SSH connection closed abruptly by remote host"
                else -> "SSH: " + it.message?.substringAfter('(')?.substringBefore(')')
            }
            return SshTunnelException(msg, e)
        }

        return e
    }

    private fun removeUnsupportedCiphers(connection: Connection) {
        if (Build.VERSION.SDK_INT < 28) {
            // ChaCha20 is not supported
            val ciphers = BlockCipherFactory.getDefaultCipherList()
                    .filter { !it.contains("chacha20") }
                    .toTypedArray()
            connection.setClient2ServerCiphers(ciphers)
            connection.setServer2ClientCiphers(ciphers)
        }
    }

    /**
     * A very simple key cache to keep unlocked/decoded keys in memory
     * Strategy:
     * 1. Keep keys in memory as long as app is in foreground
     * 2. Clear cache if app goes in background for more than 15 minutes
     */
    private object KeyCache {
        private val cache = mutableMapOf<String, KeyPair>()
        private var lifecycleObserver: DefaultLifecycleObserver? = null

        fun get(pk: String): KeyPair? {
            synchronized(cache) {
                return cache[pk]
            }
        }

        fun put(pk: String, keyPair: KeyPair) {
            synchronized(cache) {
                cache[pk] = keyPair
                addLifecycleObserver()
            }
        }

        private fun addLifecycleObserver() {
            if (lifecycleObserver != null)
                return // Already added

            lifecycleObserver = object : DefaultLifecycleObserver {
                var cleanupJob: Job? = null

                override fun onStart(owner: LifecycleOwner) {
                    cleanupJob?.let { if (it.isActive) it.cancel() }
                    cleanupJob = null
                }

                override fun onStop(owner: LifecycleOwner) {
                    cleanupJob = owner.lifecycleScope.launch {
                        delay(15 * 60 * 1000)
                        synchronized(cache) {
                            cache.clear()
                        }
                    }
                }
            }

            ProcessLifecycleOwner.get().let {
                // Observer needs to be set on main thread,
                // and lifecycleScope is already bound to main thread
                it.lifecycleScope.launch {
                    it.lifecycle.addObserver(lifecycleObserver!!)
                }
            }
        }
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 10_000
        const val OPERATION_TIMEOUT_MS = 15_000
        // Stable Linux TCP UAPI option values; these names are absent from public OsConstants.
        const val TCP_KEEPIDLE = 4
        const val TCP_KEEPINTVL = 5
        const val TCP_KEEPCNT = 6
        val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "SSH operation deadlines").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
