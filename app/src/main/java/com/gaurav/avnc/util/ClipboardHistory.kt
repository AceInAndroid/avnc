/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.util

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Local text history, excluded from Android and connection backups. Call from an IO thread. */
class ClipboardHistory(context: Context) {
    data class Entry(val id: String, val text: String, val time: Long)
    private val file = AtomicFile(File(context.noBackupFilesDir, "clipboard-history.json"))

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_TEXT_BYTES = 64 * 1024
        private const val MAX_TOTAL_BYTES = 512 * 1024
        fun accepts(text: String) = text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES
        private val lock = Any()
    }

    fun entries(): List<Entry> = synchronized(lock) { read() }

    fun record(text: String, sensitive: Boolean, time: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        if (sensitive || !accepts(text)) return@synchronized read()
        val entries = read().filterNot { it.text == text }.toMutableList()
        entries.add(0, Entry(UUID.randomUUID().toString(), text, time))
        var bytes = 0
        val bounded = entries.take(MAX_ENTRIES).takeWhile {
            bytes += it.text.toByteArray(Charsets.UTF_8).size
            bytes <= MAX_TOTAL_BYTES
        }
        write(bounded)
        bounded
    }

    fun remove(id: String): List<Entry> = synchronized(lock) {
        read().filterNot { it.id == id }.also { write(it) }
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun read(): List<Entry> {
        if (!file.baseFile.exists()) return emptyList()
        return runCatching {
            require(file.baseFile.length() <= MAX_TOTAL_BYTES * 8L)
            val json = JSONArray(file.readFully().toString(Charsets.UTF_8))
            var bytes = 0
            (0 until minOf(json.length(), MAX_ENTRIES)).mapNotNull { index ->
                val item = json.getJSONObject(index)
                val text = item.getString("text")
                bytes += text.toByteArray(Charsets.UTF_8).size
                if (!accepts(text) || bytes > MAX_TOTAL_BYTES) null
                else Entry(item.getString("id"), text, item.getLong("time"))
            }.distinctBy { it.text }
        }.getOrDefault(emptyList())
    }

    private fun write(entries: List<Entry>) {
        val json = JSONArray()
        entries.forEach { item -> json.put(JSONObject().put("id", item.id).put("text", item.text).put("time", item.time)) }
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}
