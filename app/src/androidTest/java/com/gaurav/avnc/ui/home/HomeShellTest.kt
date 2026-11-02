/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.widget.EditText
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.pollingAssert
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.ui.prefs.SettingsRootFragment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeShellTest {
    @JvmField @Rule val prefs = CleanPrefsRule()
    @JvmField @Rule val database = EmptyDatabaseRule()
    @Before fun setup() { targetPrefs.edit { putBoolean("discovery_autorun", false) } }

    @Test fun searchMatchesNameAndAddressAndSurvivesTabSwitchAndRecreation() {
        runBlocking {
            database.db.serverProfileDao.save(listOf(
                    ServerProfile(name = "Studio Mac", host = "192.0.2.10", remotePlatform = "mac"),
                    ServerProfile(name = "Office Windows", host = "192.0.2.20", remotePlatform = "windows")))
        }
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            pollingAssert { scenario.onActivity { assertEquals(2, it.viewModel.profilesState.value!!.profiles.size) } }
            scenario.onActivity { it.findViewById<EditText>(R.id.home_search).setText("192.0.2.10") }
            pollingAssert {
                scenario.onActivity {
                    val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.home_list)
                    assertEquals(1, (0 until list.childCount).count { index -> list.getChildAt(index).tag?.toString()?.matches(Regex("saved-[0-9]+")) == true })
                }
            }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.home_tab_settings).performClick() }
            pollingAssert { scenario.onActivity { assertTrue(it.findViewById<android.view.View>(R.id.home_tab_settings).isSelected) } }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.home_tab_connections).performClick() }
            pollingAssert { scenario.onActivity { assertEquals("192.0.2.10", it.findViewById<EditText>(R.id.home_search).text.toString()) } }
            scenario.recreate()
            pollingAssert { scenario.onActivity { assertEquals("192.0.2.10", it.findViewById<EditText>(R.id.home_search).text.toString()) } }
            scenario.onActivity { it.findViewById<EditText>(R.id.home_search).setText("Office") }
            pollingAssert {
                scenario.onActivity {
                    val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.home_list)
                    assertEquals(1, (0 until list.childCount).count { index -> list.getChildAt(index).tag?.toString()?.matches(Regex("saved-[0-9]+")) == true })
                }
            }
        }
    }

    @Test fun emptySearchHasClearActionAndDoesNotOfferAnOfflineStatus() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            pollingAssert { scenario.onActivity { assertFalse(it.viewModel.profilesState.value!!.loading) } }
            scenario.onActivity { it.findViewById<EditText>(R.id.home_search).setText("missing-device") }
            pollingAssert {
                scenario.onActivity {
                    val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.home_list)
                    assertNotNull(list.findViewWithTag<android.view.View>("no-search"))
                    assertNull(list.findViewWithTag<android.view.View>("empty"))
                    it.findViewById<android.view.View>(R.id.home_search_clear).performClick()
                }
            }
            pollingAssert { scenario.onActivity { assertEquals("", it.findViewById<EditText>(R.id.home_search).text.toString()) } }
        }
    }

    @Test fun settingsSelectionSurvivesRecreationAndBackReturnsToConnections() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<android.view.View>(R.id.home_tab_settings).performClick() }
            scenario.recreate()
            pollingAssert {
                scenario.onActivity {
                    assertTrue(it.findViewById<android.view.View>(R.id.home_tab_settings).isSelected)
                    assertTrue(it.supportFragmentManager.findFragmentByTag(HomeActivity.SETTINGS) is SettingsRootFragment)
                }
            }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            pollingAssert { scenario.onActivity { assertTrue(it.findViewById<android.view.View>(R.id.home_tab_connections).isSelected) } }
        }
    }
}
