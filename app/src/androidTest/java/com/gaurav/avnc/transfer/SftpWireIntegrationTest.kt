/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.viewmodel.service.SshClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/** Opt-in wire test against a disposable SFTP server forwarded to this device by the test harness. */
@RunWith(AndroidJUnit4::class)
class SftpWireIntegrationTest {
    @Test fun authenticatedUploadPreservesExistingFileAndStreamsUnicodeFilename() {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("sftpPort")?.toIntOrNull()
        val fingerprint = args.getString("sftpFingerprint")
        assumeTrue("Requires a disposable local SFTP server", port != null && !fingerprint.isNullOrBlank())
        val hosts = File.createTempFile("sftp-wire-hosts", ".txt", targetContext.cacheDir)
        var trusted = false
        val ssh = SshClient(object : SshClient.Observer {
            override fun getKnownSshHostsFile() = hosts
            override fun getSshPassword() = "avnc-integration-only"
            override fun getSshKeyPassword() = ""
            override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
                assertTrue(isNewHost)
                assertTrue(message.contains(checkNotNull(fingerprint)))
                trusted = true
                return true
            }
        })
        try {
            val client = ssh.openFileTransfer(ServerProfile(sshHost = "127.0.0.1", sshPort = checkNotNull(port),
                    sshUsername = "avnc-test", sshAuthType = ServerProfile.SSH_AUTH_PASSWORD))
            try {
                val engine = SftpUploadEngine(TrileadUploadRemote(client))
                val payload = ByteArray(200_003) { (it % 251).toByte() }
                val first = engine.upload("report.bin", ByteArrayInputStream(payload), payload.size.toLong())
                assertEquals("/Downloads/report (1).bin", first)
                assertEquals(payload.size.toLong(), client.stat(first).size.toLong())
                val second = engine.upload("文档-AVNC.bin", ByteArrayInputStream(payload), null)
                assertEquals("/Downloads/文档-AVNC.bin", second)
                val buffer = ByteArray(8192)
                var offset = 0
                val handle = client.openFileRO(second)
                try {
                    while (offset < payload.size) {
                        val length = client.read(handle, offset.toLong(), buffer, 0,
                                minOf(buffer.size, payload.size - offset))
                        assertTrue(length > 0)
                        assertArrayEquals(payload.copyOfRange(offset, offset + length), buffer.copyOf(length))
                        offset += length
                    }
                } finally { client.closeFile(handle) }
                assertEquals(payload.size, offset)
                assertEquals(4L, client.stat("/Downloads/report.bin").size.toLong())
            } finally { client.close() }
            assertTrue(trusted)
        } finally { ssh.close(); hosts.delete() }
    }
}
