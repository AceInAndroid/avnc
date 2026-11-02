/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.instrumentation
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.targetApp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EditorViewModelStateTest {
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun defaultsAndDiscardDoNotMutateTheCaller() = main {
        val source = ServerProfile()
        val vm = EditorViewModel(targetApp, SavedStateHandle(), source)
        assertEquals(5900, vm.profile.port)
        assertEquals("auto", vm.profile.remotePlatform)
        assertFalse(vm.hasUnsavedChanges())
        vm.profile.host = "edited.example"
        vm.profile.password = "draft-only"
        vm.useRepeater.value = true
        vm.idOnRepeater.value = "123"
        vm.readOnly.value = true
        assertTrue(vm.hasUnsavedChanges())
        assertEquals("", source.host)
        assertEquals("", source.password)
        vm.discardChanges()
        assertEquals(source, vm.prepareProfileForSave())
        assertFalse(vm.hasUnsavedChanges())
    }

    @Test fun generatedIdAndSingleConnectSurviveRepeatedSubmitAndResultObservation() {
        val pending = CompletableDeferred<ServerProfile>()
        lateinit var vm: EditorViewModel
        var writes = 0
        val connections = mutableListOf<ServerProfile>()
        main {
            vm = EditorViewModel(targetApp, SavedStateHandle(), ServerProfile(host = "test.example"))
            val persist: suspend (ServerProfile) -> ServerProfile = { writes++; pending.await() }
            assertTrue(vm.submit(true, persist) { connections.add(it) })
            assertFalse(vm.submit(true, persist) { connections.add(it) })
            assertEquals(EditorViewModel.SaveState.SAVING, vm.saveState.value)
            assertNull(vm.savedProfile.value)
        }
        pending.complete(ServerProfile(ID = 42L, host = "test.example"))
        instrumentation.waitForIdleSync()
        main {
            assertEquals(1, writes)
            assertEquals(listOf(42L), connections.map { it.ID })
            assertEquals(42L, vm.profile.ID)
            assertFalse(vm.hasUnsavedChanges())
            assertEquals(EditorViewModel.SaveState.SAVED, vm.saveState.value)
            val resultObserver = androidx.lifecycle.Observer<ServerProfile?> { }
            vm.savedProfile.observeForever(resultObserver)
            vm.savedProfile.removeObserver(resultObserver)
            vm.savedProfile.observeForever(resultObserver)
            vm.savedProfile.removeObserver(resultObserver)
            assertEquals(1, connections.size)
            assertFalse(vm.submit(true, { error("Must not save twice") }) { error("Must not connect twice") })
        }
    }

    @Test fun failedSaveKeepsDraftAndExplicitRetryCanSaveOnly() {
        lateinit var vm: EditorViewModel
        main {
            vm = EditorViewModel(targetApp, SavedStateHandle(), ServerProfile(host = "original.example"))
            vm.profile.host = "draft.example"
            vm.profile.password = "unchanged password"
            vm.submit(true, { throw IOException("disk full") }) { fail("A failed save must not connect") }
        }
        instrumentation.waitForIdleSync()
        main {
            assertEquals(EditorViewModel.SaveState.ERROR, vm.saveState.value)
            assertEquals("disk full", vm.saveError.value)
            assertEquals("draft.example", vm.profile.host)
            assertEquals("unchanged password", vm.profile.password)
            assertNull(vm.savedProfile.value)
            assertTrue(vm.hasUnsavedChanges())
            assertTrue(vm.submit(false, { it.copy(ID = 13L) }) { fail("Save only must not connect") })
        }
        instrumentation.waitForIdleSync()
        main {
            assertEquals(EditorViewModel.SaveState.SAVED, vm.saveState.value)
            assertEquals(13L, vm.savedProfile.value?.ID)
            assertNull(vm.saveError.value)
        }
    }

    @Test fun restoredStateKeepsDraftExpansionAndOriginalDirtyBaseline() = main {
        val source = ServerProfile(ID = 9L, host = "original.example", remoteInputOverrides = "{\"pointerSpeed\":2.0}")
        val handle = SavedStateHandle()
        val first = EditorViewModel(targetApp, handle, source)
        first.profile.host = "rotated.example"
        first.profile.password = "draft"
        first.profile.sshPrivateKey = "imported-key"
        first.enableWol.value = true
        first.advancedExpanded.value = true
        first.pictureExpanded.value = true
        first.readOnly.value = true
        first.prepareProfileForSave()
        val restoredValues = handle.keys().associateWith { key ->
            handle.get<Any>(key).let { if (it is ServerProfile) it.copy() else it }
        }
        val restored = EditorViewModel(targetApp, SavedStateHandle(restoredValues), source)
        assertEquals("rotated.example", restored.profile.host)
        assertEquals("draft", restored.profile.password)
        assertEquals("imported-key", restored.profile.sshPrivateKey)
        assertTrue(restored.enableWol.value == true)
        assertTrue(restored.advancedExpanded.value == true)
        assertTrue(restored.pictureExpanded.value == true)
        assertTrue(restored.readOnly.value == true)
        assertTrue(restored.hasUnsavedChanges())
        restored.discardChanges()
        assertEquals(source, restored.prepareProfileForSave())
    }

    @Test fun missingGeneratedIdIsAnErrorAndNeverConnects() {
        lateinit var vm: EditorViewModel
        main {
            vm = EditorViewModel(targetApp, SavedStateHandle(), ServerProfile(host = "test.example"))
            vm.submit(true, { it }) { fail("An unpersisted profile must not connect") }
        }
        instrumentation.waitForIdleSync()
        main {
            assertEquals(EditorViewModel.SaveState.ERROR, vm.saveState.value)
            assertNull(vm.savedProfile.value)
            assertEquals(0L, vm.profile.ID)
        }
    }
}
