/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.gaurav.avnc.R
import com.gaurav.avnc.ui.common.GlassStyle
import com.gaurav.avnc.ui.home.HomeActivity
import com.gaurav.avnc.viewmodel.VncViewModel

/** Presentation only: all work still belongs to the existing RemoteSession. */
class ConnectionEntry(private val activity: VncActivity, retry: () -> Unit, private val stopRetry: () -> Unit) {
    private val model = activity.viewModel
    private val style = GlassStyle(activity)
    private val title = style.label("", 28f, true).apply {
        id = R.id.client_state
        gravity = Gravity.CENTER
        ViewCompat.setAccessibilityHeading(this, true)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val target = style.label("", 17f, true).apply { gravity = Gravity.CENTER; setPadding(0, style.dp(12), 0, 0) }
    private val address = style.label("", 14f).apply { gravity = Gravity.CENTER; setPadding(0, style.dp(4), 0, 0) }
    private val hint = style.label("", 15f).apply { gravity = Gravity.CENTER; setPadding(0, style.dp(16), 0, style.dp(20)) }
    private val spinner = ProgressBar(activity).apply {
        indeterminateTintList = ColorStateList.valueOf(style.blue)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val computer = ImageView(activity).apply {
        setImageResource(R.drawable.ic_computer)
        imageTintList = ColorStateList.valueOf(style.blue)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val retryButton = style.button(activity.getString(R.string.entry_retry), true).apply {
        id = R.id.reconnect_btn
        tag = "connection_entry_retry"
        setOnClickListener { stopRetry(); retry() }
    }
    private val cancelButton = style.button(activity.getString(R.string.entry_cancel)).apply {
        id = R.id.connection_entry_cancel
        setOnClickListener { stopRetry(); activity.disconnectSession() }
    }
    private val edit = style.button(activity.getString(R.string.entry_edit)).apply {
        id = R.id.connection_entry_edit
        setOnClickListener {
            model.profileLive.value?.let { HomeActivity.editConnection(activity, it.copy()) }
            stopRetry()
            activity.disconnectSession()
        }
    }
    private val home = style.button(activity.getString(R.string.entry_home)).apply {
        id = R.id.connection_entry_home
        setOnClickListener {
            stopRetry()
            HomeActivity.returnToConnections(activity)
            activity.disconnectSession()
        }
    }
    private val details = style.button(activity.getString(R.string.entry_details)).apply {
        id = R.id.connection_entry_details
        setOnClickListener {
            detailsExpanded = !detailsExpanded
            render()
        }
    }
    private var detailsExpanded = false
    private val reason = style.label("", 13f).apply {
        id = R.id.disconnect_reason
        setTextIsSelectable(true)
        setPadding(0, style.dp(8), 0, style.dp(8))
    }
    private val countdown = style.label("", 14f).apply {
        id = R.id.connection_entry_countdown
        gravity = Gravity.CENTER
    }
    private val stop = style.button(activity.getString(R.string.entry_stop_retry)).apply {
        setOnClickListener { stopRetry(); stopped = true; render() }
    }
    private val retryRow = LinearLayout(activity).apply { gravity = Gravity.CENTER }
    private val progress = activity.binding.autoReconnectProgress
    private var seconds: Int? = null
    private var stopped = false

    init {
        val host = activity.binding.statusContainer
        host.removeAllViews()
        val card = style.column(24).apply {
            id = R.id.connection_entry_card
            background = style.rounded(style.surface, 24)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(computer, LinearLayout.LayoutParams(style.dp(64), style.dp(64)).apply { bottomMargin = style.dp(12) })
            addView(spinner, LinearLayout.LayoutParams(style.dp(32), style.dp(32)).apply { bottomMargin = style.dp(12) })
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(target, LinearLayout.LayoutParams(-1, -2))
            addView(address, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
            listOf(retryButton, cancelButton, edit, home, details).forEach {
                addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(8) })
            }
            addView(reason, LinearLayout.LayoutParams(-1, -2))
            retryRow.addView(progress, LinearLayout.LayoutParams(style.dp(32), style.dp(32)))
            retryRow.addView(countdown, LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(8) })
            addView(retryRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(16) })
            addView(stop, LinearLayout.LayoutParams(-1, -2))
        }
        val center = FrameLayout(activity).apply {
            setPadding(style.dp(20), style.dp(24), style.dp(20), style.dp(24))
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val width = minOf((r - l - paddingLeft - paddingRight).coerceAtLeast(1), style.dp(560))
                if (card.layoutParams.width != width) card.layoutParams = card.layoutParams.apply { this.width = width }
            }
        }
        host.addView(NestedScrollView(activity).apply {
            isFillViewport = true
            addView(center, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(-1, -1))
        model.profileLive.observe(activity) { render() }
        model.state.observe(activity) { render() }
        model.disconnectReason.observe(activity) { render() }
        model.awaitingUser.observe(activity) { render() }
        render()
    }

    fun setCountdown(remaining: Int?) {
        seconds = remaining
        countdown.text = remaining?.let { activity.getString(R.string.entry_retry_countdown, it) }
                ?: if (stopped) activity.getString(R.string.entry_retry_stopped) else ""
        retryRow.isVisible = remaining != null || stopped
        progress.isVisible = remaining != null
        stop.isVisible = remaining != null
    }

    fun render() {
        val failed = model.state.value == VncViewModel.State.Disconnected
        val waiting = model.awaitingUser.value == true
        val raw = model.disconnectReason.value.orEmpty()
        title.setText(when {
            failed && raw.isBlank() -> R.string.entry_disconnected
            failed -> R.string.entry_failed
            waiting -> R.string.entry_authenticating
            else -> R.string.entry_connecting
        })
        val profile = model.profileLive.value
        target.text = profile?.name?.ifBlank { profile.host }.orEmpty()
        address.text = profile?.let { (if (it.host.contains(':')) "[${it.host}]" else it.host) + ":${it.port}" }.orEmpty()
        hint.setText(when {
            failed && (raw.contains("auth", true) || raw.contains("password", true)) -> R.string.entry_auth_failed_hint
            failed && (raw.contains("time", true) || raw.contains("unreachable", true)) -> R.string.entry_timeout_hint
            failed -> R.string.entry_failed_hint
            waiting -> R.string.entry_auth_hint
            else -> R.string.entry_connecting_hint
        })
        spinner.isVisible = !failed && !waiting
        computer.isVisible = failed || waiting
        cancelButton.isVisible = !failed
        retryButton.isVisible = failed
        edit.isVisible = failed && profile != null
        home.isVisible = failed
        details.isVisible = failed && raw.isNotBlank()
        reason.text = raw
        reason.isVisible = failed && detailsExpanded && raw.isNotBlank()
        setCountdown(if (failed) seconds else null)
    }
}
