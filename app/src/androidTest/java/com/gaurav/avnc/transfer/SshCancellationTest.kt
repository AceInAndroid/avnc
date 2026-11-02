/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.transfer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.SshTunnelScenario
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.viewmodel.service.SshClient
import com.gaurav.avnc.viewmodel.service.SshTunnelException
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.auth.AsyncAuthException
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.subsystem.SubsystemFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real TCP peers that accept SSH and deliberately never send their SSH identification. */
@RunWith(AndroidJUnit4::class)
class SshCancellationTest {
    private class SilentSshPeer : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        val accepted = CountDownLatch(1)
        val receivedClientIdentification = CountDownLatch(1)
        private val stop = CountDownLatch(1)
        private val socket = AtomicReference<Socket?>()
        private val worker = thread(name = "silent-ssh-peer", isDaemon = true) {
            try {
                listener.accept().use { peer ->
                    socket.set(peer)
                    accepted.countDown()
                    peer.soTimeout = 15_000
                    val input = peer.getInputStream()
                    // Reaching the newline proves SSHLib is inside its synchronized handshake.
                    repeat(512) {
                        val value = input.read()
                        if (value == -1) return@thread
                        if (value == '\n'.code) {
                            receivedClientIdentification.countDown()
                            stop.await(15, TimeUnit.SECONDS)
                            return@thread
                        }
                    }
                }
            } catch (_: IOException) {
                // Closing this disposable test endpoint also interrupts accept/read.
            }
        }

        override fun close() {
            stop.countDown()
            runCatching { socket.get()?.close() }
            listener.close()
            worker.join(1000)
        }
    }

    private class UnexpectedPrompts : SshClient.Observer, Closeable {
        val calls = AtomicInteger()
        private val hosts = File.createTempFile("ssh-cancel-known-hosts", ".txt", targetContext.cacheDir)
        override fun getKnownSshHostsFile(): File { calls.incrementAndGet(); return hosts }
        override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
            calls.incrementAndGet()
            return false
        }
        override fun getSshPassword(): String { calls.incrementAndGet(); return "unused" }
        override fun getSshKeyPassword(): String { calls.incrementAndGet(); return "unused" }
        override fun close() { hosts.delete() }
    }

    private fun profile(peer: SilentSshPeer) = ServerProfile(
            sshHost = "127.0.0.1", sshPort = peer.port, sshUsername = "test",
            sshAuthType = ServerProfile.SSH_AUTH_PASSWORD, host = "127.0.0.1", port = 5900)

    private fun cancelDuringHandshake(fileTransfer: Boolean) {
        SilentSshPeer().use { peer ->
            UnexpectedPrompts().use { observer ->
                val ssh = SshClient(observer)
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>()
                val worker = thread(name = "cancelled-ssh-handshake", isDaemon = true) {
                    try {
                        if (fileTransfer) ssh.openFileTransfer(profile(peer)).close()
                        else ssh.openTunnel(profile(peer)).close()
                        error.set(AssertionError("A silent SSH peer must never establish a connection"))
                    } catch (failure: Throwable) {
                        error.set(failure)
                    } finally {
                        ssh.close() // Connection cleanup belongs to its worker, never the UI.
                        done.countDown()
                    }
                }
                try {
                    assertTrue("SSH client did not reach its handshake", peer.receivedClientIdentification.await(3, TimeUnit.SECONDS))
                    val start = SystemClock.elapsedRealtime()
                    instrumentation.runOnMainSync { ssh.cancel() }
                    val cancelElapsed = SystemClock.elapsedRealtime() - start
                    assertTrue("UI cancel waited ${cancelElapsed}ms for SSHLib's monitor", cancelElapsed < 2000)
                    assertTrue("Closing the owned socket must unblock SSH connect", done.await(2, TimeUnit.SECONDS))
                    assertTrue("Cancelled connection should fail with an IOException: ${error.get()}", error.get() is IOException)
                    assertTrue("No authentication or trust prompt should run for a silent server", observer.calls.get() == 0)
                } finally {
                    ssh.cancel()
                    peer.close()
                    worker.join(2000)
                }
            }
        }
    }

    @Test(timeout = 15_000) fun tunnelHandshakeCanBeCancelledFromMainThreadWithinTwoSeconds() =
            cancelDuringHandshake(fileTransfer = false)

    @Test(timeout = 15_000) fun sftpHandshakeCanBeCancelledFromMainThreadWithinTwoSeconds() =
            cancelDuringHandshake(fileTransfer = true)

    @Test(timeout = 15_000) fun cancelBeforeStartIsStickyForTunnelAndSftp() {
        SilentSshPeer().use { peer ->
            UnexpectedPrompts().use { observer ->
                val ssh = SshClient(observer)
                try {
                    ssh.cancel()
                    val tunnel = runCatching { ssh.openTunnel(profile(peer)) }.exceptionOrNull()
                    val sftp = runCatching { ssh.openFileTransfer(profile(peer)) }.exceptionOrNull()
                    assertTrue(tunnel is SshTunnelException)
                    assertTrue(sftp is SshTunnelException)
                    assertFalse("Cancelled client must not create a socket", peer.accepted.await(200, TimeUnit.MILLISECONDS))
                    assertTrue(observer.calls.get() == 0)
                } finally { ssh.close() }
            }
        }
    }

    @Test(timeout = 20_000) fun silentSshHandshakeHasBoundedTimeoutWithoutCancellation() {
        SilentSshPeer().use { peer ->
            UnexpectedPrompts().use { observer ->
                val ssh = SshClient(observer)
                val done = CountDownLatch(1)
                val error = AtomicReference<Throwable?>()
                val start = SystemClock.elapsedRealtime()
                val worker = thread(name = "timed-ssh-handshake", isDaemon = true) {
                    try {
                        ssh.openTunnel(profile(peer)).close()
                    } catch (failure: Throwable) { error.set(failure) }
                    finally { ssh.close(); done.countDown() }
                }
                try {
                    assertTrue(peer.receivedClientIdentification.await(3, TimeUnit.SECONDS))
                    assertTrue("Silent SSH handshake exceeded its 10s timeout", done.await(12, TimeUnit.SECONDS))
                    val elapsed = SystemClock.elapsedRealtime() - start
                    assertNotNull("Silent peer must fail the handshake", error.get())
                    assertTrue(error.get() is IOException)
                    assertTrue("Timeout returned implausibly early: ${elapsed}ms", elapsed >= 8000)
                    assertTrue("Timeout was not bounded: ${elapsed}ms", elapsed < 15_000)
                    assertTrue(observer.calls.get() == 0)
                } finally {
                    ssh.cancel()
                    peer.close()
                    worker.join(2000)
                }
            }
        }
    }

    @SdkSuppress(minSdkVersion = 26) // MINA requires NIO classes absent on older Android versions.
    @Test(timeout = 30_000) fun silentPasswordAuthenticationHasItsOwnDeadline() {
        // Initialize MINA's Android path resolvers before creating the real SSH server.
        val hostKey = SshTunnelScenario.hostKeyPair
        val enteredAuth = CountDownLatch(1)
        val authStarted = AtomicLong()
        val pendingAuth = AtomicReference<AsyncAuthException?>()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ ->
                val pending = AsyncAuthException()
                pendingAuth.set(pending)
                authStarted.set(SystemClock.elapsedRealtime())
                enteredAuth.countDown()
                // MINA accepts the auth request asynchronously, but never sends its result.
                throw pending
            }
        }
        val hosts = File.createTempFile("ssh-auth-timeout-hosts", ".txt", targetContext.cacheDir)
        val ssh = SshClient(object : SshClient.Observer {
            override fun getKnownSshHostsFile() = hosts
            override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean) = true
            override fun getSshPassword() = "not-used-by-silent-authenticator"
            override fun getSshKeyPassword() = ""
        })
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var worker: Thread? = null
        try {
            server.start()
            worker = thread(name = "timed-ssh-authentication", isDaemon = true) {
                try {
                    ssh.openTunnel(ServerProfile(sshHost = "127.0.0.1", sshPort = server.port,
                            sshUsername = "tester", sshAuthType = ServerProfile.SSH_AUTH_PASSWORD)).close()
                } catch (failure: Throwable) { error.set(failure) }
                finally { ssh.close(); done.countDown() }
            }
            assertTrue("Server never received the authentication request", enteredAuth.await(5, TimeUnit.SECONDS))
            assertTrue("Silent authentication exceeded its 15s operation deadline", done.await(18, TimeUnit.SECONDS))
            val elapsed = SystemClock.elapsedRealtime() - authStarted.get()
            assertTrue("Expected an SSH authentication timeout, got ${error.get()}",
                    error.get() is SshTunnelException && error.get()?.message?.contains("authentication timed out") == true)
            assertTrue("Authentication failed before its deadline: ${elapsed}ms", elapsed >= 12_000)
            assertTrue("Authentication was not bounded: ${elapsed}ms", elapsed < 20_000)
        } finally {
            ssh.cancel()
            runCatching { pendingAuth.get()?.setAuthed(false) }
            server.stop(true)
            worker?.join(2000)
            hosts.delete()
        }
    }

    /** The real SFTP INIT/VERSION wire exchange, without adding a server SFTP dependency. */
    private class SftpInitCommand : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var exit: ExitCallback
        private var worker: Thread? = null
        override fun setInputStream(stream: InputStream) { input = stream }
        override fun setOutputStream(stream: OutputStream) { output = stream }
        override fun setErrorStream(stream: OutputStream) {}
        override fun setExitCallback(callback: ExitCallback) { exit = callback }
        override fun start(channel: ChannelSession, env: Environment) {
            worker = thread(name = "sftp-version-peer", isDaemon = true) {
                try {
                    val request = DataInputStream(input)
                    check(request.readInt() == 5 && request.readUnsignedByte() == 1)
                    check(request.readInt() == 3)
                    DataOutputStream(output).apply {
                        writeInt(5); writeByte(2); writeInt(3); flush()
                    }
                    while (input.read() != -1) { }
                    exit.onExit(0)
                } catch (_: IOException) {
                    // Closing the SSH session terminates this disposable subsystem.
                }
            }
        }
        override fun destroy(channel: ChannelSession) {
            runCatching { input.close() }
            worker?.interrupt()
        }
    }

    private fun trustPromptConnection(cancel: Boolean) {
        val hostKey = SshTunnelScenario.hostKeyPair
        val enteredPrompt = CountDownLatch(1)
        val releasePrompt = CountDownLatch(1)
        val authAttempts = AtomicInteger()
        val initialized = CountDownLatch(1)
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> authAttempts.incrementAndGet(); true }
            subsystemFactories = listOf(object : SubsystemFactory {
                override fun getName() = "sftp"
                override fun createSubsystem(channel: ChannelSession): Command = SftpInitCommand()
            })
        }
        val hosts = File.createTempFile("ssh-trust-prompt-hosts", ".txt", targetContext.cacheDir)
        val ssh = SshClient(object : SshClient.Observer {
            override fun getKnownSshHostsFile() = hosts
            override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
                enteredPrompt.countDown()
                check(releasePrompt.await(20, TimeUnit.SECONDS))
                return true
            }
            override fun getSshPassword() = "password"
            override fun getSshKeyPassword() = ""
        })
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var worker: Thread? = null
        try {
            server.start()
            worker = thread(name = "ssh-trust-prompt-client", isDaemon = true) {
                try {
                    val client = ssh.openFileTransfer(ServerProfile(sshHost = "127.0.0.1", sshPort = server.port,
                            sshUsername = "tester", sshAuthType = ServerProfile.SSH_AUTH_PASSWORD))
                    initialized.countDown()
                    client.close()
                } catch (failure: Throwable) { error.set(failure) }
                finally { ssh.close(); done.countDown() }
            }
            assertTrue("Host-trust prompt was not reached", enteredPrompt.await(5, TimeUnit.SECONDS))
            if (cancel) {
                val start = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync { ssh.cancel() }
                assertTrue("Cancellation blocked behind the host-trust callback",
                        SystemClock.elapsedRealtime() - start < 2000)
            } else {
                assertFalse("Handshake expired while waiting for human host-trust confirmation",
                        done.await(11, TimeUnit.SECONDS))
                assertTrue("Host trust must wait for confirmation", hosts.readText().isEmpty())
            }
            releasePrompt.countDown()
            assertTrue("SSH worker did not finish after releasing the trust prompt", done.await(5, TimeUnit.SECONDS))
            if (cancel) {
                assertTrue("Cancelled prompt must fail the connection: ${error.get()}", error.get() is IOException)
                assertTrue("Cancelled confirmation must not trust the host", hosts.readText().isEmpty())
                assertTrue("Cancelled confirmation must not attempt authentication", authAttempts.get() == 0)
                assertTrue(initialized.count == 1L)
            } else {
                assertTrue("Slow human confirmation must still permit SFTP: ${error.get()}", error.get() == null)
                assertTrue("The actual SFTP INIT/VERSION exchange did not complete", initialized.count == 0L)
                assertTrue(hosts.readText().isNotEmpty())
                assertTrue(authAttempts.get() > 0)
            }
        } finally {
            ssh.cancel()
            releasePrompt.countDown()
            server.stop(true)
            worker?.join(2000)
            hosts.delete()
        }
    }

    @SdkSuppress(minSdkVersion = 26)
    @Test(timeout = 30_000) fun humanHostTrustLongerThanHandshakeTimeoutStillPermitsSftp() =
            trustPromptConnection(cancel = false)

    @SdkSuppress(minSdkVersion = 26)
    @Test(timeout = 15_000) fun cancelDuringHumanHostTrustDoesNotPersistOrAuthenticate() =
            trustPromptConnection(cancel = true)

}
