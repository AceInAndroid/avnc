/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.gaurav.avnc.R
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.transfer.SftpUploadEngine
import com.gaurav.avnc.transfer.TrileadUploadRemote
import com.gaurav.avnc.util.getKnownHostsFile
import com.gaurav.avnc.viewmodel.VncViewModel
import com.gaurav.avnc.viewmodel.service.SshClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Owns the SAF launcher and a separate, short-lived SFTP connection. Construct before STARTED. */
class FileTransferControls(private val activity: VncActivity) : DefaultLifecycleObserver {
    private data class Selection(val profile: ServerProfile, val password: String)
    private val style by lazy { ControlStyle(activity) }
    private val context by lazy { ContextThemeWrapper(activity, R.style.ThemeOverlay_RemoteInput) }
    private var configuration: Selection? = null
    private var job: Job? = null
    @Volatile private var ssh: SshClient? = null
    @Volatile private var activeSource: InputStream? = null
    private var cancelled = AtomicBoolean(false)
    private var dialog: AlertDialog? = null
    private var hostDialog: AlertDialog? = null
    @Volatile private var hostResponse: CompletableDeferred<Boolean>? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selected = configuration
        configuration = null
        if (uri != null && selected != null && interactive()) upload(uri, selected)
    }

    init { activity.lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        activity.viewModel.state.observe(activity) {
            if (it == VncViewModel.State.Disconnected) cancel()
        }
        activity.viewModel.activeViewMode.observe(activity) {
            if (it == ServerProfile.VIEW_MODE_NO_INPUT) cancel()
        }
        activity.viewModel.inPiPMode.observe(activity) { if (it) cancel() }
    }

    override fun onDestroy(owner: LifecycleOwner) = cancel()

    private fun interactive() = activity.viewModel.connected &&
            activity.viewModel.activeViewMode.value != ServerProfile.VIEW_MODE_NO_INPUT &&
            activity.viewModel.inPiPMode.value != true && !activity.isFinishing && !activity.isDestroyed

    fun show() {
        if (!interactive()) { notify(R.string.file_transfer_unavailable); return }
        if (job?.isCompleted == false) { notify(R.string.file_transfer_finishing); return }
        activity.releaseInputControls()
        dialog?.dismiss()
        val profile = activity.viewModel.profile
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(20), 0, style.dp(20), style.dp(12))
            addView(style.label(activity.getString(R.string.file_transfer_hint)))
            addView(style.label(activity.getString(R.string.file_transfer_destination), 13f, true))
        }
        fun field(label: Int, value: String, input: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
            hint = activity.getString(label); contentDescription = hint
            inputType = input; setSingleLine(); setText(value); minimumHeight = style.dp(48)
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val host = field(R.string.file_transfer_host, profile.sshHost.ifBlank { profile.host })
        val port = field(R.string.file_transfer_port, profile.sshPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val username = field(R.string.file_transfer_user, profile.sshUsername.ifBlank { profile.username })
        val key = CheckBox(context).apply {
            text = activity.getString(R.string.file_transfer_use_key)
            isChecked = profile.sshPrivateKey.isNotBlank() && profile.sshAuthType == ServerProfile.SSH_AUTH_KEY
            isEnabled = profile.sshPrivateKey.isNotBlank(); minimumHeight = style.dp(48)
            content.addView(this)
        }
        val password = field(if (key.isChecked) R.string.file_transfer_key_password else R.string.file_transfer_password,
                if (key.isChecked) "" else profile.sshPassword,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        key.setOnCheckedChangeListener { _, checked ->
            password.setText("")
            password.hint = activity.getString(if (checked) R.string.file_transfer_key_password else R.string.file_transfer_password)
            password.contentDescription = password.hint
        }
        dialog = MaterialAlertDialogBuilder(context).setBackground(style.background())
                .setTitle(R.string.file_transfer_title)
                .setView(ScrollView(context).apply { addView(content) })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.file_transfer_choose, null).create().apply {
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val hostname = host.text.toString().trim()
                            val number = port.text.toString().toIntOrNull()
                            val user = username.text.toString().trim()
                            if (hostname.isBlank()) { host.error = activity.getString(R.string.file_transfer_required); return@setOnClickListener }
                            if (number == null || number !in 1..65535) { port.error = activity.getString(R.string.file_transfer_invalid_port); return@setOnClickListener }
                            if (user.isBlank()) { username.error = activity.getString(R.string.file_transfer_required); return@setOnClickListener }
                            configuration = Selection(profile.copy(sshHost = hostname, sshPort = number,
                                    sshUsername = user, sshAuthType = if (key.isChecked) ServerProfile.SSH_AUTH_KEY else ServerProfile.SSH_AUTH_PASSWORD,
                                    sshPassword = ""), password.text.toString())
                            dismiss()
                            try { picker.launch(arrayOf("*/*")) }
                            catch (_: android.content.ActivityNotFoundException) { configuration = null; notify(R.string.file_transfer_no_picker) }
                        }
                    }
                    show()
                }
    }

    private fun showProgress(name: String) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(20), 0, style.dp(20), style.dp(16))
            addView(style.label(name, 15f, true))
        }
        status = style.label(activity.getString(R.string.file_transfer_connecting)).also { content.addView(it) }
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = true
            content.addView(this, LinearLayout.LayoutParams(-1, style.dp(24)))
        }
        dialog = MaterialAlertDialogBuilder(context).setBackground(style.background()).setTitle(R.string.file_transfer_title).setView(content)
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
                .setOnCancelListener { cancel() }.create().apply {
                    setCanceledOnTouchOutside(false)
                    show()
                }
    }

    private fun upload(uri: Uri, selection: Selection) {
        if (!interactive() || job?.isCompleted == false) return
        activity.releaseInputControls()
        cancelled = AtomicBoolean(false)
        val token = cancelled
        showProgress(activity.getString(R.string.file_transfer_preparing))
        job = activity.lifecycleScope.launch {
            var client: com.trilead.ssh2.SFTPv3Client? = null
            var connection: SshClient? = null
            var result: String? = null
            var failure: Exception? = null
            val timedOut = AtomicBoolean(false)
            // Independent of the blocking provider/socket call: cancellation closes both
            // resources, so timeout does not depend on the next successful I/O checkpoint.
            val watchdog = CoroutineScope(Dispatchers.Default).launch {
                delay(30 * 60 * 1000L)
                withContext(Dispatchers.Main) {
                    if (cancelled === token && job?.isCompleted == false) {
                        timedOut.set(true)
                        cancel()
                    }
                }
            }
            try {
                result = withContext(Dispatchers.IO) {
                    val resolver = activity.contentResolver
                    var name = "File"
                    var size: Long? = null
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                        }
                    }
                    ensureActive()
                    val observer = object : SshClient.Observer {
                        override fun getKnownSshHostsFile() = getKnownHostsFile(activity)
                        override fun getSshPassword() = selection.password
                        override fun getSshKeyPassword() = selection.password
                        override fun confirmSshHostKeyWithUser(message: String, isNewHost: Boolean): Boolean {
                            if (token.get()) return false
                            val response = CompletableDeferred<Boolean>()
                            hostResponse = response
                            activity.runOnUiThread {
                                if (!interactive() || token.get() || cancelled !== token) response.complete(false)
                                else hostDialog = MaterialAlertDialogBuilder(context).setBackground(style.background())
                                        .setTitle(if (isNewHost) R.string.title_unknown_ssh_host else R.string.title_ssh_host_key_changed)
                                        .setMessage(message)
                                        .setPositiveButton(R.string.title_continue) { _, _ -> response.complete(true) }
                                        .setNegativeButton(android.R.string.cancel) { _, _ -> response.complete(false) }
                                        .setOnCancelListener { response.complete(false) }.create().apply { show() }
                            }
                            return runBlocking { response.await() }
                        }
                    }
                    connection = SshClient(observer)
                    ssh = connection
                    client = connection!!.openFileTransfer(selection.profile)
                    ensureActive()
                    var lastUpdate = 0L
                    val transferContext = coroutineContext
                    resolver.openInputStream(uri)?.also { activeSource = it }?.use { source ->
                        SftpUploadEngine(TrileadUploadRemote(client!!)).upload(name, source, size,
                                checkActive = {
                                    transferContext.ensureActive()
                                    if (token.get()) throw CancellationException()
                                }, progress = { bytes ->
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastUpdate >= 100 || bytes == size) {
                                        lastUpdate = now
                                        activity.runOnUiThread {
                                            if (!token.get() && cancelled === token) {
                                                progress?.isIndeterminate = size == null || size == 0L
                                                if (size != null && size!! > 0) progress?.progress = ((bytes.toDouble() / size!! * 1000).toInt()).coerceIn(0, 1000)
                                                status?.text = activity.getString(R.string.file_transfer_progress,
                                                        android.text.format.Formatter.formatShortFileSize(activity, bytes),
                                                        size?.let { android.text.format.Formatter.formatShortFileSize(activity, it) } ?: "—")
                                            }
                                        }
                                    }
                                })
                    } ?: throw IOException(activity.getString(R.string.file_transfer_cannot_read))
                }
            } catch (_: CancellationException) {
                if (timedOut.get()) failure = IOException(activity.getString(R.string.file_transfer_timeout))
            } catch (error: Exception) {
                failure = error
            } finally {
                watchdog.cancel()
                withContext(NonCancellable) {
                    if (cancelled === token) {
                        hostResponse?.complete(false); hostResponse = null
                        hostDialog?.dismiss(); hostDialog = null
                    }
                    withContext(Dispatchers.IO) {
                        runCatching { activeSource?.close() }
                        runCatching { client?.close() }
                        connection?.close()
                    }
                    // Ownership lasts through transport cleanup. A canceled Job is not complete
                    // until this point; old cleanup must never clear a newer transfer's UI/state.
                    if (cancelled === token) {
                        ssh = null; activeSource = null
                        dialog?.dismiss(); dialog = null
                        job = null
                        if (interactive() && (!token.get() || timedOut.get())) {
                            val error = failure
                            dialog = if (error != null) MaterialAlertDialogBuilder(context).setBackground(style.background())
                                    .setTitle(R.string.file_transfer_failed)
                                    .setMessage(error.message ?: activity.getString(R.string.file_transfer_failed))
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.file_transfer_retry) { _, _ -> upload(uri, selection) }.create().apply { show() }
                            else if (result != null) MaterialAlertDialogBuilder(context).setBackground(style.background()).setTitle(R.string.file_transfer_complete)
                                    .setMessage(activity.getString(R.string.file_transfer_saved, result))
                                    .setPositiveButton(android.R.string.ok, null).create().apply { show() }
                            else null
                        }
                    }
                }
            }
        }
    }

    /** Stops sending; the worker removes its partial file before closing when the connection survives. */
    fun cancel() {
        configuration = null
        cancelled.set(true)
        hostResponse?.complete(false)
        hostDialog?.dismiss(); hostDialog = null
        dialog?.dismiss(); dialog = null
        val activeJob = job
        activeJob?.cancel()
        // Allow a responsive connection to remove its temporary file. A stuck transport must
        // still be closed without blocking the UI; a disconnected server may retain that part.
        val connection = ssh
        val source = activeSource
        val cleanup = CoroutineScope(Dispatchers.IO)
        cleanup.launch { runCatching { source?.close() } }
        cleanup.launch {
            delay(1500)
            if (activeJob?.isCompleted == false) connection?.cancel()
        }
    }

    private fun notify(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
