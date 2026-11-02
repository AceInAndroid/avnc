/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gaurav.avnc.R
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.common.GlassStyle
import com.gaurav.avnc.ui.prefs.RemoteInputEditor
import com.gaurav.avnc.util.hideKeyboard
import com.gaurav.avnc.util.setClipboardTextWithNotification
import com.gaurav.avnc.viewmodel.HomeViewModel
import com.gaurav.avnc.viewmodel.ProfilesUiState
import com.gaurav.avnc.viewmodel.service.Discovery

/** Home list treats empty storage, empty search and discovery failures as distinct states. */
class ConnectionsFragment : Fragment() {
    private val model by activityViewModels<HomeViewModel>()
    private lateinit var style: GlassStyle
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var rows: Rows
    private var query = ""
    private var saved = ProfilesUiState(loading = true)
    private var found = emptyList<ServerProfile>()
    private var discovery = Discovery.UiState(Discovery.Phase.IDLE)
    private var locallyVisible = emptyList<ServerProfile>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        style = GlassStyle(requireContext())
        query = state?.getString("connection_search").orEmpty()
        val page = style.column(0).apply { setPadding(style.dp(20), style.dp(16), style.dp(20), 0) }
        val heading = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(style.label(getString(R.string.home_connections), 34f, true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(style.icon(R.drawable.ic_plus, getString(R.string.home_add_computer)).apply {
            id = R.id.home_add
            setOnClickListener { model.onNewProfile() }
        }, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
        page.addView(heading)
        page.addView(style.label(getString(R.string.home_subtitle), 14f).apply {
            setPadding(0, style.dp(4), 0, style.dp(16))
        })
        val searchRow = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = style.rounded(style.surface, 16)
            setPadding(style.dp(12), 0, style.dp(4), 0)
        }
        searchRow.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(style.secondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(style.dp(22), style.dp(22)))
        search = EditText(requireContext()).apply {
            id = R.id.home_search
            hint = getString(R.string.home_search_hint)
            contentDescription = hint
            textSize = 16f
            setTextColor(style.ink); setHintTextColor(style.secondary)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(style.dp(10), style.dp(12), 0, style.dp(12))
            setText(query)
            setOnEditorActionListener { _, _, _ -> hideKeyboard(this); clearFocus(); true }
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = style.icon(R.drawable.ic_clear, getString(R.string.home_search_clear)).apply {
            id = R.id.home_search_clear
            background = null
            isVisible = query.isNotEmpty()
            setOnClickListener { search.setText("") }
        }
        searchRow.addView(clear, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
        page.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = style.dp(8) })
        rows = Rows()
        list = RecyclerView(requireContext()).apply {
            id = R.id.home_list
            layoutManager = LinearLayoutManager(context)
            adapter = rows
            clipToPadding = false
            setPadding(0, 0, 0, style.dp(12))
            itemAnimator = null
        }
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        search.doAfterTextChanged {
            query = it.toString()
            clear.isVisible = query.isNotEmpty()
            render()
        }
        return FrameLayout(requireContext()).apply {
            setBackgroundColor(style.background)
            addView(page, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val width = minOf(right - left, style.dp(960))
                if (width > 0 && page.layoutParams.width != width) page.layoutParams = page.layoutParams.apply { this.width = width }
            }
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        model.profilesState.observe(viewLifecycleOwner) { saved = it; render() }
        model.discovery.servers.observe(viewLifecycleOwner) { found = it.orEmpty(); render() }
        model.discovery.state.observe(viewLifecycleOwner) { discovery = it; render() }
        model.rediscoveredProfiles.observe(viewLifecycleOwner) { locallyVisible = it.orEmpty(); render() }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && this::search.isInitialized) {
            hideKeyboard(search)
            search.clearFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("connection_search", query)
        super.onSaveInstanceState(outState)
    }

    private fun matches(profile: ServerProfile): Boolean = query.trim().let {
        it.isEmpty() || profile.name.contains(it, true) || profile.host.contains(it, true)
    }

    private fun render() {
        if (!this::rows.isInitialized) return
        val items = mutableListOf<Item>()
        val profiles = saved.profiles.filter(::matches)
        val local = found.filter(::matches)
        if (query.isNotBlank() && profiles.isEmpty() && local.isEmpty() && !saved.loading && saved.error == null) {
            items += Item("no-search", Kind.INFO, getString(R.string.home_no_results), getString(R.string.home_no_results_hint), action = Action.CLEAR)
        }
        items += Item("saved-header", Kind.HEADER, getString(R.string.home_my_computers))
        when {
            saved.loading && saved.profiles.isEmpty() -> items += Item("loading", Kind.PROGRESS, getString(R.string.home_profiles_loading))
            saved.error != null -> items += Item("read-error", Kind.INFO, getString(R.string.home_profiles_error), saved.error.orEmpty(), action = Action.RETRY)
            saved.profiles.isEmpty() && query.isBlank() -> items += Item("empty", Kind.INFO,
                    getString(R.string.home_profiles_empty), getString(R.string.home_profiles_empty_hint), action = Action.ADD)
        }
        profiles.forEach { profile ->
            val visible = locallyVisible.any { it.ID == profile.ID }
            items += Item("saved-${profile.ID}", Kind.COMPUTER, displayName(profile),
                    address(profile) + " · " + platform(profile) + if (visible) "\n" + getString(R.string.home_local_visible) else "", profile)
        }
        items += Item("local-header", Kind.HEADER, getString(R.string.home_local_network), detail = discovery.phase.name, action = Action.DISCOVERY)
        val (title, hint, busy) = when (discovery.phase) {
            Discovery.Phase.STARTING -> Triple(getString(R.string.home_discovery_starting), "", true)
            Discovery.Phase.SEARCHING -> Triple(getString(R.string.home_searching), "", true)
            Discovery.Phase.RESULTS -> Triple(getString(R.string.home_discovery_found, found.size), "", false)
            Discovery.Phase.EMPTY -> Triple(getString(R.string.home_discovery_empty), getString(R.string.home_discovery_help), false)
            Discovery.Phase.ERROR -> Triple(getString(R.string.home_discovery_failed), discovery.error.orEmpty(), false)
            Discovery.Phase.STOPPED -> Triple(getString(R.string.home_discovery_stopped), getString(R.string.home_discovery_help), false)
            Discovery.Phase.IDLE -> Triple(getString(R.string.home_discovery_idle), getString(R.string.home_discovery_help), false)
        }
        items += Item("discovery-state", if (busy) Kind.PROGRESS else Kind.INFO, title, hint)
        local.forEach { profile ->
            items += Item("local-${profile.name}-${profile.host}:${profile.port}", Kind.COMPUTER, displayName(profile), address(profile), profile)
        }
        rows.submitList(items)
    }

    private fun displayName(profile: ServerProfile) = profile.name.ifBlank { profile.host }
    private fun platform(profile: ServerProfile) = RemoteInputEditor.platformLabel(requireContext(), RemotePlatform.from(profile.remotePlatform))
    private fun address(profile: ServerProfile) = (if (profile.host.contains(':')) "[${profile.host}]" else profile.host) + ":${profile.port}"
    private fun discoveryAction() {
        if (discovery.phase in listOf(Discovery.Phase.STARTING, Discovery.Phase.SEARCHING, Discovery.Phase.EMPTY, Discovery.Phase.RESULTS)) model.stopDiscovery() else { model.stopDiscovery(); model.startDiscovery() }
    }

    private fun more(anchor: View, profile: ServerProfile) {
        PopupMenu(requireContext(), anchor).apply {
            if (profile.isSaved()) {
                menu.add(R.string.title_edit).setOnMenuItemClickListener { model.onEditProfile(profile); true }
                menu.add(R.string.title_duplicate).setOnMenuItemClickListener { model.onDuplicateProfile(profile); true }
            } else menu.add(R.string.home_save_computer).setOnMenuItemClickListener { model.onNewProfile(profile); true }
            menu.add(R.string.home_copy_address).setOnMenuItemClickListener { requireActivity().setClipboardTextWithNotification(address(profile)); true }
            if (profile.isSaved()) menu.add(R.string.title_delete).setOnMenuItemClickListener { model.deleteProfile(profile); true }
            show()
        }
    }

    private enum class Kind { HEADER, INFO, PROGRESS, COMPUTER }
    private enum class Action { NONE, ADD, CLEAR, RETRY, DISCOVERY }
    private data class Item(val key: String, val kind: Kind, val title: String, val detail: String = "",
                            val profile: ServerProfile? = null, val action: Action = Action.NONE)

    private inner class Rows : ListAdapter<Item, Holder>(object : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) = old.key == new.key
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }) {
        override fun getItemViewType(position: Int) = getItem(position).kind.ordinal
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(style.column(0))
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    }

    private inner class Holder(private val column: LinearLayout) : RecyclerView.ViewHolder(column) {
        fun bind(item: Item) {
            column.removeAllViews()
            column.tag = item.key
            column.setOnClickListener(null)
            column.isClickable = false
            column.background = null
            column.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = style.dp(8) }
            column.setPadding(0, 0, 0, 0)
            when (item.kind) {
                Kind.HEADER -> {
                    val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                    row.addView(style.label(item.title, 18f, true).apply {
                        ViewCompat.setAccessibilityHeading(this, true)
                        setPadding(style.dp(4), style.dp(16), 0, style.dp(8))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    if (item.action == Action.DISCOVERY) {
                        val running = discovery.phase in listOf(Discovery.Phase.STARTING, Discovery.Phase.SEARCHING, Discovery.Phase.EMPTY, Discovery.Phase.RESULTS)
                        row.addView(style.button(getString(if (running) R.string.home_stop_discovery else R.string.home_find_devices)).apply {
                            id = R.id.home_discovery_action
                            textSize = 14f
                            setOnClickListener { discoveryAction() }
                        }, LinearLayout.LayoutParams(-2, -2))
                    }
                    column.addView(row)
                }
                Kind.COMPUTER -> {
                    column.background = style.rounded()
                    val row = LinearLayout(requireContext()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(style.dp(12), style.dp(12), style.dp(8), style.dp(12))
                    }
                    row.addView(ImageView(requireContext()).apply {
                        setImageResource(R.drawable.ic_computer)
                        imageTintList = ColorStateList.valueOf(style.blue)
                        background = style.rounded(style.selected, 14)
                        setPadding(style.dp(10), style.dp(10), style.dp(10), style.dp(10))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
                    val text = style.column(0).apply { setPadding(style.dp(12), 0, style.dp(4), 0) }
                    text.addView(style.label(item.title, 17f, true))
                    text.addView(style.label(item.detail, 13f).apply { setPadding(0, style.dp(4), 0, 0) })
                    row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(style.icon(R.drawable.ic_control_more, getString(R.string.home_more_computer, item.title)).apply {
                        id = R.id.home_row_more
                        background = null
                        setOnClickListener { more(this, item.profile!!) }
                    }, LinearLayout.LayoutParams(style.dp(48), style.dp(48)))
                    column.addView(row)
                    column.isFocusable = true
                    column.setOnClickListener { model.startConnection(item.profile!!) }
                }
                Kind.INFO, Kind.PROGRESS -> {
                    column.background = style.rounded()
                    column.setPadding(style.dp(20), style.dp(20), style.dp(20), style.dp(20))
                    val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                    if (item.kind == Kind.PROGRESS) row.addView(ProgressBar(requireContext()).apply {
                        indeterminateTintList = ColorStateList.valueOf(style.blue)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(style.dp(24), style.dp(24)).apply { marginEnd = style.dp(12) })
                    row.addView(style.label(item.title, 17f, true))
                    column.addView(row)
                    if (item.detail.isNotBlank()) column.addView(style.label(item.detail, 14f).apply { setPadding(0, style.dp(8), 0, 0) })
                    if (item.action != Action.NONE) {
                        val (title, id) = when (item.action) {
                            Action.ADD -> R.string.home_add_computer to R.id.home_empty_add
                            Action.CLEAR -> R.string.home_search_clear to R.id.home_search_clear
                            else -> R.string.home_retry to R.id.home_retry_profiles
                        }
                        column.addView(style.button(getString(title), primary = item.action == Action.ADD).apply {
                            this.id = id
                            setOnClickListener {
                                when (item.action) {
                                    Action.ADD -> model.onNewProfile()
                                    Action.CLEAR -> search.setText("")
                                    Action.RETRY -> model.retryProfiles()
                                    else -> Unit
                                }
                            }
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(16) })
                    }
                }
            }
        }
    }
}
