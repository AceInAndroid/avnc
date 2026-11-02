/*
 * Copyright (c) 2024  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gaurav.avnc.model.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * ViewModel for profile editor
 */
class EditorViewModel(app: Application, private val state: SavedStateHandle, initialProfile: ServerProfile) : BaseViewModel(app) {

    enum class SaveState { EDITING, INVALID, SAVING, ERROR, SAVED }

    /**
     * Profile being edited
     */
    var profile: ServerProfile = state["profile"] ?: initialProfile.copy()
        private set
    private var baseline: ServerProfile = state["baseline"] ?: initialProfile.copy()

    val saveState = MutableLiveData(SaveState.EDITING)
    val saveError = MutableLiveData<String?>(null)
    val savedProfile = MutableLiveData<ServerProfile?>(null)
    val advancedExpanded = state.getLiveData("advancedExpanded", false)
    val connectionExpanded = state.getLiveData("connectionExpanded", true)
    val pictureExpanded = state.getLiveData("pictureExpanded", false)
    val inputExpanded = state.getLiveData("inputExpanded", false)
    val otherExpanded = state.getLiveData("otherExpanded", false)

    init {
        state["profile"] = profile
        state["baseline"] = baseline
    }

    /**
     * While most fields of [profile] are straightforward to edit, some require
     * more complex handling, and live feedback in UI.
     * For these, we have to use dedicated LiveData fields.
     */
    val useRepeater = state.getLiveData("useRepeater", profile.useRepeater)
    val idOnRepeater = state.getLiveData("idOnRepeater", if (profile.useRepeater) profile.idOnRepeater.toString() else "")
    val useRawEncoding = state.getLiveData("useRawEncoding", profile.useRawEncoding)
    val enableWol = state.getLiveData("enableWol", profile.enableWol)
    val useSshTunnel = state.getLiveData("useSshTunnel", profile.channelType == ServerProfile.CHANNEL_SSH_TUNNEL)
    val sshUsePassword = state.getLiveData("sshUsePassword", profile.sshAuthType == ServerProfile.SSH_AUTH_PASSWORD)
    val sshUsePrivateKey = state.getLiveData("sshUsePrivateKey", profile.sshAuthType == ServerProfile.SSH_AUTH_KEY)
    val hasSshPrivateKey = state.getLiveData("hasSshPrivateKey", profile.sshPrivateKey.isNotBlank())
    val readOnly = state.getLiveData("readOnly", profile.viewMode == ServerProfile.VIEW_MODE_NO_INPUT)


    fun prepareProfileForSave(): ServerProfile {
        profile.useRepeater = useRepeater.value == true
        profile.idOnRepeater = idOnRepeater.value?.toIntOrNull() ?: 0
        profile.useRawEncoding = useRawEncoding.value == true
        profile.enableWol = enableWol.value == true
        profile.channelType = if (useSshTunnel.value == true) ServerProfile.CHANNEL_SSH_TUNNEL else ServerProfile.CHANNEL_TCP
        profile.sshAuthType = if (sshUsePassword.value == true) ServerProfile.SSH_AUTH_PASSWORD else ServerProfile.SSH_AUTH_KEY
        if (readOnly.value == true) profile.viewMode = ServerProfile.VIEW_MODE_NO_INPUT
        else if (profile.viewMode == ServerProfile.VIEW_MODE_NO_INPUT) profile.viewMode = ServerProfile.VIEW_MODE_NORMAL
        return profile
    }

    fun markInvalid() { saveState.value = SaveState.INVALID }

    fun hasUnsavedChanges(): Boolean = prepareProfileForSave() != baseline

    /** The source passed by Home stays untouched, including when a draft is discarded. */
    fun discardChanges() {
        if (saveState.value == SaveState.SAVING) return
        profile = baseline.copy()
        state["profile"] = profile
        useRepeater.value = profile.useRepeater
        idOnRepeater.value = if (profile.useRepeater) profile.idOnRepeater.toString() else ""
        useRawEncoding.value = profile.useRawEncoding
        enableWol.value = profile.enableWol
        useSshTunnel.value = profile.channelType == ServerProfile.CHANNEL_SSH_TUNNEL
        sshUsePassword.value = profile.sshAuthType == ServerProfile.SSH_AUTH_PASSWORD
        sshUsePrivateKey.value = profile.sshAuthType == ServerProfile.SSH_AUTH_KEY
        hasSshPrivateKey.value = profile.sshPrivateKey.isNotBlank()
        readOnly.value = profile.viewMode == ServerProfile.VIEW_MODE_NO_INPUT
        saveError.value = null
        saveState.value = SaveState.EDITING
    }

    /** Busy is claimed before launching, so rapid taps cannot enqueue duplicate inserts. */
    fun submit(connectAfterSave: Boolean,
               persist: suspend (ServerProfile) -> ServerProfile,
               connect: (ServerProfile) -> Unit): Boolean {
        if (saveState.value == SaveState.SAVING || saveState.value == SaveState.SAVED) return false
        val draft = prepareProfileForSave().copy(host = profile.host.trim(), name = profile.name.trim())
        saveError.value = null
        saveState.value = SaveState.SAVING
        viewModelScope.launch {
            val saved = try {
                persist(draft).also { check(it.isSaved()) { "Connection was not saved" } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saveError.value = error.message
                saveState.value = SaveState.ERROR
                return@launch
            }
            profile = saved.copy()
            baseline = saved.copy()
            state["profile"] = profile
            state["baseline"] = baseline
            saveState.value = SaveState.SAVED
            // This effect belongs to the surviving ViewModel, never to replayed UI observers.
            if (connectAfterSave) connect(saved)
            savedProfile.value = saved
        }
        return true
    }
}
