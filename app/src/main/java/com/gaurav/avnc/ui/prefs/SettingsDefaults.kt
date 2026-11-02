/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.XmlRes
import androidx.core.content.edit
import org.xmlpull.v1.XmlPullParser

/** Restores only persisted controls declared by this page, using its XML defaults. */
object SettingsDefaults {
    private const val APP = "http://schemas.android.com/apk/res-auto"
    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    fun restore(context: Context, preferences: SharedPreferences, @XmlRes page: Int): Map<String, Any> {
        val defaults = linkedMapOf<String, Any>()
        context.resources.getXml(page).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val key = parser.getAttributeValue(APP, "key") ?: parser.getAttributeValue(ANDROID, "key") ?: continue
                // Changing this lock always requires authentication through the existing control.
                if (key == "lock_saved_server") continue
                val namespace = if (parser.getAttributeValue(APP, "defaultValue") != null) APP else ANDROID
                val raw = parser.getAttributeValue(namespace, "defaultValue") ?: continue
                val resource = parser.getAttributeResourceValue(namespace, "defaultValue", 0)
                val value: Any = when (parser.name.substringAfterLast('.')) {
                    "SwitchPreference", "SwitchPreferenceCompat" ->
                        if (resource != 0) context.resources.getBoolean(resource) else raw.toBooleanStrict()
                    "SeekBarPreference" ->
                        if (resource != 0) context.resources.getInteger(resource) else raw.toInt()
                    "ListPreference", "ListPreferenceEx" ->
                        if (resource != 0) context.getString(resource) else raw
                    else -> continue
                }
                defaults[key] = value
            }
        }
        preferences.edit {
            defaults.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                }
            }
        }
        return defaults
    }
}
