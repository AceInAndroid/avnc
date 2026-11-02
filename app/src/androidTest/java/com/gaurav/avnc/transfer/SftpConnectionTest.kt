/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.SshTunnelScenario
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.viewmodel.service.SshClient
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Real SSH handshake/auth boundaries, with the same host verification as ordinary VNC tunnels. */
@RunWith(AndroidJUnit4::class)
class SftpConnectionTest {
    // Initialize the Android home/working-directory resolvers before Apache ServerBuilder.
    private val testHostKey = SshTunnelScenario.hostKeyPair
    @Test fun rejectingHostKeyStopsBeforeAuthenticationAndTrustIsNotPersisted() {
        val attempts = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(testHostKey)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> attempts.incrementAndGet(); true }
        }
        val hosts = File.createTempFile("sftp-known-hosts", ".txt", targetContext.cacheDir)
        var confirmations = 0
        val ssh = SshClient(object : SshClient.Observer {
            override fun getKnownSshHostsFile() = hosts
            override fun getSshPassword() = "password"
            override fun getSshKeyPassword() = ""
            override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
                confirmations++
                assertTrue(isNewHost)
                assertTrue(message.contains(SshTunnelScenario.HOST_FINGERPRINT))
                return false
            }
        })
        try {
            server.start()
            try {
                ssh.openFileTransfer(ServerProfile(sshHost = "127.0.0.1", sshPort = server.port,
                        sshUsername = "tester", sshAuthType = ServerProfile.SSH_AUTH_PASSWORD))
                fail("Rejected host must not establish a file connection")
            } catch (_: IOException) { }
            assertEquals(1, confirmations)
            assertEquals(0, attempts.get())
            assertTrue(hosts.readText().isEmpty())
        } finally { ssh.close(); server.stop(); hosts.delete() }
    }

    @Test fun acceptedHostIsRememberedButBadPasswordCannotStartSftp() {
        val attempts = AtomicInteger()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = KeyPairProvider.wrap(testHostKey)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ -> attempts.incrementAndGet(); false }
        }
        val hosts = File.createTempFile("sftp-known-hosts", ".txt", targetContext.cacheDir)
        val ssh = SshClient(object : SshClient.Observer {
            override fun getKnownSshHostsFile() = hosts
            override fun getSshPassword() = "wrong password"
            override fun getSshKeyPassword() = ""
            override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean) = true
        })
        try {
            server.start()
            try {
                ssh.openFileTransfer(ServerProfile(sshHost = "127.0.0.1", sshPort = server.port,
                        sshUsername = "tester", sshAuthType = ServerProfile.SSH_AUTH_PASSWORD))
                fail("Bad password must not establish a file connection")
            } catch (_: IOException) { }
            assertTrue(attempts.get() > 0)
            assertTrue(hosts.readText().isNotBlank())
        } finally { ssh.close(); server.stop(); hosts.delete() }
    }
}
