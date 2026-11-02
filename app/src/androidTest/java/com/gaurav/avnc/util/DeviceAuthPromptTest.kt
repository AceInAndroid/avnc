/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.util

import androidx.biometric.BiometricPrompt
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.gaurav.avnc.BiometricMocking
import com.gaurav.avnc.CleanPrefsRule
import com.gaurav.avnc.ui.prefs.PrefsActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class DeviceAuthPromptTest {
    @JvmField @Rule val preferences = CleanPrefsRule()
    @Before fun mockAuthentication() = BiometricMocking.start()
    @After fun stopMocking() = BiometricMocking.end()

    @Test fun cancellationCodesAreNeutralButRealFailuresKeepTheirMessage() {
        ActivityScenario.launch(PrefsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var cancellations = 0
                val failures = mutableListOf<String>()
                val prompt = DeviceAuthPrompt(activity)
                prompt.init({ fail("Unexpected authentication success") }, { failures += it }, { cancellations++ })
                listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED).forEach { code ->
                    prompt.launch("Test")
                    assertTrue(prompt.hasLaunched())
                    BiometricMocking.promptCallback!!.onAuthenticationError(activity, code, "Localized dismissal")
                    assertFalse(prompt.hasLaunched())
                }
                assertEquals(3, cancellations)
                assertTrue(failures.isEmpty())
                prompt.launch("Test")
                // Classification must use the error code, never localized error text.
                BiometricMocking.promptCallback!!.onAuthenticationError(activity, BiometricPrompt.ERROR_LOCKOUT, "cancelled")
                assertEquals(listOf("cancelled"), failures)
                assertEquals(3, cancellations)
                assertFalse(prompt.hasLaunched())
            }
        }
    }

    @Test fun legacyCallersStillReceiveCancellationAsFailure() {
        ActivityScenario.launch(PrefsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val failures = mutableListOf<String>()
                val prompt = DeviceAuthPrompt(activity)
                prompt.init({ fail("Unexpected authentication success") }, { failures += it })
                prompt.launch("Test")
                BiometricMocking.promptCallback!!.onAuthenticationError(activity, BiometricPrompt.ERROR_CANCELED, "Dismissed")
                assertEquals(listOf("Dismissed"), failures)
                assertFalse(prompt.hasLaunched())
            }
        }
    }

    @Test fun activePromptRebindsCancellationAfterActivityRecreation() {
        var oldCancellations = 0
        var newCancellations = 0
        ActivityScenario.launch(PrefsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                DeviceAuthPrompt(activity).apply {
                    init({ fail("Unexpected success") }, { fail(it) }, { oldCancellations++ })
                    launch("Test", "export")
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val rebound = DeviceAuthPrompt(activity)
                rebound.init({ fail("Unexpected success") }, { fail(it) }, { newCancellations++ })
                assertTrue(rebound.hasLaunched())
                assertSame(activity, BiometricMocking.promptHost)
                BiometricMocking.promptCallback!!.onAuthenticationError(activity, BiometricPrompt.ERROR_USER_CANCELED, "Dismissed")
                assertFalse(rebound.hasLaunched())
            }
        }
        assertEquals(0, oldCancellations)
        assertEquals(1, newCancellations)
    }
}
