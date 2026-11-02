/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class SftpUploadEngineTest {
    private class Remote : SftpUploadEngine.Remote {
        val dirs = mutableSetOf("/Users/test", "/Users/test/Downloads")
        val files = mutableMapOf<String, ByteArray>()
        var maxChunk = 0
        var failWrites = false
        var raceDestination = false
        var renamed = false
        var onDirectory: ((String) -> Unit)? = null
        override fun home() = "/Users/test"
        override fun directory(path: String): Boolean? {
            onDirectory?.invoke(path)
            return if (path in dirs) true else if (path in files) false else null
        }
        override fun mkdir(path: String) { check(directory(path) == null); dirs += path }
        override fun create(path: String): SftpUploadEngine.Sink {
            check(path !in files)
            files[path] = byteArrayOf()
            return object : SftpUploadEngine.Sink {
                val output = ByteArrayOutputStream()
                override fun write(offset: Long, bytes: ByteArray, length: Int) {
                    if (failWrites) throw IOException("disk full")
                    assertEquals(output.size().toLong(), offset)
                    maxChunk = maxOf(maxChunk, length)
                    output.write(bytes, 0, length)
                    files[path] = output.toByteArray()
                }
                override fun close() = Unit
            }
        }
        override fun rename(source: String, target: String) {
            if (raceDestination) files[target] = "Other writer".toByteArray()
            if (directory(target) != null) throw IOException("Already exists")
            files[target] = checkNotNull(files.remove(source)); renamed = true
        }
        override fun remove(path: String) { files.remove(path) }
        override fun rmdir(path: String) { dirs.remove(path) }
        fun assertNoParts() {
            assertTrue(files.keys.none { "/.avnc-upload-" in it })
            assertTrue(dirs.none { "/.avnc-upload-" in it })
        }
    }

    @Test fun streamsIntoDownloadsAndPreservesExistingFilesWithNumberedNames() {
        val remote = Remote()
        remote.files["/Users/test/Downloads/report.pdf"] = byteArrayOf(1)
        remote.files["/Users/test/Downloads/report (1).pdf"] = byteArrayOf(2)
        val data = ByteArray(200_003) { (it % 251).toByte() }
        val sent = mutableListOf<Long>()
        val result = SftpUploadEngine(remote).upload("report.pdf", ByteArrayInputStream(data), data.size.toLong(), progress = { sent += it })
        assertEquals("/Users/test/Downloads/report (2).pdf", result)
        assertArrayEquals(data, remote.files[result])
        assertArrayEquals(byteArrayOf(1), remote.files["/Users/test/Downloads/report.pdf"])
        assertArrayEquals(byteArrayOf(2), remote.files["/Users/test/Downloads/report (1).pdf"])
        assertEquals(data.size.toLong(), sent.last())
        assertTrue(remote.maxChunk <= 32768)
        assertTrue(remote.renamed)
        remote.assertNoParts()
    }

    @Test fun cancellationAndDiskFailureRemovePartialFilesWithoutPublishing() {
        for (diskFailure in listOf(false, true)) {
            val remote = Remote().apply { failWrites = diskFailure }
            var sent = 0L
            try {
                SftpUploadEngine(remote).upload("data.bin", ByteArrayInputStream(ByteArray(100_000)), null,
                        checkActive = { if (!diskFailure && sent > 0) throw CancellationException() },
                        progress = { sent = it })
                fail("Upload should fail")
            } catch (_: CancellationException) { assertFalse(diskFailure) }
              catch (_: IOException) { assertTrue(diskFailure) }
            assertFalse(remote.renamed)
            remote.assertNoParts()
            assertTrue(remote.files.isEmpty())
        }
    }

    @Test fun sizeMismatchDoesNotPublishTruncatedFile() {
        val remote = Remote()
        try {
            SftpUploadEngine(remote).upload("data", ByteArrayInputStream(byteArrayOf(1)), 5L)
            fail("Expected size mismatch")
        } catch (_: IOException) { }
        assertFalse(remote.renamed)
        remote.assertNoParts()
    }

    @Test fun competingDestinationCannotBeOverwrittenDuringRename() {
        val remote = Remote().apply { raceDestination = true }
        try {
            SftpUploadEngine(remote).upload("data", ByteArrayInputStream(byteArrayOf(1)), 1L)
            fail("Expected destination race failure")
        } catch (_: IOException) { }
        assertArrayEquals("Other writer".toByteArray(), remote.files["/Users/test/Downloads/data"])
        assertFalse(remote.renamed)
        remote.assertNoParts()
    }

    @Test fun safeNamesCannotEscapeDownloadsAndStayWithinFilesystemLimits() {
        assertEquals(".._.._secret.txt", SftpUploadEngine.safeName("../../secret.txt"))
        assertEquals("File", SftpUploadEngine.safeName(".."))
        assertEquals("a_b_c", SftpUploadEngine.safeName("a\\b\u0000c"))
        val longName = SftpUploadEngine.safeName("😀".repeat(200))
        assertTrue(longName.toByteArray(Charsets.UTF_8).size <= 180)
        assertFalse(longName.last().isHighSurrogate())
    }

    @Test fun cancellationDuringDuplicateScanStopsBeforeTheNextProbeAndNeverPublishes() {
        val remote = Remote()
        repeat(20) { index ->
            val name = if (index == 0) "data" else "data ($index)"
            remote.files["/Users/test/Downloads/$name"] = byteArrayOf(7)
        }
        var cancelled = false
        var probes = 0
        remote.onDirectory = { path ->
            if (path.startsWith("/Users/test/Downloads/data")) {
                probes++
                if (probes == 3) cancelled = true
            }
        }
        try {
            SftpUploadEngine(remote).upload("data", ByteArrayInputStream(byteArrayOf(1)), 1L,
                    checkActive = { if (cancelled) throw CancellationException() })
            fail("Cancellation must stop the filename scan")
        } catch (_: CancellationException) { }
        assertEquals(3, probes)
        assertFalse(remote.renamed)
        assertEquals(20, remote.files.size)
        remote.assertNoParts()
    }

    @Test fun cancellationAfterFindingAnUnusedNameStillPreventsPublishing() {
        val remote = Remote()
        var cancelled = false
        remote.onDirectory = { if (it == "/Users/test/Downloads/data") cancelled = true }
        try {
            SftpUploadEngine(remote).upload("data", ByteArrayInputStream(byteArrayOf(1)), 1L,
                    checkActive = { if (cancelled) throw CancellationException() })
            fail("Cancellation after stat must prevent rename")
        } catch (_: CancellationException) { }
        assertFalse(remote.renamed)
        assertTrue(remote.files.isEmpty())
        remote.assertNoParts()
    }

    @Test fun cancellationInsideSourceReadPreventsTheNextRemoteWrite() {
        val remote = Remote()
        var cancelled = false
        val source = object : InputStream() {
            override fun read(): Int = error("Use bounded reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                cancelled = true
                bytes[offset] = 42
                return 1
            }
        }
        try {
            SftpUploadEngine(remote).upload("data", source, 1L,
                    checkActive = { if (cancelled) throw CancellationException() })
            fail("Cancellation during the read must prevent writing returned bytes")
        } catch (_: CancellationException) { }
        assertEquals(0, remote.maxChunk)
        assertFalse(remote.renamed)
        remote.assertNoParts()
    }

    @Test fun missingDownloadsIsCreatedAndEmptyFileCanBeSent() {
        val remote = Remote().apply { dirs.remove("/Users/test/Downloads") }
        val path = SftpUploadEngine(remote).upload("empty", ByteArrayInputStream(byteArrayOf()), 0L)
        assertTrue("/Users/test/Downloads" in remote.dirs)
        assertArrayEquals(byteArrayOf(), remote.files[path])
        remote.assertNoParts()
    }
}
