/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.util

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.gaurav.avnc.model.RemoteInputConfig
import com.gaurav.avnc.model.RemoteInputOverrides
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.model.ServerProfile

class RemoteInputPreferences(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        // Snapshot explicitly saved legacy settings once; do not multiply scroll sensitivity twice.
        if (!prefs.getBoolean("remote_input_migrated", false)) {
            val legacy = RemoteInputOverrides(
                    pointerSpeed = if (prefs.contains("gesture_swipe_sensitivity")) prefs.getInt("gesture_swipe_sensitivity", 10) / 10f else null,
                    scrollSpeed = if (prefs.contains("gesture_swipe_sensitivity")) prefs.getInt("gesture_swipe_sensitivity", 10) / 10f else null,
                    invertVertical = if (prefs.contains("invert_vertical_scrolling")) prefs.getBoolean("invert_vertical_scrolling", false) else null,
                    inertia = if (prefs.contains("remote_scroll_inertia") && !prefs.getBoolean("remote_scroll_inertia", true)) "off" else null,
                    threeFinger = if (prefs.contains("gesture_swipe3")) "legacy" else null)
            prefs.edit {
                RemotePlatform.entries.filter { it != RemotePlatform.AUTO }.forEach {
                    if (!prefs.contains(key(it))) putString(key(it), legacy.encode())
                }
                putBoolean("remote_input_migrated", true)
            }
        }
    }

    private fun key(platform: RemotePlatform) = "remote_input_template_${platform.id}"
    fun getOverrides(platform: RemotePlatform) = RemoteInputOverrides.decode(prefs.getString(key(platform), "{}") ?: "{}")
    fun template(platform: RemotePlatform) = RemoteInputConfig.defaults(platform).apply(getOverrides(platform))
    fun saveTemplate(platform: RemotePlatform, overrides: RemoteInputOverrides) = prefs.edit { putString(key(platform), overrides.encode()) }
    fun resetTemplate(platform: RemotePlatform) = saveTemplate(platform, RemoteInputOverrides())
    fun resolve(profile: ServerProfile, appleServer: Boolean = false): RemoteInputConfig {
        val choice = RemotePlatform.from(profile.remotePlatform)
        val platform = if (choice != RemotePlatform.AUTO) choice else if (appleServer) RemotePlatform.MAC else RemotePlatform.GENERIC
        return template(platform).apply(RemoteInputOverrides.decode(profile.remoteInputOverrides))
    }
}
