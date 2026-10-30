/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.model

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacActionTest {
    @Test fun macControlsUseNativeShortcutDefaults() {
        val config = RemoteInputConfig.defaults(RemotePlatform.MAC)
        assertEquals(RemoteShortcut(KeyEvent.KEYCODE_F3, listOf(KeyEvent.KEYCODE_CTRL_LEFT)), config.shortcut(RemoteAction.DOCK))
        assertEquals(RemoteShortcut(KeyEvent.KEYCODE_DPAD_UP, listOf(KeyEvent.KEYCODE_CTRL_LEFT)), config.shortcut(RemoteAction.OVERVIEW))
        assertEquals(RemoteShortcut(KeyEvent.KEYCODE_SPACE), config.shortcut(RemoteAction.QUICK_LOOK))
        val fullscreen = requireNotNull(config.shortcut(RemoteAction.TOGGLE_FULLSCREEN))
        assertEquals(KeyEvent.KEYCODE_F, fullscreen.key)
        assertEquals(setOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_META_LEFT), fullscreen.modifiers.toSet())
    }

    @Test fun eachNewMacActionCanUseItsExistingShortcutOverrideMechanism() {
        val actions = listOf(RemoteAction.DOCK, RemoteAction.QUICK_LOOK, RemoteAction.TOGGLE_FULLSCREEN)
        for (action in actions) {
            val custom = RemoteShortcut(KeyEvent.KEYCODE_F9, listOf(KeyEvent.KEYCODE_ALT_LEFT))
            val config = RemoteInputConfig.defaults(RemotePlatform.MAC)
                    .apply(RemoteInputOverrides(shortcuts = mapOf(action.name to custom)))
            assertEquals(custom, config.shortcut(action))
        }
    }
}
