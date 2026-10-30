/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.vnc

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.gaurav.avnc.R
import com.google.android.material.button.MaterialButton

/** Viewer-local styling; never changes the theme or the remote framebuffer. */
internal class ControlStyle(private val context: Context) {
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun color(@ColorRes id: Int) = ContextCompat.getColor(context, id)
    val ink get() = color(R.color.viewer_ink)
    val secondary get() = color(R.color.viewer_secondary)
    val blue get() = color(R.color.viewer_blue)
    fun background(@ColorRes fill: Int = R.color.viewer_glass, radius: Int = 24, border: Boolean = true) =
            GradientDrawable().apply {
                setColor(color(fill))
                if (fill == R.color.viewer_glass) {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(androidx.core.graphics.ColorUtils.compositeColors(color(R.color.viewer_highlight), color(fill)), color(fill))
                }
                cornerRadius = dp(radius).toFloat()
                if (border) setStroke(dp(1), color(R.color.viewer_line))
            }

    fun button(label: String, description: String = label) = MaterialButton(context,
            null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        insetTop = 0; insetBottom = 0
        setPadding(dp(8), 0, dp(8), 0)
        cornerRadius = dp(8)
        strokeWidth = 0
        gravity = Gravity.CENTER
        elevation = 0f
        stateListAnimator = null
        rippleColor = ColorStateList.valueOf(color(R.color.viewer_selected))
        select(this, false)
    }

    fun select(button: MaterialButton, selected: Boolean, key: Boolean = false) {
        button.isSelected = selected
        button.backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(color(R.color.viewer_selected), color(when {
            selected -> R.color.viewer_selected
            key -> R.color.viewer_key
            else -> R.color.viewer_key_secondary
        })))
        button.setTextColor(if (selected) blue else ink)
    }

    fun icon(@DrawableRes drawable: Int, @StringRes description: Int) = ImageButton(context).apply {
        setImageDrawable(AppCompatResources.getDrawable(context, drawable))
        imageTintList = ColorStateList.valueOf(ink)
        contentDescription = context.getString(description)
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.viewer_selected)),
                background(radius = 24, border = false), background(radius = 24, border = false))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48); minimumHeight = dp(48)
        isFocusable = true
        setOnLongClickListener { android.widget.Toast.makeText(context, contentDescription, android.widget.Toast.LENGTH_SHORT).show(); true }
    }

    fun label(text: String, size: Float = 13f, emphasized: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(if (emphasized) ink else secondary)
        if (emphasized) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
}
