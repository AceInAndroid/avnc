/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.gaurav.avnc.R
import com.gaurav.avnc.model.RemoteDisplay
import com.gaurav.avnc.model.RemoteDisplayLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Local layout sketch only: never reads pixels or sends pointer events to the VNC server. */
internal class DisplayLayoutDiagram(context: android.content.Context) : View(context) {
    private val style = ControlStyle(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var layout = RemoteDisplayLayout()
    private var selected: String? = null
    private val area = RectF()
    private var scale = 1f
    var editable = false
    var onSelect: ((String) -> Unit)? = null
    var onResize: ((RemoteDisplay) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var initial: RemoteDisplay? = null
    private var edge = 0

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.mac_display_diagram)
        background = style.background(R.color.viewer_key_secondary, 16)
    }

    fun setLayout(value: RemoteDisplayLayout, selectedId: String?) {
        layout = value
        selected = selectedId
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (layout.framebufferWidth <= 0 || layout.framebufferHeight <= 0) return
        val padding = style.dp(20).toFloat()
        scale = minOf((width - padding * 2) / layout.framebufferWidth,
                (height - padding * 2) / layout.framebufferHeight).coerceAtLeast(.0001f)
        val w = layout.framebufferWidth * scale
        val h = layout.framebufferHeight * scale
        area.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.color = style.secondary; paint.style = Paint.Style.STROKE; paint.strokeWidth = style.dp(1).toFloat()
        canvas.drawRect(area, paint)
        layout.displays.forEachIndexed { i, display ->
            val bounds = toCanvas(display.bounds)
            paint.style = Paint.Style.FILL
            paint.color = if (display.id == selected) style.color(R.color.viewer_selected) else style.color(R.color.viewer_key)
            canvas.drawRoundRect(bounds, style.dp(4).toFloat(), style.dp(4).toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = style.dp(if (display.id == selected) 3 else 1).toFloat()
            paint.color = if (display.id == selected) style.blue else style.secondary
            canvas.drawRoundRect(bounds, style.dp(4).toFloat(), style.dp(4).toFloat(), paint)
            paint.style = Paint.Style.FILL
            paint.color = style.ink; paint.textSize = style.dp(14).toFloat(); paint.textAlign = Paint.Align.CENTER
            canvas.save(); canvas.clipRect(bounds)
            canvas.drawText((i + 1).toString(), bounds.centerX(), bounds.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            canvas.restore()
            if (editable && display.id == selected) {
                paint.color = style.blue
                listOf(bounds.left to bounds.centerY(), bounds.right to bounds.centerY(),
                        bounds.centerX() to bounds.top, bounds.centerX() to bounds.bottom).forEach { (x, y) -> canvas.drawCircle(x, y, style.dp(5).toFloat(), paint) }
            }
        }
    }

    private fun toCanvas(bounds: RectF) = RectF(area.left + bounds.left * scale, area.top + bounds.top * scale,
            area.left + bounds.right * scale, area.top + bounds.bottom * scale)

    override fun performClick(): Boolean { super.performClick(); return true }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                initial = null
                val current = layout.displays.firstOrNull { it.id == selected }
                if (editable && current != null) {
                    val b = toCanvas(current.bounds)
                    val tolerance = style.dp(16)
                    val near = RectF(b).apply { inset(-tolerance.toFloat(), -tolerance.toFloat()) }
                    if (near.contains(event.x, event.y)) {
                        val distances = listOf(abs(event.x - b.left), abs(event.x - b.right), abs(event.y - b.top), abs(event.y - b.bottom))
                        edge = if (distances.min() < tolerance) distances.indexOf(distances.min()) + 1 else 0
                        initial = current
                    }
                }
                if (initial == null) {
                    layout.displays.asReversed().firstOrNull { toCanvas(it.bounds).contains(event.x, event.y) }?.let {
                        selected = it.id; initial = it; edge = 0; onSelect?.invoke(it.id); invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (editable && event.pointerCount == 1) {
                val d = initial ?: return true
                val dx = ((event.x - downX) / scale).roundToInt()
                val dy = ((event.y - downY) / scale).roundToInt()
                val maxW = layout.framebufferWidth
                val maxH = layout.framebufferHeight
                val updated = when (edge) {
                    1 -> { val x = (d.x + dx).coerceIn(0, d.x + d.width - 1); d.copy(x = x, width = d.x + d.width - x) }
                    2 -> d.copy(width = (d.width + dx).coerceIn(1, maxW - d.x))
                    3 -> { val y = (d.y + dy).coerceIn(0, d.y + d.height - 1); d.copy(y = y, height = d.y + d.height - y) }
                    4 -> d.copy(height = (d.height + dy).coerceIn(1, maxH - d.y))
                    else -> d.copy(x = (d.x + dx).coerceIn(0, maxW - d.width), y = (d.y + dy).coerceIn(0, maxH - d.height))
                }
                onResize?.invoke(updated)
                return true
            }
            MotionEvent.ACTION_UP -> { initial = null; parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { initial = null; parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return true
    }
}

internal class ManualDisplayEditor(private val activity: VncActivity, private val width: Int,
                                   private val height: Int, original: RemoteDisplayLayout) {
    private val style = ControlStyle(activity)
    private val context = ContextThemeWrapper(activity, R.style.ThemeOverlay_RemoteInput)
    private val regions = original.displays.filter { it.fits(width, height) }.toMutableList().apply {
        if (isEmpty()) add(RemoteDisplay("manual-1", activity.getString(R.string.mac_display_name, 1), 0, 0, width, height))
    }
    private var selected = regions.first().id
    private val diagram = DisplayLayoutDiagram(activity).apply { editable = true }
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: AlertDialog? = null
    private var coordinateDialog: AlertDialog? = null
    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = dismiss()
    }
    private val pipObserver = androidx.lifecycle.Observer<Boolean> { if (it) dismiss() }
    private val connectionObserver = androidx.lifecycle.Observer<com.gaurav.avnc.viewmodel.VncViewModel.State> {
        if (it == com.gaurav.avnc.viewmodel.VncViewModel.State.Disconnected) dismiss()
    }
    private fun layout() = RemoteDisplayLayout(width, height, regions.toList(), "manual")

    fun show() {
        activity.releaseInputControls()
        activity.lifecycle.addObserver(lifecycle)
        activity.viewModel.inPiPMode.observe(activity, pipObserver)
        activity.viewModel.state.observe(activity, connectionObserver)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(18), 0, style.dp(18), style.dp(8))
        }
        content.addView(style.label(activity.getString(R.string.mac_display_manual_hint), 13f))
        content.addView(diagram, LinearLayout.LayoutParams(-1, style.dp(200)).apply { topMargin = style.dp(12) })
        fun buttonRow(first: Pair<Int, () -> Unit>, second: Pair<Int, () -> Unit>) {
            content.addView(LinearLayout(activity).apply {
                listOf(first, second).forEach { (label, run) ->
                    addView(style.button(activity.getString(label)).apply {
                        minimumHeight = style.dp(48); setOnClickListener { run() }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
        }
        buttonRow(R.string.mac_display_horizontal to { split(true) }, R.string.mac_display_vertical to { split(false) })
        buttonRow(R.string.mac_display_add to {
            if (regions.size < 64) {
                val id = "manual-${java.util.UUID.randomUUID()}"
                regions += RemoteDisplay(id, activity.getString(R.string.mac_display_name, regions.size + 1), 0, 0, width, height)
                selected = id; refresh()
            }
        }, R.string.mac_display_delete to {
            if (regions.size > 1) { regions.removeAll { it.id == selected }; selected = regions.first().id; refresh() }
        })
        content.addView(list)
        diagram.onSelect = { selected = it; refresh() }
        diagram.onResize = { changed ->
            val index = regions.indexOfFirst { it.id == changed.id }
            if (index >= 0) { regions[index] = changed; diagram.setLayout(layout(), selected) }
        }
        val result = MaterialAlertDialogBuilder(context).setTitle(R.string.mac_display_manual)
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton(R.string.mac_cancel, null).setPositiveButton(R.string.mac_save, null)
                .setBackground(ControlStyle(context).background()).create()
        dialog = result
        result.setOnDismissListener {
            coordinateDialog?.dismiss()
            activity.lifecycle.removeObserver(lifecycle)
            activity.viewModel.inPiPMode.removeObserver(pipObserver)
            activity.viewModel.state.removeObserver(connectionObserver)
        }
        result.show()
        result.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = layout()
            if (value.isValid()) { activity.saveManualDisplayLayout(value); result.dismiss() }
        }
        refresh()
    }

    fun dismiss() { coordinateDialog?.dismiss(); dialog?.dismiss() }

    private fun split(horizontal: Boolean) {
        val dimension = if (horizontal) width else height
        if (dimension < 2) return
        regions.clear()
        val half = dimension / 2
        regions += RemoteDisplay("manual-1", activity.getString(R.string.mac_display_name, 1), 0, 0, if (horizontal) half else width, if (horizontal) height else half)
        regions += RemoteDisplay("manual-2", activity.getString(R.string.mac_display_name, 2), if (horizontal) half else 0, if (horizontal) 0 else half, if (horizontal) width - half else width, if (horizontal) height else height - half)
        selected = regions.first().id
        refresh()
    }

    private fun refresh() {
        diagram.setLayout(layout(), selected)
        list.removeAllViews()
        regions.forEachIndexed { index, display ->
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(style.button(display.name.ifBlank { activity.getString(R.string.mac_display_name, index + 1) }).apply {
                minimumHeight = style.dp(48)
                style.select(this, display.id == selected)
                setOnClickListener { selected = display.id; refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(style.button(activity.getString(R.string.mac_display_coordinates)).apply {
                minimumHeight = style.dp(48)
                setOnClickListener { selected = display.id; editCoordinates(display); refresh() }
            }, LinearLayout.LayoutParams(-2, -2))
            list.addView(row)
        }
    }

    private fun editCoordinates(display: RemoteDisplay) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(20), 0, style.dp(20), 0)
        }
        val values = listOf(R.string.mac_display_region_name to display.name,
                R.string.mac_display_x to display.x.toString(), R.string.mac_display_y to display.y.toString(),
                R.string.mac_display_width to display.width.toString(), R.string.mac_display_height to display.height.toString())
        val fields = values.mapIndexed { index, (label, value) ->
            content.addView(style.label(activity.getString(label)))
            EditText(context).apply {
                inputType = if (index == 0) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER
                setSingleLine(true); setText(value); minHeight = style.dp(48)
                contentDescription = activity.getString(label)
                content.addView(this)
            }
        }
        val result = MaterialAlertDialogBuilder(context).setTitle(R.string.mac_display_coordinates)
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton(R.string.mac_cancel, null).setPositiveButton(R.string.mac_save, null).create()
        coordinateDialog = result
        result.show()
        result.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val numbers = fields.drop(1).map { it.text.toString().toIntOrNull() }
            val name = fields[0].text.toString().trim()
            if (name.isNotEmpty() && numbers.all { it != null }) {
                val updated = display.copy(name = name, x = numbers[0]!!, y = numbers[1]!!, width = numbers[2]!!, height = numbers[3]!!)
                if (updated.fits(width, height)) {
                    val index = regions.indexOfFirst { it.id == display.id }
                    if (index >= 0) regions[index] = updated
                    refresh(); result.dismiss(); return@setOnClickListener
                }
            }
            fields[0].error = activity.getString(R.string.mac_display_error)
        }
    }
}
