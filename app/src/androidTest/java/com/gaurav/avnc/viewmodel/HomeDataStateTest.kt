/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.viewmodel

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.Observer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.db.MainDb
import com.gaurav.avnc.model.db.ServerProfileDao
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.runOnMainSync
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class HomeDataStateTest {
    @JvmField @Rule val prefs = CleanPrefsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun withModel(dao: ServerProfileDao, block: (HomeViewModel) -> Unit) {
        lateinit var model: HomeViewModel
        val store = ViewModelStore()
        runOnMainSync { model = HomeViewModel(app, dao); store.put("test", model) }
        try { block(model) } finally { runOnMainSync { store.clear() } }
    }

    @Test fun roomSaveAssignsIdBeforeEventAndSubsequentSaveUpdatesTheSameRow() {
        val db = Room.inMemoryDatabaseBuilder(targetContext, MainDb::class.java).build()
        try {
            withModel(db.serverProfileDao) { model ->
                val profile = ServerProfile(name = "New computer", host = "192.0.2.1")
                val returned = runBlocking { model.persistProfile(profile) }
                assertSame(profile, returned)
                assertTrue(profile.ID > 0)
                runOnMainSync { assertEquals(profile.ID, model.profileSavedEvent.value?.ID) }
                val id = profile.ID
                profile.name = "Renamed computer"
                runBlocking { model.persistProfile(profile) }
                val rows = runBlocking { db.serverProfileDao.getList() }
                assertEquals(1, rows.size)
                assertEquals(id, rows.single().ID)
                assertEquals("Renamed computer", rows.single().name)
            }
        } finally { db.close() }
    }

    @Test fun queryFailureKeepsRowsRetryClearsErrorAndSortingStillUsesDaoOrder() {
        val dao = mockk<ServerProfileDao>()
        val fail = AtomicBoolean(true)
        val beta = ServerProfile(ID = 1, name = "Beta")
        val alpha = ServerProfile(ID = 2, name = "Alpha")
        every { dao.observeList() } answers { flow {
            emit(listOf(beta, alpha))
            if (fail.get()) throw IOException("Database read failed")
            awaitCancellation()
        } }
        every { dao.observeSortedList() } returns flow { emit(listOf(alpha, beta)); awaitCancellation() }
        withModel(dao) { model ->
            val observer = Observer<ProfilesUiState> { }
            runOnMainSync { model.profilesState.observeForever(observer) }
            try {
                pollingAssert { runOnMainSync {
                    assertEquals("Database read failed", model.profilesState.value?.error)
                    assertEquals(listOf(beta, alpha), model.profilesState.value?.profiles)
                    assertFalse(model.profilesState.value!!.loading)
                } }
                fail.set(false)
                runOnMainSync { model.retryProfiles() }
                pollingAssert { runOnMainSync {
                    assertNull(model.profilesState.value?.error)
                    assertFalse(model.profilesState.value!!.loading)
                    assertEquals(listOf(beta, alpha), model.profilesState.value?.profiles)
                } }
                runOnMainSync { targetPrefs.edit { putBoolean("sort_server_list", true) } }
                pollingAssert { runOnMainSync {
                    assertEquals(listOf(alpha, beta), model.profilesState.value?.profiles)
                } }
            } finally { runOnMainSync { model.profilesState.removeObserver(observer) } }
        }
    }

    @Test fun persistenceFailuresReachEditorAndDeleteUndoFailuresDoNotEmitSuccess() {
        val dao = mockk<ServerProfileDao>()
        // Android's DAO proxy wraps checked exceptions absent from the JVM signature.
        // Use an unchecked database failure so identity assertions test our error routing.
        val failure = IllegalStateException("Disk unavailable")
        coEvery { dao.save(any<ServerProfile>()) } throws failure
        coEvery { dao.delete(any()) } throws failure
        withModel(dao) { model ->
            val profile = ServerProfile(name = "Unsaved")
            try {
                runBlocking { model.persistProfile(profile) }
                fail("Editor save should throw")
            } catch (error: IllegalStateException) { assertSame(failure, error) }
            runOnMainSync {
                assertEquals(0L, profile.ID)
                assertNull(model.profileSavedEvent.value)
                model.deleteProfile(profile)
            }
            pollingAssert { runOnMainSync {
                assertSame(failure, model.operationError.value)
                assertNull(model.profileDeletedEvent.value)
            } }
            runBlocking { model.saveProfile(profile).join() }
            runOnMainSync {
                assertSame(failure, model.operationError.value)
                assertNull(model.profileSavedEvent.value)
            }
        }
    }

    @Test fun startupQueryFailureEmitsOneErrorAndDoesNotStartConnection() {
        val dao = mockk<ServerProfileDao>()
        val failure = IllegalStateException("Startup database read failed")
        coEvery { dao.getConnectableOnAppStart() } throws failure
        withModel(dao) { model ->
            val errors = mutableListOf<Throwable>()
            lateinit var owner: LifecycleOwner
            lateinit var job: Job
            runOnMainSync {
                owner = object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply {
                        currentState = Lifecycle.State.RESUMED
                    }
                }
                model.operationError.observe(owner) { errors += it }
                job = model.maybeConnectOnAppStart()
            }
            try {
                runBlocking { job.join() }
                runOnMainSync {
                    assertEquals(listOf(failure), errors)
                    assertNull(model.newConnectionEvent.value)
                    assertFalse(job.isCancelled)
                }
            } finally {
                runOnMainSync { (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED }
            }
        }
    }

    @Test fun startupQueryCancellationIsNotReportedAsFailure() {
        val dao = mockk<ServerProfileDao>()
        coEvery { dao.getConnectableOnAppStart() } throws CancellationException("Home closed")
        withModel(dao) { model ->
            lateinit var job: Job
            runOnMainSync { job = model.maybeConnectOnAppStart() }
            runBlocking { job.join() }
            runOnMainSync {
                assertTrue(job.isCancelled)
                assertNull(model.operationError.value)
                assertNull(model.newConnectionEvent.value)
            }
        }
    }
}
