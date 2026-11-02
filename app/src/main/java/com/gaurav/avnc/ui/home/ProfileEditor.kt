/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.home

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.BundleCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.descendants
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.FragmentProfileEditorAdvancedBinding
import com.gaurav.avnc.model.DisplayViewMode
import com.gaurav.avnc.model.RemotePlatform
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.model.StreamingPreset
import com.gaurav.avnc.ui.common.GlassStyle
import com.gaurav.avnc.ui.prefs.RemoteInputEditor
import com.gaurav.avnc.util.MsgDialog
import com.gaurav.avnc.util.OpenableDocument
import com.gaurav.avnc.util.parseMacAddress
import com.gaurav.avnc.viewmodel.EditorViewModel
import com.gaurav.avnc.viewmodel.HomeViewModel
import com.gaurav.avnc.viewmodel.service.PemKey
import com.gaurav.avnc.vnc.VncUri
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PROFILE_KEY = "profile"
private const val ADVANCED_KEY = "expandAdvanced"
private const val EDITOR_TAG = "profileEditor"

/** Address-only parsing: URI authentication is deliberately never applied to a draft. */
internal data class ProfileAddress(val host: String, val port: Int?, val hasSettings: Boolean)

internal fun parseProfileAddress(input: String): ProfileAddress? = runCatching {
    val text = input.trim()
    require(text.isNotEmpty())
    val protocol = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).find(text)?.value
    require(protocol == null || protocol.equals("vnc://", true))
    val withoutScheme = text.replaceFirst(Regex("^vnc:/?/?", RegexOption.IGNORE_CASE), "")
    val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
    require(authority.isNotEmpty() && !authority.contains('@'))
    // As in UrlBarActivity, an unbracketed IPv6 literal is a whole host, never host:port.
    val normalizedAuthority = if (!authority.startsWith('[') && authority.count { it == ':' } > 1)
        "[$authority]" else authority
    require(!normalizedAuthority.endsWith(':'))
    val uri = VncUri("vnc://" + normalizedAuthority + withoutScheme.substring(authority.length))
    val host = requireNotNull(uri.host).also { require(it.isNotBlank()) }
    uri.port?.let { require(it in 1..65535) }
    ProfileAddress(host, uri.port, withoutScheme.substringAfter('?', "").substringBefore('#').isNotBlank())
}.getOrNull()

/** Keep the public entry point used by Home and external connection/edit callers. */
fun startProfileEditor(host: FragmentActivity, profile: ServerProfile, preferAdvancedEditor: Boolean) {
    if (host.supportFragmentManager.findFragmentByTag(EDITOR_TAG) != null) return
    SimpleProfileEditor().apply {
        arguments = Bundle().apply {
            putParcelable(PROFILE_KEY, profile.copy())
            putBoolean(ADVANCED_KEY, preferAdvancedEditor)
        }
        show(host.supportFragmentManager, EDITOR_TAG)
    }
}

private fun getProfileArg(fragment: Fragment): ServerProfile = fragment.arguments?.let {
    BundleCompat.getParcelable(it, PROFILE_KEY, ServerProfile::class.java)
} ?: ServerProfile()

/** Both names survive for restored fragments and existing integrations; both use one editor. */
class SimpleProfileEditor : ProfileEditorSheet()
class AdvancedProfileEditor : ProfileEditorSheet()

open class ProfileEditorSheet : BottomSheetDialogFragment() {
    private val homeViewModel by activityViewModels<HomeViewModel>()
    private val viewModel by viewModels<EditorViewModel> { EditorViewModelFactory(this) }
    private lateinit var binding: FragmentProfileEditorAdvancedBinding
    private val style by lazy { GlassStyle(requireContext()) }
    private val keyFilePicker = registerForActivityResult(OpenableDocument()) { importPrivateKey(it) }
    private var discardDialog: androidx.appcompat.app.AlertDialog? = null
    private var explicitPort = false
    private var normalizingAddress = false
    private var submitted = false
    private var addressNotice: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        explicitPort = savedInstanceState?.getBoolean("explicitPort")
                ?: (getProfileArg(this).isSaved() || getProfileArg(this).port != 5900)
        submitted = savedInstanceState?.getBoolean("submitted") ?: false
        addressNotice = savedInstanceState?.getString("addressNotice")
        // Explicit close, Back and outside taps share the dirty-draft gate.
        isCancelable = true
        if (savedInstanceState == null &&
            (arguments?.getBoolean(ADVANCED_KEY) == true || this is AdvancedProfileEditor)) {
            viewModel.advancedExpanded.value = true
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
            object : BottomSheetDialog(requireContext(), theme) {
                override fun cancel() = requestDismiss()
            }.apply {
                setCanceledOnTouchOutside(false)
                onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() = requestDismiss()
                })
            }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentProfileEditorAdvancedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        binding.toolbar.title = getString(if (getProfileArg(this).isSaved())
            R.string.title_edit_server_profile else R.string.title_add_server_profile)
        binding.toolbar.setNavigationOnClickListener { requestDismiss() }
        binding.saveConnectBtn.setOnClickListener { save(connect = true) }
        binding.saveBtn.setOnClickListener { save(connect = false) }
        binding.tryBtn.setOnClickListener { tryConnection() }
        binding.keyImportBtn.setOnClickListener { keyFilePicker.launch(arrayOf("*/*")) }
        binding.platformBtn.setOnClickListener { choosePlatform() }
        binding.streamingBtn.setOnClickListener { chooseStreaming() }
        binding.displaysBtn.setOnClickListener { chooseDisplays() }
        binding.remoteInputSettings.setOnClickListener {
            RemoteInputEditor.showProfile(requireContext(), viewModel.profile, ::refreshChoiceLabels)
        }
        binding.wolHelpBtn.setOnClickListener {
            MsgDialog.show(parentFragmentManager, getString(R.string.title_enable_wol), getString(R.string.msg_wake_on_lan_help))
        }
        binding.buttonUpDelayHelpBtn.setOnClickListener {
            MsgDialog.show(parentFragmentManager, getString(R.string.title_button_up_delay), getString(R.string.msg_button_up_delay_help))
        }
        setupAppearance()
        setupAddressValidation()
        setupCompactLayout()
        setupExpansion(binding.advancedToggle, binding.advancedContainer, viewModel.advancedExpanded)
        setupExpansion(binding.connectionHeader, binding.connectionGroup, viewModel.connectionExpanded)
        setupExpansion(binding.pictureHeader, binding.pictureGroup, viewModel.pictureExpanded)
        setupExpansion(binding.inputHeader, binding.inputGroup, viewModel.inputExpanded)
        setupExpansion(binding.otherHeader, binding.otherGroup, viewModel.otherExpanded)
        setupToolbarMenu()
        refreshChoiceLabels()
        viewModel.saveState.observe(viewLifecycleOwner) { renderSaveState(it) }
        viewModel.useRawEncoding.observe(viewLifecycleOwner) { raw ->
            binding.imageQuality.isEnabled = raw != true &&
                    viewModel.saveState.value != EditorViewModel.SaveState.SAVING
        }
        viewModel.savedProfile.observe(viewLifecycleOwner) { if (it != null) dismiss() }
        // Android restores field cursor/text/scroll state after this callback as usual.
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as BottomSheetDialog
        val config = resources.configuration
        val tablet = config.screenWidthDp >= 600
        val shortWindow = config.screenHeightDp < 480
        val height = if (shortWindow) ViewGroup.LayoutParams.MATCH_PARENT
        else style.dp((config.screenHeightDp * if (tablet) .88f else .92f).toInt())
        sheetDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(this, true)
            setLayout(if (tablet && !shortWindow) minOf(style.dp(600), resources.displayMetrics.widthPixels) else ViewGroup.LayoutParams.MATCH_PARENT,
                    height)
            setGravity(if (tablet && !shortWindow) Gravity.CENTER else Gravity.BOTTOM)
        }
        sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            layoutParams = layoutParams.apply { this.height = ViewGroup.LayoutParams.MATCH_PARENT }
            background = style.rounded(style.background, if (shortWindow) 0 else 24)
            clipToOutline = true
        }
        sheetDialog.behavior.apply {
            // Material's default sheet width cap otherwise defeats the short-window full-screen layout.
            maxWidth = if (tablet && !shortWindow) style.dp(600) else resources.displayMetrics.widthPixels
            isHideable = false
            isDraggable = !shortWindow
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheetDialog.findViewById<View>(com.google.android.material.R.id.touch_outside)
                ?.setOnClickListener { requestDismiss() }
        binding.sheetHandle.isVisible = !shortWindow
    }

    private fun setupAppearance() {
        binding.root.setBackgroundColor(style.background)
        binding.sheetHandle.background = style.rounded(style.line, 2)
        binding.editorSubtitle.setTextColor(style.secondary)
        binding.addressFeedback.setTextColor(style.secondary)
        binding.displaysHint.setTextColor(style.secondary)
        binding.saveError.setTextColor(style.destructive)
        binding.toolbar.setTitleTextColor(style.ink)
        binding.toolbar.navigationIcon?.setTint(style.blue)
        // Keep the form and its save actions visible instead of the IME's landscape extract editor.
        binding.editorSurface.descendants.filterIsInstance<EditText>().forEach {
            it.imeOptions = it.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        listOf(binding.basicGroup, binding.connectionGroup, binding.pictureGroup,
                binding.inputGroup, binding.otherGroup).forEach { it.background = style.rounded() }
        binding.editorSurface.descendants.filterIsInstance<MaterialButton>().forEach { button ->
            val primary = button === binding.saveConnectBtn
            button.isAllCaps = false
            button.cornerRadius = style.dp(16)
            button.minimumHeight = style.dp(48)
            button.setTextColor(if (primary) Color.WHITE else style.blue)
            button.backgroundTintList = ColorStateList.valueOf(if (primary) style.actionFill else style.selected)
            button.strokeWidth = 0
            button.elevation = 0f
        }
        binding.editorFooter.setBackgroundColor(style.background)
    }

    private fun setupCompactLayout() {
        if (resources.configuration.screenHeightDp >= 480) return
        // ScrollView's resize animation otherwise replays coordinates from before the axis change.
        binding.scrollView.isSmoothScrollingEnabled = false
        var activeField: View? = null
        binding.editorContent.descendants.filterIsInstance<EditText>().forEach { field ->
            val listener = field.onFocusChangeListener
            field.setOnFocusChangeListener { view, focused ->
                if (focused) activeField = view
                listener?.onFocusChange(view, focused)
            }
        }
        binding.editorActions.orientation = android.widget.LinearLayout.HORIZONTAL
        listOf(binding.saveConnectBtn, binding.saveBtn).forEachIndexed { index, button ->
            button.layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index > 0) marginStart = style.dp(8) }
        }
        var previousCompact: Boolean? = null
        fun update(height: Int) {
            val compact = height in 1 until style.dp(240)
            if (previousCompact == compact) return
            previousCompact = compact
            val previousFocus = binding.editorContent.findFocus()?.takeIf { it !== binding.editorContent }
                    ?: activeField.takeIf { binding.editorSurface.findFocus() == null }
            // An oversized landscape IME can leave less than 120dp for the form.
            binding.toolbar.isVisible = !compact
            binding.editorSubtitle.isVisible = !compact
            binding.editorSurface.orientation = if (compact) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL
            binding.scrollView.layoutParams = android.widget.LinearLayout.LayoutParams(
                    if (compact) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                    if (compact) ViewGroup.LayoutParams.MATCH_PARENT else 0, 1f)
            binding.editorFooter.layoutParams = android.widget.LinearLayout.LayoutParams(
                    if (compact) minOf(style.dp(320), resources.displayMetrics.widthPixels / 2) else ViewGroup.LayoutParams.MATCH_PARENT,
                    if (compact) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
            binding.editorFooter.gravity = if (compact) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            binding.editorFooter.setPadding(style.dp(16), if (compact) 0 else style.dp(8),
                    style.dp(16), if (compact) 0 else style.dp(8))
            binding.editorContent.doOnNextLayout {
                val focus = if (binding.editorSurface.findFocus() == null) previousFocus
                else binding.editorContent.findFocus()?.takeIf { it !== binding.editorContent }
                focus?.let { focused ->
                    focused.requestFocus()
                    val field = Rect(0, 0, focused.width, focused.height)
                    binding.editorContent.offsetDescendantRectToMyCoords(focused, field)
                    // Use the new layout's coordinates and preserve focus through the axis change.
                    binding.scrollView.scrollTo(0, field.top.coerceAtLeast(0))
                }
            }
        }
        binding.editorSurface.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> update(bottom - top) }
        binding.editorSurface.post { update(binding.editorSurface.height) }
    }

    private fun setupAddressValidation() {
        binding.executePendingBindings()
        var previousAddress = binding.host.text.toString()
        binding.host.doAfterTextChanged {
            val changed = previousAddress != it.toString()
            previousAddress = it.toString()
            if (!normalizingAddress) {
                if (changed) addressNotice = null
                showAddressNotice()
                validateAddress(normalize = false)
            }
        }
        binding.host.setOnFocusChangeListener { _, focused ->
            if (!focused && binding.host.text.isNotBlank()) validateAddress(normalize = true)
        }
        var previousPort = binding.port.text.toString()
        binding.port.doAfterTextChanged {
            val value = it.toString()
            if (!normalizingAddress && value != previousPort) explicitPort = true
            previousPort = value
            validatePortLive(binding.port)
        }
        binding.sshPort.doAfterTextChanged {
            if (viewModel.useSshTunnel.value == true) validatePortLive(binding.sshPort)
        }
        binding.wolPort.doAfterTextChanged {
            if (viewModel.enableWol.value == true) validatePortLive(binding.wolPort)
        }
        showAddressNotice()
    }

    private fun validatePortLive(field: EditText) {
        val value = field.text.toString().toIntOrNull()
        field.error = if ((field.text.isNotEmpty() || submitted || field.hasFocus()) &&
                         (value == null || value !in 1..65535))
            getString(R.string.quick_editor_invalid_port) else null
    }

    private fun showAddressNotice() {
        binding.addressFeedback.text = addressNotice
        binding.addressFeedback.isVisible = !addressNotice.isNullOrBlank()
    }

    private fun validateAddress(normalize: Boolean): Boolean {
        val raw = binding.host.text.toString()
        if (raw.isBlank()) {
            binding.host.error = if (submitted) getString(R.string.msg_required) else null
            return false
        }
        val parsed = parseProfileAddress(raw)
        if (parsed == null) {
            binding.host.error = getString(R.string.quick_editor_invalid_address)
            return false
        }
        binding.host.error = null
        val notices = mutableListOf<String>()
        if (parsed.port != null && explicitPort && binding.port.text.toString().toIntOrNull() != parsed.port)
            notices += getString(R.string.quick_editor_address_port_kept)
        if (parsed.hasSettings) notices += getString(R.string.quick_editor_address_settings)
        if (notices.isNotEmpty()) addressNotice = notices.joinToString("\n")
        showAddressNotice()
        if (!normalize) return true
        normalizingAddress = true
        try {
            parsed.port?.let { port ->
                if (!explicitPort) {
                    viewModel.profile.port = port
                    binding.port.setText(port.toString())
                }
            }
            // Keep an earlier warning when an already-normalized host is submitted again.
            viewModel.profile.host = parsed.host
            if (raw != parsed.host) binding.host.setText(parsed.host)
        } finally { normalizingAddress = false }
        showAddressNotice()
        return true
    }

    private fun setupExpansion(button: MaterialButton, content: View, expanded: MutableLiveData<Boolean>) {
        button.setOnClickListener { expanded.value = expanded.value != true }
        expanded.observe(viewLifecycleOwner) { open ->
            content.isVisible = open == true
            button.isSelected = open == true
            ViewCompat.setStateDescription(button, getString(if (open == true)
                R.string.quick_editor_expanded else R.string.quick_editor_collapsed))
            button.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(),
                    if (open == true) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            button.iconGravity = MaterialButton.ICON_GRAVITY_END
            button.iconTint = ColorStateList.valueOf(style.blue)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                menu.add(R.string.title_always_show_advanced_editor).setCheckable(true)
                        .setChecked(viewModel.pref.ui.preferAdvancedEditor).setOnMenuItemClickListener {
                            viewModel.pref.ui.preferAdvancedEditor = !viewModel.pref.ui.preferAdvancedEditor
                            it.isChecked = viewModel.pref.ui.preferAdvancedEditor
                            true
                        }
            }
            override fun onMenuItemSelected(item: MenuItem) = false
        }, viewLifecycleOwner)
    }

    private fun refreshChoiceLabels() {
        if (view == null) return
        binding.platformBtn.text = getString(R.string.quick_editor_choice, getString(R.string.remote_input_platform),
                RemoteInputEditor.platformLabel(requireContext(), RemotePlatform.from(viewModel.profile.remotePlatform)))
        binding.remoteInputSettings.text = getString(R.string.remote_input_title)
        val quality = when (StreamingPreset.from(viewModel.profile.streamingPreset)) {
            StreamingPreset.SMOOTH -> R.string.mac_quality_smooth
            StreamingPreset.HD -> R.string.mac_quality_hd
            StreamingPreset.LEGACY -> R.string.quick_editor_quality_custom
        }
        binding.streamingBtn.text = getString(R.string.quick_editor_choice, getString(R.string.mac_quality), getString(quality))
        // Presets own encoding parameters; the retained manual controls apply in Custom.
        val customQuality = StreamingPreset.from(viewModel.profile.streamingPreset) == StreamingPreset.LEGACY
        listOf(binding.imageQualityLabel, binding.imageQuality, binding.rawEncoding).forEach {
            it.isVisible = customQuality
        }
        binding.displaysBtn.text = getString(R.string.quick_editor_choice, getString(R.string.mac_displays),
                getString(if (DisplayViewMode.from(viewModel.profile.displayViewMode) == DisplayViewMode.ALL)
                    R.string.mac_display_all else R.string.mac_display_single))
    }

    private fun choosePlatform() {
        val options = RemotePlatform.entries.toList()
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.remote_input_platform)
                .setSingleChoiceItems(options.map { RemoteInputEditor.platformLabel(requireContext(), it) }.toTypedArray(),
                        options.indexOf(RemotePlatform.from(viewModel.profile.remotePlatform))) { dialog, index ->
                    viewModel.profile.remotePlatform = options[index].id
                    refreshChoiceLabels()
                    dialog.dismiss()
                }.show()
    }

    private fun chooseStreaming() {
        val options = listOf(StreamingPreset.SMOOTH, StreamingPreset.HD, StreamingPreset.LEGACY)
        val labels = intArrayOf(R.string.mac_quality_smooth, R.string.mac_quality_hd, R.string.quick_editor_quality_custom)
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mac_quality)
                .setSingleChoiceItems(labels.map { getString(it) }.toTypedArray(),
                        options.indexOf(StreamingPreset.from(viewModel.profile.streamingPreset))) { dialog, index ->
                    viewModel.profile.streamingPreset = options[index].id
                    refreshChoiceLabels()
                    dialog.dismiss()
                }.show()
    }

    private fun chooseDisplays() {
        val options = listOf(DisplayViewMode.SINGLE, DisplayViewMode.ALL)
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.mac_displays)
                .setSingleChoiceItems(arrayOf(getString(R.string.mac_display_single), getString(R.string.mac_display_all)),
                        options.indexOf(DisplayViewMode.from(viewModel.profile.displayViewMode))) { dialog, index ->
                    viewModel.profile.displayViewMode = options[index].id
                    refreshChoiceLabels()
                    dialog.dismiss()
                }.show()
    }

    private fun renderSaveState(state: EditorViewModel.SaveState) {
        val busy = state == EditorViewModel.SaveState.SAVING
        binding.editorSurface.descendants.forEach { it.isEnabled = !busy }
        // Preserve the raw-encoding field dependency after restoring input controls.
        binding.imageQuality.isEnabled = !busy && viewModel.useRawEncoding.value != true
        binding.savingProgress.isVisible = busy
        binding.saveConnectBtn.setText(if (busy) R.string.quick_editor_saving else R.string.quick_editor_save_connect)
        binding.saveError.isVisible = state == EditorViewModel.SaveState.ERROR
        if (state == EditorViewModel.SaveState.ERROR) {
            binding.saveError.text = getString(R.string.quick_editor_save_failed) +
                    viewModel.saveError.value?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        }
    }

    private fun requestDismiss() {
        if (!isAdded || viewModel.saveState.value == EditorViewModel.SaveState.SAVING) return
        if (!viewModel.hasUnsavedChanges()) {
            dismiss()
            return
        }
        if (discardDialog?.isShowing == true) return
        discardDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.quick_editor_discard_title)
                .setMessage(R.string.quick_editor_discard_message)
                .setNegativeButton(R.string.quick_editor_continue) { _, _ -> }
                .setPositiveButton(R.string.quick_editor_discard) { _, _ ->
                    viewModel.discardChanges()
                    dismiss()
                }.show()
    }

    private fun save(connect: Boolean) {
        if (viewModel.saveState.value == EditorViewModel.SaveState.SAVING) return
        if (validate()) viewModel.submit(connect, homeViewModel::persistProfile, homeViewModel::startConnection)
    }

    private fun tryConnection() {
        if (viewModel.saveState.value == EditorViewModel.SaveState.SAVING || !validate()) return
        homeViewModel.startConnection(viewModel.prepareProfileForSave().copy(ID = 0))
    }

    private fun validate(): Boolean {
        submitted = true
        binding.executePendingBindings()
        val errors = mutableListOf<TextView>()
        binding.editorSurface.descendants.filterIsInstance<TextView>().forEach { it.error = null }
        fun required(field: EditText) {
            if (field.text.isNullOrBlank()) {
                field.error = getString(R.string.msg_required)
                errors.add(field)
            }
        }
        fun port(field: EditText, update: (Int) -> Unit) {
            val value = field.text.toString().toIntOrNull()
            if (value == null || value !in 1..65535) {
                field.error = getString(R.string.quick_editor_invalid_port)
                errors.add(field)
            } else update(value)
        }
        if (!validateAddress(normalize = true)) errors.add(binding.host)
        port(binding.port) { viewModel.profile.port = it }
        if (viewModel.useRepeater.value == true) {
            val id = binding.idOnRepeater.text.toString().toLongOrNull()
            if (id == null || id !in 0..999_999_999L) {
                binding.idOnRepeater.error = getString(R.string.quick_editor_invalid_repeater)
                errors.add(binding.idOnRepeater)
            }
        }
        if (viewModel.useSshTunnel.value == true) {
            required(binding.sshHost)
            port(binding.sshPort) { viewModel.profile.sshPort = it }
            required(binding.sshUsername)
            if (viewModel.sshUsePrivateKey.value == true && viewModel.hasSshPrivateKey.value != true) {
                binding.keyImportBtn.error = getString(R.string.msg_required)
                errors.add(binding.keyImportBtn)
            }
        }
        if (viewModel.enableWol.value == true) {
            if (runCatching { parseMacAddress(binding.wolMac.text.toString()) }.isFailure) {
                binding.wolMac.error = getString(R.string.msg_invalid_mac_address)
                errors.add(binding.wolMac)
            }
            port(binding.wolPort) { viewModel.profile.wolPort = it }
        }
        errors.firstOrNull()?.let { first ->
            if (binding.connectionGroup.descendants.any { it === first }) {
                viewModel.advancedExpanded.value = true
                viewModel.connectionExpanded.value = true
            }
            if (binding.otherGroup.descendants.any { it === first }) {
                viewModel.advancedExpanded.value = true
                viewModel.otherExpanded.value = true
            }
            first.post {
                first.requestFocus()
                first.requestRectangleOnScreen(Rect(0, 0, first.width, first.height), true)
            }
            viewModel.markInvalid()
        }
        return errors.isEmpty()
    }

    private fun importPrivateKey(uri: Uri?) {
        if (uri == null || viewModel.saveState.value == EditorViewModel.SaveState.SAVING) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openAssetFileDescriptor(uri, "r")!!.use { file ->
                        check(file.length < 2 * 1024 * 1024) { "File is too big [${file.length}]" }
                        // A provider may report unknown length; bound the read as well.
                        val bytes = file.createInputStream().use { it.readBytesLimited(2 * 1024 * 1024) }
                        String(bytes, Charsets.UTF_8).also { PemKey(it) }
                    }
                }
            }
            if (view == null) return@launch
            result.onSuccess {
                viewModel.profile.sshPrivateKey = it
                viewModel.hasSshPrivateKey.value = true
                binding.keyImportBtn.error = null
                Snackbar.make(binding.root, R.string.msg_imported, Snackbar.LENGTH_SHORT).show()
            }.onFailure {
                MsgDialog.show(parentFragmentManager, getString(R.string.msg_invalid_key_file), it.message.orEmpty())
                Log.e("ProfileEditor", "Error importing Private Key", it)
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(bytes.size() + count <= limit) { "File is too big" }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    override fun onDestroyView() {
        discardDialog?.dismiss()
        discardDialog = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("explicitPort", explicitPort)
        outState.putBoolean("submitted", submitted)
        outState.putString("addressNotice", addressNotice)
        super.onSaveInstanceState(outState)
    }

    private class EditorViewModelFactory(private val fragment: Fragment) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                modelClass.cast(EditorViewModel(fragment.requireActivity().application,
                        extras.createSavedStateHandle(), getProfileArg(fragment)))!!
    }
}
