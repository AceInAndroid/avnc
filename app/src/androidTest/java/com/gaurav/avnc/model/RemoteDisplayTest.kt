/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteDisplayTest {
    private val desktop = RemoteDisplayLayout(3840, 1440, listOf(
            RemoteDisplay("1", "Mac", 0, 0, 1920, 1080),
            RemoteDisplay("4294967295", "External", 1920, 0, 1920, 1440)), "manual")

    @Test fun protocolKeepsEveryDisplayAndUnsignedIds() {
        val layout = RemoteDisplayLayout.fromProtocol(3840, 1440,
                intArrayOf(1, 0, 0, 1920, 1080, -1, 1920, 0, 1920, 1440))
        assertTrue(layout.isValid())
        assertEquals("protocol", layout.source)
        assertEquals(listOf("1", "4294967295"), layout.displays.map { it.id })
        assertEquals(1920, layout.displays[1].x)
        assertEquals(1440, layout.displays[1].height)
    }

    @Test fun missingMetadataDoesNotGuessAnEqualSplit() {
        val layout = RemoteDisplayLayout.fromProtocol(3840, 1080, intArrayOf())
        assertEquals("unavailable", layout.source)
        assertTrue(layout.displays.isEmpty())
        assertEquals(3840, layout.framebufferWidth)
    }

    @Test fun truncatedMetadataIsRejectedAsAWhole() {
        val layout = RemoteDisplayLayout.fromProtocol(3840, 1080, intArrayOf(1, 0, 0, 1920))
        assertFalse(layout.isValid())
        assertTrue(layout.displays.isEmpty())
    }

    @Test fun invalidDisplaysAndDuplicateIdsAreRejected() {
        val valid = RemoteDisplay("1", "", 0, 0, 1920, 1080)
        val invalid = listOf(valid.copy(width = 0), valid.copy(height = -1), valid.copy(x = -1),
                valid.copy(y = -1), valid.copy(x = Int.MAX_VALUE), valid.copy(width = Int.MAX_VALUE),
                valid.copy(id = ""))
        invalid.forEach { assertFalse(it.toString(), RemoteDisplayLayout(1920, 1080, listOf(it), "manual").isValid()) }
        assertFalse(desktop.copy(displays = listOf(valid, valid)).isValid())
    }

    @Test fun protocolRejectsOutOfBoundsAndDuplicateIds() {
        val invalid = listOf(
                intArrayOf(1, 0, 0, 1921, 1080),
                intArrayOf(1, 0, 0, 0, 1080),
                intArrayOf(1, 0, 0, 1920, 1080, 1, 0, 0, 1920, 1080))
        invalid.forEach { assertTrue(RemoteDisplayLayout.fromProtocol(1920, 1080, it).displays.isEmpty()) }
    }

    @Test fun manualNamesRectanglesAndSourcesRoundTrip() {
        assertEquals(desktop, RemoteDisplayLayout.decode(desktop.encode()))
        assertEquals(RemoteDisplayLayout(), RemoteDisplayLayout.decode("not-json"))
        assertEquals(RemoteDisplayLayout(), RemoteDisplayLayout.decode("{}"))
    }

    @Test fun rightAndBottomEdgesAreExclusive() {
        val screen = desktop.displays[1]
        assertTrue(screen.contains(1920, 0))
        assertTrue(screen.contains(3839, 1439))
        assertFalse(screen.contains(1919, 0))
        assertFalse(screen.contains(3840, 0))
        assertFalse(screen.contains(1920, 1440))
    }

    @Test fun boundsAreCopiesAndCannotMutateStoredDisplay() {
        val screen = desktop.displays[1]
        screen.bounds.setEmpty()
        assertEquals(1920f, screen.bounds.left, 0f)
        assertEquals(3840f, screen.bounds.right, 0f)
    }

    @Test fun newProfilesUseSingleDisplayAndSmoothStreaming() {
        val profile = ServerProfile()
        assertEquals("single", profile.displayViewMode)
        assertEquals("smooth", profile.streamingPreset)
        assertEquals("shortcut", profile.dockActivation)
        assertEquals("", profile.selectedDisplayId)
        assertEquals("{}", profile.manualDisplayLayout)
    }

    @Test fun profileExportImportPreservesDisplaySelectionAndQuality() {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val profile = ServerProfile(name = "Mac", remotePlatform = "mac", displayViewMode = "all",
                selectedDisplayId = "4294967295", manualDisplayLayout = desktop.encode(),
                streamingPreset = "hd", dockActivation = "left")
        val restored = json.decodeFromString<ServerProfile>(json.encodeToString(profile))
        assertEquals(profile, restored)
        assertEquals(desktop, RemoteDisplayLayout.decode(restored.manualDisplayLayout))
    }
}
