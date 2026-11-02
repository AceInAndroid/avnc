/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.gaurav.avnc.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.util.ClipboardHistory
import com.gaurav.avnc.util.getClipboard
import com.gaurav.avnc.util.getClipboardTextSnapshot
import com.gaurav.avnc.util.setClipboardText
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foreground-only text sharing. Android deliberately disallows reading other apps' clips in background. */
class ClipboardControls(private val activity: VncActivity) {
    private val model get() = activity.viewModel
    private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
    private val history = ClipboardHistory(activity)
    private val manager: ClipboardManager = getClipboard(activity)
    private val style = ControlStyle(activity)
    private var resumed = false
    private var focused = activity.hasWindowFocus()
    private var dialogFocused = false
    private var dialogDecor: View? = null
    private val dialogFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        dialogFocused = hasFocus
        updateListening()
    }
    private var listening = false
    private var closed = false
    private var captureJob: Job? = null
    private var initialSyncJob: Job? = null
    private var dialog: AlertDialog? = null
    private var entriesHost: LinearLayout? = null
    private var stateLabel: TextView? = null
    private var sendCurrentButton: MaterialButton? = null
    private val sendHistoryButtons = mutableListOf<MaterialButton>()
    private var autoSwitch: MaterialCheckBox? = null
    private var masterSwitch: MaterialCheckBox? = null
    private var lastSentText: String? = null
    private var observedRemoteRevision = 0L
    private var lastObservedText: String? = null
    private var connectionReadyAt = 0L
    private var wasConnected = false
    private var settingControls = false
    private var copyingHistory = false
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (listening && !copyingHistory) captureCurrent(send = autoEnabled, requireFocus = true)
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "clipboard_sync" || key == "clipboard_auto_send") {
            // Preference changes revoke queued automatic work even when the listener stays attached.
            captureJob?.cancel()
            lastSentText = null
            updateListening()
            if (listening) captureCurrent(send = autoEnabled, requireFocus = true)
            refreshSwitches()
        }
    }
    private val masterEnabled get() = prefs.getBoolean("clipboard_sync", true)
    private val autoEnabled get() = prefs.getBoolean("clipboard_auto_send", false)
    private val foreground get() = resumed && !closed && model.inPiPMode.value != true
    private val canSend get() = foreground && masterEnabled && model.connected &&
            model.profile.viewMode != ServerProfile.VIEW_MODE_NO_INPUT

    init { prefs.registerOnSharedPreferenceChangeListener(prefListener) }

    fun onResume() {
        resumed = true
        focused = activity.hasWindowFocus()
        dialogFocused = dialogDecor?.hasWindowFocus() == true
        updateListening()
    }
    fun onPause() {
        resumed = false; focused = false; dialogFocused = false
        updateListening()
    }
    fun onWindowFocusChanged(hasFocus: Boolean) { focused = hasFocus; updateListening() }
    fun onPiPChanged() { updateListening(); refreshSwitches() }
    fun onStateChanged() {
        initialSyncJob?.cancel()
        if (!model.connected) lastSentText = null
        if (model.connected && !wasConnected) connectionReadyAt = android.os.SystemClock.uptimeMillis() + 1000
        wasConnected = model.connected
        updateListening()
        refreshSwitches()
        if (model.connected) {
            initialSyncJob = activity.lifecycleScope.launch {
                // Extended clipboard support is negotiated in the first update exchange.
                delay(1000)
                if (listening) captureCurrent(send = autoEnabled, requireFocus = true)
            }
        }
    }

    fun close() {
        closed = true
        updateListening()
        initialSyncJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        dialog?.dismiss()
    }

    private fun updateListening() {
        val wanted = foreground && (focused || dialogFocused) && masterEnabled && model.connected
        if (wanted == listening) return
        listening = wanted
        if (wanted) {
            manager.addPrimaryClipChangedListener(listener)
            captureCurrent(send = autoEnabled, requireFocus = true)
        } else {
            manager.removePrimaryClipChangedListener(listener)
            captureJob?.cancel()
        }
    }

    /** An explicit action; it works while the viewer's own dialog has window focus. */
    fun sendCurrentClipboard() = captureCurrent(send = true, requireFocus = false, notify = true)

    private fun captureCurrent(send: Boolean, requireFocus: Boolean, notify: Boolean = false) {
        if (!foreground || (requireFocus && !listening)) return
        captureJob?.cancel()
        captureJob = activity.lifecycleScope.launch {
            try {
                if (send && requireFocus) delay((connectionReadyAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
                val snapshot = getClipboardTextSnapshot(activity)
                if (!foreground || (requireFocus && !listening)) return@launch
                if (snapshot == null || !ClipboardHistory.accepts(snapshot.text)) {
                    if (notify) message(R.string.clipboard_no_text)
                    return@launch
                }
                // A remote update invalidates the local send cache, including A → remote B → local A.
                val remoteRevision = model.remoteClipboardRevision
                if (remoteRevision != observedRemoteRevision) {
                    observedRemoteRevision = remoteRevision
                    lastSentText = null
                }
                // Server-originated clipboard updates are never echoed or saved as local cuts.
                val fromRemote = snapshot.text == model.lastRemoteClipboardText
                val newlyObserved = snapshot.text != lastObservedText
                lastObservedText = snapshot.text
                if (newlyObserved && !fromRemote && !snapshot.sensitive) {
                    withContext(Dispatchers.IO) { history.record(snapshot.text, sensitive = false) }
                    refreshHistory()
                }
                // Revoke stale snapshots and re-check the current opt-in after suspended IO.
                if (requireFocus && remoteRevision != model.remoteClipboardRevision) return@launch
                if (send && foreground && (!requireFocus || (listening && autoEnabled))) {
                    val automaticDuplicate = requireFocus && (fromRemote || snapshot.text == lastSentText)
                    if (!automaticDuplicate) sendText(snapshot.text, notify)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (notify) message(R.string.clipboard_unavailable)
            }
        }
    }

    private fun sendText(text: String, notify: Boolean): Boolean {
        val sent = canSend && model.sendClipboardText(text)
        if (sent) lastSentText = text
        if (notify) message(if (sent) R.string.clipboard_sent else R.string.clipboard_cannot_send)
        return sent
    }

    fun show() {
        if (dialog?.isShowing == true) return
        val context = android.view.ContextThemeWrapper(activity, R.style.ThemeOverlay_RemoteInput)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(12))
        }
        body.addView(style.label(activity.getString(R.string.clipboard_help)).apply {
            setPadding(0, 0, 0, style.dp(12))
        })
        masterSwitch = MaterialCheckBox(context).apply {
            text = activity.getString(R.string.clipboard_master)
            minimumHeight = style.dp(48)
            tag = "clipboard_master"
            setOnCheckedChangeListener { _, checked ->
                if (!settingControls) prefs.edit { putBoolean("clipboard_sync", checked) }
            }
        }.also { body.addView(it) }
        autoSwitch = MaterialCheckBox(context).apply {
            text = activity.getString(R.string.clipboard_auto_send)
            minimumHeight = style.dp(48)
            tag = "clipboard_auto_send"
            setOnCheckedChangeListener { _, checked ->
                if (!settingControls) {
                    prefs.edit { putBoolean("clipboard_auto_send", checked) }
                }
            }
        }.also { body.addView(it) }
        body.addView(style.label(activity.getString(R.string.clipboard_auto_send_summary)).apply {
            setPadding(0, 0, 0, style.dp(12))
        })
        stateLabel = style.label("").also { body.addView(it) }
        sendCurrentButton = style.button(activity.getString(R.string.clipboard_send_current)).apply {
            tag = "clipboard_send_current"
            setOnClickListener { sendCurrentClipboard() }
        }.also { body.addView(it, LinearLayout.LayoutParams(-1, style.dp(48))) }
        val heading = LinearLayout(context)
        heading.addView(style.label(activity.getString(R.string.clipboard_recent), emphasized = true),
                LinearLayout.LayoutParams(0, style.dp(48), 1f))
        heading.addView(style.button(activity.getString(R.string.clipboard_clear)).apply {
            tag = "clipboard_clear"
            setOnClickListener {
                MaterialAlertDialogBuilder(context).setTitle(R.string.clipboard_clear_confirm)
                        .setPositiveButton(R.string.clipboard_clear) { _, _ -> historyAction { history.clear() } }
                        .setNegativeButton(android.R.string.cancel, null).show()
            }
        }, LinearLayout.LayoutParams(-2, style.dp(48)))
        body.addView(heading)
        entriesHost = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.also { body.addView(it) }
        val scroll = ScrollView(context).apply { addView(body) }
        dialog = MaterialAlertDialogBuilder(context)
                .setBackground(style.background())
                .setTitle(R.string.clipboard_title)
                .setView(scroll)
                .setNegativeButton(R.string.remote_input_done, null)
                .create().also { shown ->
                    shown.setOnDismissListener {
                        dialogDecor?.viewTreeObserver?.takeIf { it.isAlive }
                                ?.removeOnWindowFocusChangeListener(dialogFocusListener)
                        dialogDecor = null; dialogFocused = false
                        dialog = null; entriesHost = null; stateLabel = null
                        autoSwitch = null; masterSwitch = null; sendCurrentButton = null
                        sendHistoryButtons.clear()
                        updateListening()
                    }
                    shown.show()
                    dialogDecor = shown.window?.decorView
                    dialogDecor?.viewTreeObserver?.addOnWindowFocusChangeListener(dialogFocusListener)
                    dialogFocused = dialogDecor?.hasWindowFocus() == true
                    updateListening()
                }
        refreshSwitches()
        refreshHistory()
        if (listening) captureCurrent(send = autoEnabled, requireFocus = true)
        else captureCurrent(send = false, requireFocus = false)
    }

    private fun refreshSwitches() {
        settingControls = true
        masterSwitch?.isChecked = masterEnabled
        autoSwitch?.isChecked = autoEnabled
        autoSwitch?.isEnabled = masterEnabled
        settingControls = false
        stateLabel?.setText(if (!canSend) R.string.clipboard_cannot_send else R.string.clipboard_ready)
        sendCurrentButton?.isEnabled = canSend
        sendHistoryButtons.forEach { it.isEnabled = canSend }
    }

    private fun refreshHistory() {
        val host = entriesHost ?: return
        activity.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { runCatching { history.entries() }.getOrDefault(emptyList()) }
            if (entriesHost !== host) return@launch
            host.removeAllViews()
            sendHistoryButtons.clear()
            if (entries.isEmpty()) host.addView(style.label(activity.getString(R.string.clipboard_empty)))
            entries.forEach { entry ->
                val item = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, style.dp(8), 0, style.dp(8))
                }
                item.addView(style.label(entry.text, emphasized = true).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val actions = LinearLayout(activity)
                fun action(label: Int, tagValue: String, click: () -> Unit) {
                    actions.addView(style.button(activity.getString(label)).apply {
                        tag = tagValue; setOnClickListener { click() }
                        if (label == R.string.clipboard_send) {
                            isEnabled = canSend
                            sendHistoryButtons += this
                        }
                    }, LinearLayout.LayoutParams(0, style.dp(48), 1f))
                }
                action(R.string.clipboard_send, "clipboard_send_${entry.id}") { sendText(entry.text, true) }
                action(R.string.clipboard_copy, "clipboard_copy_${entry.id}") {
                    lastObservedText = entry.text
                    activity.lifecycleScope.launch {
                        copyingHistory = true
                        try { if (setClipboardText(activity, entry.text)) message(R.string.msg_copied_to_clipboard) }
                        finally { copyingHistory = false }
                    }
                }
                action(R.string.clipboard_delete, "clipboard_delete_${entry.id}") { historyAction { history.remove(entry.id) } }
                item.addView(actions)
                host.addView(item)
            }
        }
    }

    private fun historyAction(action: () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                captureJob?.cancelAndJoin()
                withContext(Dispatchers.IO) { action() }
                refreshHistory()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { message(R.string.clipboard_unavailable) }
        }
    }

    private fun message(text: Int) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
