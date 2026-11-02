/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.home

import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gaurav.avnc.EmptyDatabaseRule
import com.gaurav.avnc.R
import com.gaurav.avnc.ScrollToBottom
import com.gaurav.avnc.TestServer
import com.gaurav.avnc.checkIsDisplayed
import com.gaurav.avnc.checkWillBeCompletelyDisplayed
import com.gaurav.avnc.checkWillBeDisplayed
import com.gaurav.avnc.checkWillBeHidden
import com.gaurav.avnc.doClick
import com.gaurav.avnc.doTypeText
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.performWithTimeout
import com.gaurav.avnc.setupFileOpenIntent
import com.gaurav.avnc.targetContext
import com.gaurav.avnc.targetPrefs
import com.gaurav.avnc.util.hideKeyboard
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BasicEditorTest {
    private val testProfile = ServerProfile(name = "foo", host = "bar")
    private val activityRule = ActivityScenarioRule(HomeActivity::class.java)
    private val dbRule = EmptyDatabaseRule()
    @Rule @JvmField val rules: RuleChain = RuleChain.outerRule(HomeTestPreferencesRule())
            .around(dbRule).around(activityRule)

    private fun openEditor() = onView(withId(R.id.home_add)).doClick()

    /** MIUI may hide the IME without returning Espresso's close-keyboard result callback. */
    private fun closeEditorKeyboard() {
        activityRule.scenario.onActivity { activity ->
            val editor = activity.supportFragmentManager.findFragmentByTag("profileEditor") as ProfileEditorSheet
            val dialog = editor.requireDialog()
            val decor = requireNotNull(dialog.window).decorView
            hideKeyboard(dialog.currentFocus ?: decor)
        }
        com.gaurav.avnc.pollingAssert {
            activityRule.scenario.onActivity { activity ->
                val editor = activity.supportFragmentManager.findFragmentByTag("profileEditor") as ProfileEditorSheet
                val decor = requireNotNull(editor.requireDialog().window).decorView
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                Assert.assertFalse("Editor IME is still visible", insets.isVisible(WindowInsetsCompat.Type.ime()))
            }
        }
    }

    @Test fun createQuickProfileSaveOnlyAssignsAnId() {
        openEditor()
        onView(withText(R.string.title_add_server_profile)).checkWillBeDisplayed()
        onView(withId(R.id.host)).doTypeText(testProfile.host)
        onView(withId(R.id.name)).doTypeText(testProfile.name)
        closeEditorKeyboard()
        onView(withId(R.id.save_btn)).doClick()
        com.gaurav.avnc.pollingAssert {
            val profile = kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList().single() }
            Assert.assertTrue(profile.ID > 0)
            Assert.assertEquals(testProfile.host, profile.host)
            Assert.assertEquals(testProfile.name, profile.name)
            Assert.assertEquals(5900, profile.port)
            Assert.assertEquals("auto", profile.remotePlatform)
        }
        com.gaurav.avnc.pollingAssert {
            onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }

    @Test fun emptyAddressShowsInlineErrorAndKeepsEditorOpen() {
        openEditor()
        closeEditorKeyboard()
        onView(withId(R.id.save_btn)).doClick()
        onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.hasErrorText(targetContext.getString(R.string.msg_required))))
        Assert.assertTrue(kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList() }.isEmpty())
    }

    @Test fun expandingAdvancedKeepsTheSameDraft() {
        openEditor()
        onView(withId(R.id.host)).doTypeText(testProfile.host)
        onView(withId(R.id.name)).doTypeText(testProfile.name)
        closeEditorKeyboard()
        onView(withId(R.id.advanced_toggle)).perform(ViewActions.scrollTo()).doClick()
        onView(withId(R.id.use_repeater)).perform(ViewActions.scrollTo()).checkIsDisplayed()
        onView(withId(R.id.host)).perform(ViewActions.scrollTo()).check(
                androidx.test.espresso.assertion.ViewAssertions.matches(withText(testProfile.host)))
        onView(withId(R.id.save_btn)).doClick()
        com.gaurav.avnc.pollingAssert {
            Assert.assertEquals(testProfile.host, kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList().single() }.host)
        }
    }

    @Test fun rotationAndContinueEditingKeepDraftThenDiscardLeavesDatabaseEmpty() {
        openEditor()
        onView(withId(R.id.host)).doTypeText(testProfile.host)
        onView(withId(R.id.name)).doTypeText(testProfile.name)
        closeEditorKeyboard()
        activityRule.scenario.recreate()
        onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(testProfile.host)))
        onView(withId(R.id.name)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(testProfile.name)))
        closeEditorKeyboard()
        onView(withContentDescription(R.string.quick_editor_close)).doClick()
        onView(withText(R.string.quick_editor_continue)).doClick()
        onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText(testProfile.host)))
        onView(withContentDescription(R.string.quick_editor_close)).doClick()
        onView(withText(R.string.quick_editor_discard)).doClick()
        onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        Assert.assertTrue(kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList() }.isEmpty())
    }

    @Test fun advancedPreferenceOpensConnectionGroup() {
        targetPrefs.edit { putBoolean("prefer_advanced_editor", true) }
        openEditor()
        onView(withId(R.id.use_repeater)).perform(ViewActions.scrollTo()).checkIsDisplayed()
    }

    @Test fun rotationDuringSaveRetainsBusyStateAndConnectsOnlyOnce() {
        openEditor()
        onView(withId(R.id.host)).doTypeText(testProfile.host)
        closeEditorKeyboard()
        val pending = kotlinx.coroutines.CompletableDeferred<ServerProfile>()
        lateinit var vm: com.gaurav.avnc.viewmodel.EditorViewModel
        var connections = 0
        activityRule.scenario.onActivity { activity ->
            val editor = activity.supportFragmentManager.findFragmentByTag("profileEditor")!!
            vm = androidx.lifecycle.ViewModelProvider(editor)[com.gaurav.avnc.viewmodel.EditorViewModel::class.java]
            Assert.assertTrue(vm.submit(true, { pending.await() }) { connections++ })
        }
        activityRule.scenario.recreate()
        activityRule.scenario.onActivity { activity ->
            val editor = activity.supportFragmentManager.findFragmentByTag("profileEditor")!!
            val retained = androidx.lifecycle.ViewModelProvider(editor)[com.gaurav.avnc.viewmodel.EditorViewModel::class.java]
            Assert.assertSame(vm, retained)
            Assert.assertEquals(com.gaurav.avnc.viewmodel.EditorViewModel.SaveState.SAVING, retained.saveState.value)
            Assert.assertFalse(retained.submit(true, { error("Duplicate save") }) { connections++ })
        }
        pending.complete(ServerProfile(ID = 71L, host = testProfile.host))
        com.gaurav.avnc.pollingAssert {
            activityRule.scenario.onActivity {
                Assert.assertEquals(1, connections)
                Assert.assertEquals(71L, vm.profile.ID)
            }
            onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        }
    }

    @Test fun pastedVncLinkExtractsAddressAndDefaultPortButPreservesAuthentication() {
        openEditor()
        onView(withId(R.id.password)).perform(ViewActions.replaceText("typed-password"))
        onView(withId(R.id.host)).perform(ViewActions.replaceText(
                "vnc://desktop.example:5901/?VncPassword=link-secret&SecurityType=2&SshPassword=ssh-secret"))
        onView(withId(R.id.address_feedback)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                withText(R.string.quick_editor_address_settings)))
        onView(withId(R.id.password)).check(androidx.test.espresso.assertion.ViewAssertions.matches(withText("typed-password")))
        closeEditorKeyboard()
        onView(withId(R.id.save_btn)).doClick()
        com.gaurav.avnc.pollingAssert {
            val saved = kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList().single() }
            Assert.assertEquals("desktop.example", saved.host)
            Assert.assertEquals(5901, saved.port)
            Assert.assertEquals("typed-password", saved.password)
            Assert.assertEquals(0, saved.securityType)
            Assert.assertEquals("", saved.sshPassword)
            Assert.assertEquals(ServerProfile.SSH_AUTH_KEY, saved.sshAuthType)
        }
    }

    @Test fun explicitPortWinsOverPastedAddressEvenAfterRotation() {
        openEditor()
        closeEditorKeyboard()
        onView(withId(R.id.advanced_toggle)).perform(ViewActions.scrollTo()).doClick()
        onView(withId(R.id.port)).perform(ViewActions.scrollTo(), ViewActions.replaceText("6001"))
        onView(withId(R.id.host)).perform(ViewActions.scrollTo(), ViewActions.replaceText("[2001:db8::1]:5901"))
        closeEditorKeyboard()
        activityRule.scenario.recreate()
        onView(withId(R.id.save_btn)).doClick()
        com.gaurav.avnc.pollingAssert {
            val saved = kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList().single() }
            Assert.assertEquals("2001:db8::1", saved.host)
            Assert.assertEquals(6001, saved.port)
        }
    }

    @Test fun addressAndPortValidateImmediatelyWithoutAnInitialEmptyDraftError() {
        openEditor()
        onView(withId(R.id.host)).check { view, _ -> Assert.assertNull((view as android.widget.EditText).error) }
        onView(withId(R.id.host)).perform(ViewActions.replaceText("https://wrong-protocol.example"))
        onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.hasErrorText(targetContext.getString(R.string.quick_editor_invalid_address))))
        closeEditorKeyboard()
        onView(withId(R.id.advanced_toggle)).perform(ViewActions.scrollTo()).doClick()
        onView(withId(R.id.port)).perform(ViewActions.scrollTo(), ViewActions.replaceText("70000"))
        onView(withId(R.id.port)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                androidx.test.espresso.matcher.ViewMatchers.hasErrorText(targetContext.getString(R.string.quick_editor_invalid_port))))
        closeEditorKeyboard()
        onView(withId(R.id.save_btn)).doClick()
        com.gaurav.avnc.pollingAssert {
            onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.hasFocus()))
        }
        Assert.assertTrue(kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList() }.isEmpty())
    }

    @Test fun saveAndConnectPersistsBeforeLaunchingViewer() {
        val server = TestServer()
        server.start()
        try {
            openEditor()
            onView(withId(R.id.host)).doTypeText(server.host)
            closeEditorKeyboard()
            onView(withId(R.id.advanced_toggle)).perform(ViewActions.scrollTo()).doClick()
            onView(withId(R.id.port)).perform(ViewActions.scrollTo(), ViewActions.replaceText(server.port.toString()))
            closeEditorKeyboard()
            onView(withId(R.id.save_connect_btn)).doClick()
            onView(withId(R.id.frame_view)).checkWillBeDisplayed()
            val saved = kotlinx.coroutines.runBlocking { dbRule.db.serverProfileDao.getList().single() }
            Assert.assertTrue(saved.ID > 0)
            Assert.assertEquals(server.port, saved.port)
            Assert.assertEquals(server.host, saved.host)
            com.gaurav.avnc.pollingAssert {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val viewer = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<com.gaurav.avnc.ui.vnc.VncActivity>().single()
                    val vm = viewer.viewModel
                    Assert.assertEquals("${vm.profileLive.value?.host}:${vm.profileLive.value?.port}; ${vm.disconnectReason.value}",
                            com.gaurav.avnc.viewmodel.VncViewModel.State.Connected, vm.state.value)
                }
            }
            onView(withId(R.id.status_container)).checkWillBeHidden()
            Espresso.pressBack()
            onView(withId(R.id.host)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        } finally { server.stop() }
    }
}

@RunWith(AndroidJUnit4::class)
class PrivateKeyTest {

    private val SAMPLE_KEY = """
        -----BEGIN EC PRIVATE KEY-----
        MHcCAQEEIOlzJCBaTq2RpkLatMn+PMwsQ/ncijqK5M6y5kPns+BAoAoGCCqGSM49
        AwEHoUQDQgAE5vfo9Is5+pnk+6dC3NlXA9rKNy0X6PdifyeI2OKKTGVA4cdhEOBh
        MXTgJKarp26FiFnsFuWEGOO61Q/Plmv8Wg==
        -----END EC PRIVATE KEY-----
    """.trimIndent()

    private val SAMPLE_ENCRYPTED_KEY = """
        -----BEGIN EC PRIVATE KEY-----
        Proc-Type: 4,ENCRYPTED
        DEK-Info: AES-128-CBC,D18B845F2B9D4B9FEB3472809F270AD2

        fNNjQEFap5uf3+gaQNI+A2ait7WZDjxPO6sT6MfOTQ34/HRxm7El4FWhhYMfIiGl
        5j/A30Je2OMbOEqTpiKZKYxKxWIZ27te8HpxsQkC9xvU9rxPPsd0Vt/7na5rbx1S
        Iqfy2ows7NJY3f3s/GlMY2ZmaAwk6TJ3DAJ+YK0KMrc=
        -----END EC PRIVATE KEY-----
    """.trimIndent()

    private val SAMPLE_PKCS8_RSA_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCdwT/QVDFbUAp
            unY5uN2x1ODEtN2rBkM4ZwGqJUkOcAQWyUzCzxgBw8PP+DYG6Z608tyniKO0EDz
            JLda3Ak1DU050tLwRAIrnq/Swp6hRyZ/uYOIAdQWX8DKhme93DV9bnDLY3frtny
            hI4EHnhaUbKtQFPVOvqQMjfpiN6B0wnQpeGJCwlqtjzoIEdEJ7yT8uxhJCd2Ok5
            2MhGWTdjY3wTGzSxpQ38XLy/S3+JDik0sufSLEzunEGfQfmkh7eBAJDXlI0nMfl
            QPuMc2BUzq1D9fagKEL3XwxzaZxgYHefH4CdVMS1LZ1qZJ3BcFLEzr9n6I05r9i
            fd1cU9ytSlPWCzAgMBAAECggEAIiICEK1m0H8NAsoMW0SarvItkb7/1knijifX5
            UZrYoHGHcNqMjuRNN6trDZ370Endo+a/FgmkE5Jb5JSuewl/SacR354yPe6imGl
            AJypN+fPxCvVbH8N9e83MJV0ciO7V9qkQnWlTtul/YNzG5aPvqRTWDrjoJfL3rg
            vzONvUr76zZbymCaBScVHKqRq9JhONe2nv6Mohsaityzs+zV9WOqIaNLgJypJFQ
            oCNrvsyaAwT6xsJBQdKAB0oUM/Au/yRTSp1agiptI9RlKjiN/9YBnsBLNdv9gUY
            K9ze0d3ptENMlTkpbdcBJBrDAwTYU3Dvv7q62swXpe3QCi0MjcHIQKBgQDQJhGe
            D8USokWzl0Z74iBAFe1chvv+Zr2vj3sVhlraU9sQPnzmZ9HofUoBpSslqA+8pJL
            GXXS5GRi/P0J+5CXRog+2y1dZFyc47TtD7HPXqGD101DRE6/diYhQDUNUIz6++8
            l6hBl1eYYnhpt4WbcrK9Ve9cox5lEWBiFXs9Yd3QKBgQDCBWls9JY9nGlNDf5Rs
            ggQzZC/AabTZGqT4R6d7OBTftZlhs/kGFybgm/+RiHG3H0mICeTH459HLLt9jT3
            zAd/fsN26/VW8bqBqd+CksLzlvv7GmFsmYIFK2ThaZ45jO51ytDu4tFduRPlUGX
            zgDAyXeQI1sGqQQfv2gA2DEf3zwKBgQCXjTBEmozNvXLsiNdb+c+reYuiU/IEUA
            AKZHikundKAcY9dJHyHGNcWGTa/8yDlXMn0dfAMGl9H8XB8ahTxX+3u7yfRjxp9
            I6tRyVgljfeLI20TtDH+gKRVcL3LkD4cNUNrzwKRUZYH0xLWRm3rfLMrxSjGGjI
            nj4pv/rk5V6fzQKBgQCE1Xmxa980vVJmu+7jddUJ1AOGkhXqYrSJHDZ+/v8yiwM
            0LVFFo9w7Z9lPKCrV4H0aTidqFc/THoPuYYMwKBL2Gg6u66tj5EnBnlD1L4+jgD
            pyV0ReOtcGvQfrQAlg83kLUlkrRET9OspBVIMIbDoTMa7+0jFzY+SQNRux5USch
            wKBgHI1FYE2Q9798a0dMoHqBAf1KQ669vOHbjIPsVWXtbrhYZ16FUgg3iR9RuI7
            aQ37HJvilQUHLuSSISpUWZ6tLtaWboVevFtuREs1bs1DvuM/8U6tRfqyt3Ec2GX
            GezpZeTJIsnBHhJTkAR53iTjV6QAevaiZfhuyPQiTQWM/Vcsg
            -----END PRIVATE KEY-----
        """.trimIndent()

    private val activityRule = ActivityScenarioRule(HomeActivity::class.java)
    @Rule @JvmField val rules: RuleChain = RuleChain.outerRule(HomeTestPreferencesRule())
            .around(EmptyDatabaseRule()).around(activityRule)

    @Before
    fun before() {
        Intents.init()
        targetPrefs.edit { putBoolean("prefer_advanced_editor", true) }
        onView(withId(R.id.home_add)).doClick()
        onView(withId(R.id.use_ssh_tunnel)).perform(ViewActions.scrollTo()).doClick()
        onView(withId(R.id.key_import_btn)).perform(ViewActions.scrollTo()).checkWillBeCompletelyDisplayed()
    }

    @After
    fun after() {
        targetPrefs.edit { putBoolean("prefer_advanced_editor", false) }
        Intents.release()
    }

    @Test
    fun importValidPK() {
        setupFileOpenIntent(SAMPLE_KEY)
        onView(withId(R.id.key_import_btn)).doClick()
        onView(withText(R.string.msg_imported)).checkWillBeDisplayed()
    }

    @Test
    fun importEncryptedPK() {
        setupFileOpenIntent(SAMPLE_ENCRYPTED_KEY)
        onView(withId(R.id.key_import_btn)).doClick()
        onView(withText(R.string.msg_imported)).checkWillBeDisplayed()
    }

    @Test
    fun importPKCS8Key() {
        setupFileOpenIntent(SAMPLE_PKCS8_RSA_KEY)
        onView(withId(R.id.key_import_btn)).doClick()
        onView(withText(R.string.msg_imported)).checkWillBeDisplayed()
    }

    @Test
    fun importInvalidFile() {
        setupFileOpenIntent("This is not a Private Key, right?")
        onView(withId(R.id.key_import_btn)).doClick()
        onView(withText(R.string.msg_invalid_key_file)).checkWillBeDisplayed()
    }
}
