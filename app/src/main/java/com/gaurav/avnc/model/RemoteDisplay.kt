/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import android.graphics.RectF
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class DisplayViewMode(val id: String) {
    SINGLE("single"), ALL("all");
    companion object { fun from(value: String) = entries.firstOrNull { it.id == value } ?: SINGLE }
}

enum class StreamingPreset(val id: String) {
    LEGACY("legacy"), SMOOTH("smooth"), HD("hd");
    companion object { fun from(value: String) = entries.firstOrNull { it.id == value } ?: LEGACY }
}

@Serializable
data class RemoteDisplay(val id: String, val name: String = "", val x: Int, val y: Int,
                         val width: Int, val height: Int) {
    val bounds: RectF get() = RectF(x.toFloat(), y.toFloat(), (x.toLong() + width).toFloat(), (y.toLong() + height).toFloat())
    fun fits(w: Int, h: Int) = id.isNotBlank() && width > 0 && height > 0 && x >= 0 && y >= 0 &&
            x.toLong() + width <= w && y.toLong() + height <= h
    fun contains(px: Int, py: Int) = px >= x && py >= y && px.toLong() < x.toLong() + width && py.toLong() < y.toLong() + height
}

@Serializable
data class RemoteDisplayLayout(val framebufferWidth: Int = 0, val framebufferHeight: Int = 0,
                               val displays: List<RemoteDisplay> = emptyList(), val source: String = "unavailable") {
    fun isValid() = framebufferWidth > 0 && framebufferHeight > 0 && displays.isNotEmpty() && displays.size <= 64 &&
            displays.all { it.fits(framebufferWidth, framebufferHeight) } && displays.map { it.id }.distinct().size == displays.size
    fun encode(): String = json.encodeToString(this)
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(value: String): RemoteDisplayLayout = runCatching { json.decodeFromString<RemoteDisplayLayout>(value) }.getOrDefault(RemoteDisplayLayout())
        fun fromProtocol(width: Int, height: Int, screens: IntArray): RemoteDisplayLayout {
            val fallback = RemoteDisplayLayout(width, height)
            if (screens.isEmpty() || screens.size % 5 != 0 || screens.size > 320) return fallback
            val list = screens.toList().chunked(5).map { (id, x, y, w, h) ->
                RemoteDisplay(id.toUInt().toString(), "", x, y, w, h)
            }
            return RemoteDisplayLayout(width, height, list, "protocol").takeIf { it.isValid() } ?: fallback
        }
    }
}
