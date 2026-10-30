/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteInputProfileTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test fun oldProfileImportReceivesAutomaticPlatformAndEmptyOverrides() {
        val profile = json.decodeFromString<ServerProfile>(
                """{"ID":4,"name":"Old desktop","host":"192.0.2.1","port":5901,"gestureStyle":"touchpad","flags":12}""")
        assertEquals("auto", profile.remotePlatform)
        assertEquals("{}", profile.remoteInputOverrides)
        assertEquals("Old desktop", profile.name)
        assertEquals(5901, profile.port)
        assertEquals("touchpad", profile.gestureStyle)
        assertEquals(12L, profile.flags)
    }

    @Test fun newProfileExportImportPreservesExplicitAndInheritedFields() {
        val overrides = RemoteInputOverrides(pointerSpeed = 1.8f, horizontalSpeed = .6f,
                invertVertical = false, acceleration = true, inertia = "off",
                shortcuts = mapOf("COPY" to RemoteShortcut(KeyEvent.KEYCODE_C, listOf(KeyEvent.KEYCODE_META_LEFT))))
        val original = ServerProfile(ID = 8, name = "Mac 工作站", host = "192.0.2.2",
                remotePlatform = "mac", remoteInputOverrides = overrides.encode())
        val restored = json.decodeFromString<ServerProfile>(json.encodeToString(original))
        assertEquals(original, restored)
        val restoredOverrides = RemoteInputOverrides.decode(restored.remoteInputOverrides)
        assertEquals(overrides, restoredOverrides)
        assertNull(restoredOverrides.scrollSpeed)
        assertEquals(false, restoredOverrides.invertVertical)
        assertEquals("mac", restored.remotePlatform)
    }

    @Test fun importedFutureFieldsKeepKnownProfileAndInputSettings() {
        val profile = json.decodeFromString<ServerProfile>("""{
            "name":"Future desktop","host":"192.0.2.3","remotePlatform":"windows",
            "remoteInputOverrides":"{\"scrollSpeed\":2.0,\"futureGesture\":\"anything\"}",
            "futureProfileOption":{"version":2}
        }""")
        assertEquals("windows", profile.remotePlatform)
        assertEquals(2f, RemoteInputOverrides.decode(profile.remoteInputOverrides).scrollSpeed!!, 0f)
        assertEquals(profile, json.decodeFromString<ServerProfile>(json.encodeToString(profile)))
    }
}
