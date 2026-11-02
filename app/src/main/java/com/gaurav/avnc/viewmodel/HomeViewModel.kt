/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.viewmodel

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.map
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.db.ServerProfileDao
import com.gaurav.avnc.util.LiveEvent
import com.gaurav.avnc.viewmodel.service.Discovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

data class ProfilesUiState(val loading: Boolean = false,
                           val profiles: List<ServerProfile> = emptyList(),
                           val error: String? = null)

class HomeViewModel @JvmOverloads constructor(app: Application,
        private val profileDaoOverride: ServerProfileDao? = null) : BaseViewModel(app) {
    private val profilesDao get() = profileDaoOverride ?: serverProfileDao
    private var profilesJob: Job? = null
    private var sortProfiles = false
    private val mutableProfilesState = MediatorLiveData(ProfilesUiState(loading = true)).apply {
        addSource(pref.ui.sortServerList) { sorted ->
            sortProfiles = sorted
            observeProfiles()
        }
    }
    val profilesState: LiveData<ProfilesUiState> = mutableProfilesState

    fun retryProfiles() = observeProfiles()

    private fun observeProfiles() {
        profilesJob?.cancel()
        mutableProfilesState.value = ProfilesUiState(loading = true,
                profiles = mutableProfilesState.value?.profiles.orEmpty())
        profilesJob = launchMain {
            flow {
                emitAll(if (sortProfiles) profilesDao.observeSortedList() else profilesDao.observeList())
            }.catch { error ->
                if (error is CancellationException) throw error
                mutableProfilesState.value = ProfilesUiState(
                        profiles = mutableProfilesState.value?.profiles.orEmpty(),
                        error = error.localizedMessage ?: error.javaClass.simpleName)
            }.collect { profiles ->
                mutableProfilesState.value = ProfilesUiState(profiles = profiles)
            }
        }
    }

    /**
     * [ServerProfile]s stored in database.
     * Depending on the user pref, this list may be sorted by server name.
     */
    val serverProfiles: LiveData<List<ServerProfile>> = profilesState.map { it.profiles }

    /**
     * Used to find new servers.
     */
    val discovery = Discovery

    /**
     * Used for starting new VNC connections.
     */
    val newConnectionEvent = LiveEvent<ServerProfile>()

    /**
     * This event is used for editing/creating server profiles.
     * Home activity observes this event and starts profile editor when it is fired.
     */
    val editProfileEvent = LiveEvent<ServerProfile>()

    /**
     * Fired when a profile is saved to database.
     * Can be used to highlight the new profile in UI.
     */
    val profileSavedEvent = LiveEvent<ServerProfile>()

    /**
     * Fired when a profile is deleted from database.
     * This is used for notifying the user and potentially undo the deletion.
     */
    val profileDeletedEvent = LiveEvent<ServerProfile>()

    /** Failure of a user-requested save, delete or undo; no success event is emitted. */
    val operationError = LiveEvent<Throwable>()

    /**
     * Starts new connection to given profile.
     */
    fun startConnection(profile: ServerProfile) = newConnectionEvent.fire(profile)

    fun maybeConnectOnAppStart() = launchMain {
        try {
            profilesDao.getConnectableOnAppStart().firstOrNull()?.let { startConnection(it) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { operationError.fire(error) }
    }

    /**************************************************************************
     * Server Discovery
     *
     * To save battery, Discovery is stopped when HomeActivity is in background.
     **************************************************************************/
    private var autoStopped = false
    private var discoveryRequested = false

    fun startDiscovery() {
        discoveryRequested = true
        autoStopped = false
        discovery.start(app)
    }

    fun stopDiscovery() {
        discoveryRequested = false
        autoStopped = false
        discovery.stop()
    }

    fun autoStartDiscovery() {
        if (pref.server.discoveryAutorun || autoStopped)
            startDiscovery()
    }

    fun autoStopDiscovery() {
        val shouldResume = discoveryRequested || discovery.isRunning.value == true
        // Stop even before the asynchronous STARTING state reaches LiveData.
        discovery.stop()
        discoveryRequested = false
        autoStopped = shouldResume
    }


    /**************************************************************************
     * Profile editing/creating
     *
     * These are invoked from UI on user actions. We simply fire [editProfileEvent]
     * with appropriate profile, causing the profile editor to be shown.
     *
     * NOTE: We need to make a copy of given profile because the instance
     * given to [editProfileEvent] can be modified by the editor.
     **************************************************************************/

    fun onNewProfile() = editProfileEvent.fire(ServerProfile())
    fun onNewProfile(source: ServerProfile) = editProfileEvent.fire(source.copy(ID = 0))
    fun onEditProfile(profile: ServerProfile) = editProfileEvent.fire(profile.copy())

    fun onDuplicateProfile(profile: ServerProfile) {
        val duplicate = profile.copy(ID = 0)
        duplicate.name += " (Copy)"
        editProfileEvent.fire(duplicate)
    }

    /**************************************************************************
     * Profile persistence
     *
     * These operations are asynchronous.
     **************************************************************************/

    /** A successful save has a stable Room ID before the editor closes or UI highlights it. */
    suspend fun persistProfile(profile: ServerProfile): ServerProfile {
        val id = profilesDao.save(profile)
        withContext(Dispatchers.Main.immediate) {
            if (profile.ID == 0L) {
                check(id > 0L) { "Room did not return an ID for the new profile" }
                profile.ID = id
            }
            profileSavedEvent.fire(profile)
        }
        return profile
    }

    fun saveProfile(profile: ServerProfile) = launchMain {
        try { persistProfile(profile) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { operationError.fire(error) }
    }

    fun deleteProfile(profile: ServerProfile) = launchMain {
        try {
            profilesDao.delete(profile)
            profileDeletedEvent.fire(profile)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { operationError.fire(error) }
    }

    /**************************************************************************
     * Rediscovery Indicator
     *
     * [rediscoveredProfiles] is the intersection of saved & discovered servers.
     *
     * To detect reachable server in [serverProfiles], we could directly 'ping'
     * them, but that has its own issues.
     **************************************************************************/
    val rediscoveredProfiles by lazy {
        pref.server.rediscoveryIndicator.switchMap {
            if (it) prepareRediscoveredProfiles()
            else MutableLiveData(null)
        }
    }

    private fun prepareRediscoveredProfiles() = with(MediatorLiveData<List<ServerProfile>>()) {
        val filter = { saved: List<ServerProfile>?, discovered: List<ServerProfile>? ->
            saved?.filter { s -> discovered?.find { s.host == it.host && s.port == it.port } != null }
        }
        addSource(serverProfiles) { value = filter(it, discovery.servers.value) }
        addSource(discovery.servers) { value = filter(serverProfiles.value, it) }
        this
    }
}
