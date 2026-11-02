/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.prefs

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.gaurav.avnc.ui.common.GlassStyle
import com.gaurav.avnc.ui.about.AboutActivity
import com.gaurav.avnc.util.Debugging
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import androidx.preference.ListPreference
import androidx.preference.SeekBarPreference
import com.gaurav.avnc.R
import com.gaurav.avnc.util.DeviceAuthPrompt
import com.gaurav.avnc.util.EdgeToEdgeHelper
import com.gaurav.avnc.util.RemoteInputPreferences
import com.gaurav.avnc.model.RemotePlatform
import com.google.android.material.dialog.MaterialAlertDialogBuilder


class PrefsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(R.style.ThemeOverlay_HomeShell, true)
        DeviceAuthPrompt.applyFingerprintDialogFix(supportFragmentManager)

        super.onCreate(savedInstanceState)
        EdgeToEdgeHelper.setContentView(this, R.layout.activity_settings)

        if (savedInstanceState == null) {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_host, SettingsRootFragment(), "settings_root")
                    .commit()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val root = supportFragmentManager.findFragmentByTag("settings_root") as? SettingsRootFragment
                if (root?.handleBack() != true) finish()
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /**
     * Starts new fragment corresponding to given [pref].
     */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val root = supportFragmentManager.findFragmentByTag("settings_root") as? SettingsRootFragment
        if (root != null) root.navigate(checkNotNull(pref.fragment))
        else {
            val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, checkNotNull(pref.fragment))
            supportFragmentManager.beginTransaction().replace(R.id.fragment_host, fragment).addToBackStack(null).commit()
        }

        return true
    }


    /**************************************************************************
     * Preference Fragments
     **************************************************************************/

    abstract class PrefFragment(private val prefResource: Int) : PreferenceFragmentCompat() {

        override fun onResume() {
            super.onResume()
            updateSettingsTitle(preferenceScreen.title ?: getString(R.string.title_settings))
            if (prefResource != R.xml.pref_main && prefResource != R.xml.pref_platform) {
                settingsRoot()?.setPageAction(getString(R.string.settings_restore_group), ::confirmRestoreDefaults)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(prefResource, rootKey)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val style = GlassStyle(requireContext())
            setDivider(null)
            listView.setBackgroundColor(style.background)
            listView.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(24))
            listView.clipToPadding = false
            val highlight = arguments?.getString(SettingsRootFragment.HIGHLIGHT_KEY)
            val highlightedTitle = highlight?.let { findPreference<Preference>(it)?.title?.toString() }
            val categoryTitles = mutableSetOf<String>()
            fun collectCategoryTitles(group: PreferenceGroup) {
                for (index in 0 until group.preferenceCount) {
                    val preference = group.getPreference(index)
                    if (preference is PreferenceCategory) preference.title?.toString()?.let(categoryTitles::add)
                    if (preference is PreferenceGroup) collectCategoryTitles(preference)
                }
            }
            collectCategoryTitles(preferenceScreen)
            listView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.bottom = style.dp(8)
                }
                override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                    for (index in 0 until parent.childCount) {
                        val child = parent.getChildAt(index)
                        val position = parent.getChildAdapterPosition(child)
                        if (position == RecyclerView.NO_POSITION) continue
                        val title = child.findViewById<TextView>(android.R.id.title)?.text?.toString()
                        if (title in categoryTitles) continue
                        paint.color = if (highlightedTitle != null && title == highlightedTitle) style.selected else style.surface
                        canvas.drawRoundRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(),
                                child.bottom.toFloat(), style.dp(20).toFloat(), style.dp(20).toFloat(), paint)
                    }
                }
            })
            if (highlight != null) listView.post { scrollToPreference(highlight) }
        }

        protected fun inputRecovery() {
            findPreference<Preference>("remote_input_templates")?.takeIf { it.fragment == null }?.setOnPreferenceClickListener {
                RemoteInputEditor.showTemplates(requireContext())
                true
            }
            findPreference<Preference>("restore_recommended_input")?.apply {
                setTitle(R.string.settings_restore_group)
                setSummary(R.string.settings_restore_group_summary)
                setOnPreferenceClickListener {
                    confirmRestoreDefaults()
                    true
                }
            }
        }

        private fun confirmRestoreDefaults() {
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.settings_restore_group)
            if (prefResource == R.xml.pref_tools) {
                dialog.setMessage(R.string.settings_restore_group_empty).setPositiveButton(android.R.string.ok, null).show()
                return
            }
            val message = getString(R.string.settings_restore_group_confirm, preferenceScreen.title) +
                    if (prefResource == R.xml.pref_server) "\n\n" + getString(R.string.settings_restore_group_security) else ""
            dialog.setMessage(message)
                    .setNegativeButton(R.string.title_cancel, null)
                    .setPositiveButton(R.string.settings_restore_group) { _, _ ->
                        val defaults = SettingsDefaults.restore(requireContext(), preferenceManager.sharedPreferences!!, prefResource)
                        defaults.forEach { (key, value) ->
                            when (val preference = findPreference<Preference>(key)) {
                                is SwitchPreference -> preference.isChecked = value as Boolean
                                is ListPreference -> preference.value = value as String
                                is SeekBarPreference -> preference.value = value as Int
                            }
                        }
                        Toast.makeText(requireContext(), R.string.settings_restore_group_done, Toast.LENGTH_SHORT).show()
                    }.show()
        }
    }

    @Keep class Main : PrefFragment(R.xml.pref_main) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            findPreference<Preference>("remote_input_templates")?.setOnPreferenceClickListener {
                RemoteInputEditor.showTemplates(requireContext())
                true
            }
        }
    }
    @Keep class Appearance : PrefFragment(R.xml.pref_appearance)

    @Keep class InputOverview : PrefFragment(R.xml.pref_input_overview) {
        override fun onCreate(savedInstanceState: Bundle?) {
            RemoteInputPreferences(requireContext())
            super.onCreate(savedInstanceState)
            inputRecovery()
        }
    }

    @Keep class Platform : PrefFragment(R.xml.pref_platform) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            listOf("mac" to RemotePlatform.MAC, "windows" to RemotePlatform.WINDOWS, "generic" to RemotePlatform.GENERIC)
                    .forEach { (key, platform) ->
                        findPreference<Preference>("remote_template_$key")?.setOnPreferenceClickListener {
                            RemoteInputEditor.showTemplate(requireContext(), platform) { refreshValues() }
                            true
                        }
                    }
        }
        override fun onResume() { super.onResume(); refreshValues() }
        private fun refreshValues() {
            val prefs = RemoteInputPreferences(requireContext())
            listOf("mac" to RemotePlatform.MAC, "windows" to RemotePlatform.WINDOWS, "generic" to RemotePlatform.GENERIC)
                    .forEach { (key, platform) ->
                        val value = prefs.template(platform)
                        findPreference<Preference>("remote_template_$key")?.summary = getString(R.string.settings_template_value,
                                value.pointerSpeed, value.scrollSpeed)
                    }
        }
    }

    @Keep class Viewer : PrefFragment(R.xml.pref_viewer) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // If system does not support PiP, disable its preference
            val hasPiPSupport = Build.VERSION.SDK_INT >= 26 &&
                                requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

            if (!hasPiPSupport) {
                findPreference<SwitchPreference>("pip_enabled")!!.apply {
                    isEnabled = false
                    summary = getString(R.string.msg_pip_not_supported)
                }
            }
        }
    }

    @Keep class Input : PrefFragment(R.xml.pref_input) {
        private val visibilityTests = mutableMapOf<Preference, (Map<String, Any?>) -> Boolean>()
        private val enablementTests = mutableMapOf<Preference, (Map<String, Any?>) -> Boolean>()
        private val prefChangeListener = OnSharedPreferenceChangeListener { _, _ -> applyTests() }

        override fun onCreate(savedInstanceState: Bundle?) {
            // Migrate before ListPreference persists its XML defaults as if they were saved choices.
            val remotePreferences = RemoteInputPreferences(requireContext())
            super.onCreate(savedInstanceState)
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefChangeListener)
            findPreference<Preference>("remote_input_templates")!!.setOnPreferenceClickListener {
                RemoteInputEditor.showTemplates(requireContext())
                true
            }
            findPreference<ListPreference>("gesture_swipe3")!!.setOnPreferenceChangeListener { _, _ ->
                RemotePlatform.entries.filter { it != RemotePlatform.AUTO }.forEach { platform ->
                    remotePreferences.saveTemplate(platform, remotePreferences.getOverrides(platform).copy(threeFinger = "legacy"))
                }
                true
            }

            inputRecovery()

            val canChangePtrIcon = Build.VERSION.SDK_INT >= 24
            if (!canChangePtrIcon) {
                findPreference<SwitchPreference>("hide_local_cursor")!!.apply {
                    isEnabled = false
                    summary = getString(R.string.msg_ptr_hiding_not_supported)
                }
            }

            findPreference<SwitchPreference>("capture_pointer")!!.apply {
                showIf { Build.VERSION.SDK_INT >= 26 }
            }

            findPreference<ListPreferenceEx>("gesture_swipe1")!!.apply {
                enableIf { it["gesture_style"] != "touchpad" }
                disabledStateSummary = getString(R.string.pref_gesture_action_move_pointer)
            }
            findPreference<ListPreferenceEx>("gesture_long_press_swipe")!!.apply {
                enableIf { it["gesture_long_press"] !in listOf(null, "left-press", "left-drag") }
                disabledStateSummary = getText(R.string.pref_long_press_swipe_disabled_summary)
            }


        }

        override fun onDestroy() {
            super.onDestroy()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        }

        private fun Preference.enableIf(test: (Map<String, Any?>) -> Boolean) {
            enablementTests += this to test
            applyTests()
        }

        private fun Preference.showIf(test: (Map<String, Any?>) -> Boolean) {
            visibilityTests += this to test
            applyTests()
        }

        private fun applyTests() {
            val prefs = preferenceManager.sharedPreferences?.all ?: return
            visibilityTests.forEach { it.key.isVisible = it.value(prefs) }
            enablementTests.forEach { it.key.isEnabled = it.value(prefs) }
        }
    }

    @Keep class Server : PrefFragment(R.xml.pref_server) {
        private val authPrompt by lazy { DeviceAuthPrompt(requireActivity()) }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val savedServerLock = findPreference<SwitchPreference>("lock_saved_server")!!

            if (authPrompt.canLaunch()) {
                authPrompt.init({ savedServerLock.isChecked = !savedServerLock.isChecked }, { })
                savedServerLock.setOnPreferenceChangeListener { _, _ ->
                    authPrompt.launch(getString(R.string.title_unlock_dialog))
                    false
                }
            } else {
                savedServerLock.isEnabled = false
            }

            findPreference<Preference>("forget_known_hosts")!!.setOnPreferenceClickListener {
                ForgetKnownHostsDialog().show(childFragmentManager, "ForgetKnownHosts")
                true
            }
        }
    }

    @Keep class Tools : PrefFragment(R.xml.pref_tools) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            findPreference<Preference>("settings_about")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AboutActivity::class.java)); true
            }
            findPreference<Preference>("settings_bug_report")?.setOnPreferenceClickListener {
                val uri = (AboutActivity.BUG_REPORT_URL + Debugging.bugReportUrlParams()).toUri()
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { Toast.makeText(requireContext(), R.string.settings_no_browser, Toast.LENGTH_LONG).show() }
                true
            }
        }
    }
}
