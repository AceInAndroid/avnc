/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.transfer

import com.trilead.ssh2.SFTPException
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.SFTPv3FileAttributes
import com.trilead.ssh2.SFTPv3FileHandle
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** Streaming file upload, with no shell, no overwrite and no partly-written final filename. */
internal class SftpUploadEngine(private val remote: Remote) {
    interface Remote {
        fun home(): String
        /** null means no such file; permission/network errors must propagate. */
        fun directory(path: String): Boolean?
        fun mkdir(path: String)
        fun create(path: String): Sink
        /** SFTP v3 rename fails if the destination already exists. */
        fun rename(source: String, target: String)
        fun remove(path: String)
        fun rmdir(path: String)
    }
    interface Sink : Closeable { fun write(offset: Long, bytes: ByteArray, length: Int) }

    fun upload(name: String, source: InputStream, expectedSize: Long?,
               checkActive: () -> Unit = {}, progress: (Long) -> Unit = {}): String {
        checkActive()
        val canonicalHome = remote.home()
        require(canonicalHome.startsWith('/') && '\u0000' !in canonicalHome) { "Invalid remote home directory" }
        val home = canonicalHome.trimEnd('/')
        val downloads = "$home/Downloads"
        checkActive()
        when (remote.directory(downloads)) {
            null -> { checkActive(); remote.mkdir(downloads) }
            false -> throw IOException("Downloads is not a directory")
            true -> Unit
        }
        val safeName = safeName(name)
        val tempDir = "$downloads/.avnc-upload-${UUID.randomUUID()}"
        checkActive()
        remote.mkdir(tempDir) // Atomic reservation; existing directories are never reused.
        val tempFile = "$tempDir/upload.part"
        var completed = false
        try {
            checkActive()
            remote.create(tempFile).use { sink ->
                val bytes = ByteArray(32 * 1024)
                var offset = 0L
                while (true) {
                    checkActive()
                    val count = source.read(bytes)
                    checkActive()
                    if (count < 0) break
                    if (count == 0) continue
                    sink.write(offset, bytes, count)
                    offset += count
                    progress(offset)
                }
                if (expectedSize != null && expectedSize >= 0 && offset != expectedSize)
                    throw IOException("Source size changed during upload")
            }
            checkActive()
            var destination: String? = null
            for (index in 0..9999) {
                checkActive()
                val candidate = "$downloads/${numbered(safeName, index)}"
                if (remote.directory(candidate) != null) continue
                // A competing writer after the existence check makes SFTP v3 rename fail;
                // it never truncates/replaces an existing destination.
                checkActive()
                remote.rename(tempFile, candidate)
                destination = candidate
                completed = true
                break
            }
            return destination ?: throw IOException("No unused destination filename")
        } finally {
            if (!completed) runCatching { remote.remove(tempFile) }
            runCatching { remote.rmdir(tempDir) }
        }
    }

    companion object {
        fun safeName(name: String): String {
            val value = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
                    .joinToString("").trim().trimEnd('.', ' ')
            var clipped = value
            while (clipped.toByteArray(Charsets.UTF_8).size > 180) clipped = clipped.dropLast(1)
            if (clipped.lastOrNull()?.isHighSurrogate() == true) clipped = clipped.dropLast(1)
            return clipped.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "File"
        }
        private fun numbered(name: String, index: Int): String {
            if (index == 0) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            return name.substring(0, dot) + " ($index)" + name.substring(dot)
        }
    }
}

internal class TrileadUploadRemote(private val client: SFTPv3Client) : SftpUploadEngine.Remote {
    override fun home() = client.canonicalPath(".")
    override fun directory(path: String): Boolean? = try {
        // lstat also treats a dangling symbolic link as occupied, preventing replacement.
        val attributes = client.lstat(path)
        if (attributes.isSymlink) try {
            client.stat(path).isDirectory
        } catch (e: SFTPException) {
            if (e.serverErrorCode == 2) false else throw e
        } else attributes.isDirectory
    } catch (e: SFTPException) {
        if (e.serverErrorCode == 2) null else throw e
    }
    override fun mkdir(path: String) = client.mkdir(path, 448 /*0700*/)
    override fun create(path: String): SftpUploadEngine.Sink {
        val handle: SFTPv3FileHandle = client.createFile(path, SFTPv3FileAttributes().apply { permissions = 384 /*0600*/ })
        return object : SftpUploadEngine.Sink {
            override fun write(offset: Long, bytes: ByteArray, length: Int) = client.write(handle, offset, bytes, 0, length)
            override fun close() = client.closeFile(handle)
        }
    }
    override fun rename(source: String, target: String) = client.mv(source, target)
    override fun remove(path: String) = client.rm(path)
    override fun rmdir(path: String) = client.rmdir(path)
}
