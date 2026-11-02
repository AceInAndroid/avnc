/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.prefs

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.gaurav.avnc.R
import com.gaurav.avnc.ui.common.GlassStyle
import com.google.android.material.appbar.MaterialToolbar
import org.xmlpull.v1.XmlPullParser

/** One settings navigation stack, hosted by either the home tab or the legacy activity. */
class SettingsRootFragment : Fragment(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    companion object {
        const val HIGHLIGHT_KEY = "settings_highlight_key"
        private const val CHILD_TAG = "settings_page"
    }

    private lateinit var home: ScrollView
    private lateinit var pages: FrameLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var results: LinearLayout
    private lateinit var search: SearchView
    private var query = ""
    private var homeScroll = 0
    private var pageTitle: CharSequence = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        query = savedInstanceState?.getString("query").orEmpty()
        homeScroll = savedInstanceState?.getInt("home_scroll") ?: 0
        pageTitle = savedInstanceState?.getCharSequence("page_title") ?: ""
        childFragmentManager.addOnBackStackChangedListener { if (view != null) updateNavigation() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val style = GlassStyle(requireContext())
        val root = style.column(0).apply { setBackgroundColor(style.background) }
        toolbar = MaterialToolbar(requireContext()).apply {
            id = R.id.toolbar
            setBackgroundColor(style.glass)
            setTitleTextColor(style.ink)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = getString(R.string.settings_back)
            setNavigationOnClickListener { handleBack() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        val content = FrameLayout(requireContext())
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        home = ScrollView(requireContext()).apply { id = R.id.settings_home; isFillViewport = true }
        val column = style.column(20)
        column.addView(style.label(getString(R.string.title_settings), 34f, true))
        column.addView(style.label(getString(R.string.settings_intro), 14f).apply {
            setPadding(0, style.dp(4), 0, style.dp(20))
        })
        search = SearchView(requireContext()).apply {
            id = R.id.settings_search
            isIconified = false
            setIconifiedByDefault(false)
            queryHint = getString(R.string.settings_search_hint)
            background = style.rounded(radius = 18)
            minimumHeight = style.dp(52)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?) = true
                override fun onQueryTextChange(text: String?): Boolean {
                    this@SettingsRootFragment.query = text.orEmpty()
                    renderResults()
                    return true
                }
            })
        }
        column.addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = style.dp(20) })
        results = style.column(0).apply { id = R.id.settings_results }
        column.addView(results)
        home.addView(column)
        content.addView(home, FrameLayout.LayoutParams(-1, -1))
        pages = FrameLayout(requireContext()).apply { id = R.id.settings_child_host }
        content.addView(pages, FrameLayout.LayoutParams(-1, -1))
        search.setQuery(query, false)
        search.clearFocus()
        renderResults()
        home.post { home.scrollTo(0, homeScroll) }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateNavigation()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && ::search.isInitialized) {
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(search.windowToken, 0)
            search.clearFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("query", query)
        outState.putInt("home_scroll", home.scrollY)
        outState.putCharSequence("page_title", pageTitle)
    }

    fun navigate(fragment: Fragment) {
        if (childFragmentManager.isStateSaved) return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        childFragmentManager.beginTransaction()
                .replace(R.id.settings_child_host, fragment, CHILD_TAG)
                .addToBackStack(null).commit()
    }

    fun navigate(className: String, highlightKey: String? = null) {
        val fragment = childFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, className)
        fragment.arguments = bundleOf(HIGHLIGHT_KEY to highlightKey)
        navigate(fragment)
    }

    /** Returns true only when an in-tab page was closed. The host owns leaving the settings tab. */
    fun handleBack(): Boolean {
        if (childFragmentManager.isStateSaved || childFragmentManager.backStackEntryCount == 0) return false
        childFragmentManager.popBackStackImmediate()
        return true
    }

    fun setPageTitle(title: CharSequence) {
        pageTitle = title
        if (::toolbar.isInitialized) { toolbar.title = title; toolbar.menu.clear() }
    }

    fun setPageAction(title: CharSequence, action: () -> Unit) {
        toolbar.menu.clear()
        toolbar.menu.add(title).setOnMenuItemClickListener { action(); true }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        navigate(checkNotNull(pref.fragment))
        return true
    }

    private fun updateNavigation() {
        val nested = childFragmentManager.backStackEntryCount > 0
        home.visibility = if (nested) View.GONE else View.VISIBLE
        toolbar.visibility = if (nested) View.VISIBLE else View.GONE
        pages.visibility = if (nested) View.VISIBLE else View.GONE
        toolbar.title = pageTitle
    }

    private fun renderResults() {
        if (!::results.isInitialized) return
        val style = GlassStyle(requireContext())
        results.removeAllViews()
        val entries = if (query.isBlank()) SettingsSearch.groups(requireContext()) else SettingsSearch.find(requireContext(), query)
        if (entries.isEmpty()) {
            results.addView(style.label(getString(R.string.settings_search_empty), 16f).apply {
                setPadding(style.dp(12), style.dp(24), style.dp(12), style.dp(24))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
        }
        entries.forEach { entry ->
            val row = style.column(16).apply {
                background = style.rounded(radius = 22)
                minimumHeight = style.dp(80)
                isClickable = true
                isFocusable = true
                gravity = Gravity.CENTER_VERTICAL
                tag = entry.key
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(style.line),
                        style.rounded(radius = 22), style.rounded(radius = 22))
                addView(style.label(entry.title, 17f, true))
                addView(style.label(listOfNotNull(entry.category, entry.summary.takeIf { it.isNotBlank() }).joinToString(" · "), 13f)
                        .apply { setPadding(0, style.dp(4), 0, 0) })
                setOnClickListener { navigate(entry.destination, entry.key) }
            }
            results.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = style.dp(10) })
        }
    }
}

internal fun Fragment.settingsRoot(): SettingsRootFragment? {
    var host = parentFragment
    while (host != null) {
        if (host is SettingsRootFragment) return host
        host = host.parentFragment
    }
    return null
}

internal fun Fragment.updateSettingsTitle(title: CharSequence) {
    val host = settingsRoot()
    if (host != null) host.setPageTitle(title) else activity?.title = title
}

/** Read XML metadata without inflating Preferences or persisting their defaults. */
object SettingsSearch {
    data class Entry(val key: String?, val title: String, val summary: String, val destination: String, val category: String? = null)
    private data class Section(val title: Int, val summary: Int, val resource: Int, val destination: Class<out Fragment>)
    private val sections get() = listOf(
            Section(R.string.settings_appearance, R.string.settings_appearance_summary, R.xml.pref_appearance, PrefsActivity.Appearance::class.java),
            Section(R.string.settings_input, R.string.settings_input_summary, R.xml.pref_input_overview, PrefsActivity.InputOverview::class.java),
            Section(R.string.settings_platform, R.string.settings_platform_summary, R.xml.pref_platform, PrefsActivity.Platform::class.java),
            Section(R.string.settings_connection, R.string.settings_connection_summary, R.xml.pref_server, PrefsActivity.Server::class.java),
            Section(R.string.settings_backup, R.string.settings_backup_summary, 0, ImportExportFragment::class.java),
            Section(R.string.settings_advanced, R.string.settings_advanced_summary, R.xml.pref_tools, PrefsActivity.Tools::class.java))

    fun groups(context: Context): List<Entry> = sections.map {
        Entry(null, context.getString(it.title), context.getString(it.summary), it.destination.name)
    }

    fun find(context: Context, query: String): List<Entry> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return groups(context)
        val entries = groups(context).toMutableList()
        val resources = sections.filter { it.resource != 0 }.map { Triple(it.resource, it.destination.name, context.getString(it.title)) } + listOf(
                Triple(R.xml.pref_input, PrefsActivity.Input::class.java.name, context.getString(R.string.settings_input)),
                Triple(R.xml.pref_viewer, PrefsActivity.Viewer::class.java.name, context.getString(R.string.settings_appearance)))
        resources.forEach { (resource, destination, category) ->
            context.resources.getXml(resource).use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != XmlPullParser.START_TAG || xml.name.endsWith("PreferenceScreen") || xml.name.endsWith("PreferenceCategory")) continue
                    fun value(name: String): String {
                        val ns = if (xml.getAttributeValue("http://schemas.android.com/apk/res-auto", name) != null)
                            "http://schemas.android.com/apk/res-auto" else "http://schemas.android.com/apk/res/android"
                        val id = xml.getAttributeResourceValue(ns, name, 0)
                        return if (id != 0) context.getString(id) else xml.getAttributeValue(ns, name).orEmpty()
                    }
                    val title = value("title")
                    val key = value("key").ifBlank { null }
                    if (key == "capture_pointer" && android.os.Build.VERSION.SDK_INT < 26) continue
                    var summary = value("summary")
                    val stored = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).all[key]
                    if (summary.isBlank()) summary = value(if (stored == true) "summaryOn" else "summaryOff")
                    val entriesId = xml.getAttributeResourceValue("http://schemas.android.com/apk/res-auto", "entries", 0)
                            .takeIf { it != 0 } ?: xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "entries", 0)
                    val valuesId = xml.getAttributeResourceValue("http://schemas.android.com/apk/res-auto", "entryValues", 0)
                            .takeIf { it != 0 } ?: xml.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "entryValues", 0)
                    if (summary.isBlank() && entriesId != 0 && valuesId != 0) {
                        val index = context.resources.getStringArray(valuesId).indexOf(stored?.toString() ?: value("defaultValue"))
                        summary = context.resources.getStringArray(entriesId).getOrNull(index).orEmpty()
                    }
                    if (title.isNotBlank()) entries += Entry(key, title, summary, destination, category)
                }
            }
        }
        return entries.distinctBy { it.key ?: it.title }.filter { entry ->
            val text = "${entry.title} ${entry.summary}".lowercase()
            tokens.all { it in text }
        }
    }
}
