/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.gaurav.avnc.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.common.GlassStyle
import com.gaurav.avnc.ui.prefs.SettingsRootFragment
import com.gaurav.avnc.ui.vnc.startVncActivity
import com.gaurav.avnc.util.DeviceAuthPrompt
import com.gaurav.avnc.util.EdgeToEdgeHelper
import com.gaurav.avnc.viewmodel.HomeViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

/** The two primary destinations retain their own navigation and scroll state. */
class HomeActivity : AppCompatActivity() {
    val viewModel by viewModels<HomeViewModel>()
    private val style by lazy { GlassStyle(this) }
    private lateinit var connectionsButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private var selected = CONNECTIONS
    private var launchingConnection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.App_Theme)
        theme.applyStyle(R.style.ThemeOverlay_HomeShell, true)
        DeviceAuthPrompt.applyFingerprintDialogFix(supportFragmentManager)
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        EdgeToEdgeHelper.setContentView(this, R.layout.activity_home)
        @Suppress("DEPRECATION")
        window.statusBarColor = style.background
        @Suppress("DEPRECATION")
        window.navigationBarColor = style.background
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        createNavigation()
        selected = savedInstanceState?.getString("home_destination") ?: CONNECTIONS
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .add(R.id.home_fragment_host, ConnectionsFragment(), CONNECTIONS)
                    .add(R.id.home_fragment_host, SettingsRootFragment(), SETTINGS)
                    .commitNow()
        }
        selectTab(selected)
        viewModel.editProfileEvent.observe(this) { showProfileEditor(it) }
        viewModel.profileSavedEvent.observe(this) { selectTab(CONNECTIONS) }
        viewModel.profileDeletedEvent.observe(this) { profile ->
            Snackbar.make(findViewById(R.id.home_shell), R.string.msg_server_profile_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.title_undo) { viewModel.saveProfile(profile) }.show()
        }
        viewModel.operationError.observe(this) {
            Snackbar.make(findViewById(R.id.home_shell), getString(R.string.home_operation_error,
                    it.localizedMessage.orEmpty()), Snackbar.LENGTH_LONG).show()
        }
        viewModel.newConnectionEvent.observe(this) {
            if (!launchingConnection) {
                launchingConnection = true
                startVncActivity(this, it)
            }
        }
        viewModel.profilesState.observe(this) {
            if (!it.loading && it.error == null) updateShortcuts(this, it.profiles)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val settings = supportFragmentManager.findFragmentByTag(SETTINGS) as? SettingsRootFragment
                when {
                    selected == SETTINGS && settings?.handleBack() == true -> Unit
                    selected == SETTINGS -> selectTab(CONNECTIONS)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        setupSplashTheme()
        if (savedInstanceState == null && !handleNavigationIntent(intent)) viewModel.maybeConnectOnAppStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent): Boolean {
        if (intent.getBooleanExtra(RETURN_HOME, false)) {
            intent.removeExtra(RETURN_HOME)
            selectTab(CONNECTIONS)
            return true
        }
        val profile = IntentCompat.getParcelableExtra(intent, EDIT_PROFILE, ServerProfile::class.java) ?: return false
        intent.removeExtra(EDIT_PROFILE)
        selectTab(CONNECTIONS)
        showProfileEditor(profile)
        return true
    }

    private fun showProfileEditor(profile: ServerProfile) {
        startProfileEditor(this, profile, viewModel.pref.ui.preferAdvancedEditor)
    }

    private fun createNavigation() {
        val capsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(style.dp(6), style.dp(6), style.dp(6), style.dp(6))
            background = style.rounded(style.glass, 30, true)
            elevation = style.dp(2).toFloat()
        }
        connectionsButton = style.button(getString(R.string.home_connections)).apply {
            id = R.id.home_tab_connections
            icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@HomeActivity, R.drawable.ic_computer)
            cornerRadius = style.dp(24)
            setOnClickListener { selectTab(CONNECTIONS) }
        }
        settingsButton = style.button(getString(R.string.title_settings)).apply {
            id = R.id.home_tab_settings
            icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@HomeActivity, R.drawable.ic_settings)
            cornerRadius = style.dp(24)
            setOnClickListener { selectTab(SETTINGS) }
        }
        capsule.addView(connectionsButton, LinearLayout.LayoutParams(0, -2, 1f))
        capsule.addView(settingsButton, LinearLayout.LayoutParams(0, -2, 1f))
        findViewById<FrameLayout>(R.id.home_navigation_host).apply {
            setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(12))
            addView(capsule, FrameLayout.LayoutParams(style.dp(360).coerceAtMost(resources.displayMetrics.widthPixels - style.dp(40)), -2, Gravity.CENTER))
        }
    }

    fun selectTab(destination: String) {
        selected = if (destination == SETTINGS) SETTINGS else CONNECTIONS
        val transaction = supportFragmentManager.beginTransaction()
        listOf(CONNECTIONS, SETTINGS).forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.let { fragment ->
                if (tag == selected) {
                    transaction.show(fragment).setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.RESUMED)
                } else transaction.hide(fragment).setMaxLifecycle(fragment, androidx.lifecycle.Lifecycle.State.STARTED)
            }
        }
        transaction.commit()
        listOf(connectionsButton to CONNECTIONS, settingsButton to SETTINGS).forEach { (button, tag) ->
            val active = selected == tag
            button.isSelected = active
            button.backgroundTintList = ColorStateList.valueOf(if (active) style.selected else android.graphics.Color.TRANSPARENT)
            button.setTextColor(if (active) style.blue else style.secondary)
            button.iconTint = ColorStateList.valueOf(if (active) style.blue else style.secondary)
        }
    }

    override fun onStart() { super.onStart(); viewModel.autoStartDiscovery() }
    override fun onResume() { super.onResume(); launchingConnection = false }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.autoStopDiscovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("home_destination", selected)
        super.onSaveInstanceState(outState)
    }
    private fun setupSplashTheme() {
        if (Build.VERSION.SDK_INT >= 31) viewModel.pref.ui.theme.observe(this) {
            splashScreen.setSplashScreenTheme(when (it) {
                "light" -> R.style.App_SplashTheme_Light
                "dark" -> R.style.App_SplashTheme_Dark
                else -> R.style.App_SplashTheme
            })
        }
    }

    companion object {
        const val CONNECTIONS = "home_connections"
        const val SETTINGS = "home_settings"
        private const val EDIT_PROFILE = "edit_connection_profile"
        private const val RETURN_HOME = "return_to_connections"
        fun returnToConnections(context: Context) {
            context.startActivity(Intent(context, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(RETURN_HOME, true))
        }
        fun editConnection(context: Context, profile: ServerProfile) {
            context.startActivity(Intent(context, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EDIT_PROFILE, profile))
        }
    }
}
