/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.gaurav.avnc.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.gaurav.avnc.R
import com.google.android.material.button.MaterialButton

/** Native shared surfaces for the home, settings, editor and connection-entry screens. */
class GlassStyle(val context: Context) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
    val background get() = ContextCompat.getColor(context, R.color.shell_background)
    val surface get() = ContextCompat.getColor(context, R.color.shell_surface)
    val glass get() = ContextCompat.getColor(context, R.color.shell_glass)
    val ink get() = ContextCompat.getColor(context, R.color.shell_ink)
    val secondary get() = ContextCompat.getColor(context, R.color.shell_secondary)
    val line get() = ContextCompat.getColor(context, R.color.shell_line)
    val blue get() = ContextCompat.getColor(context, R.color.shell_blue)
    val actionFill get() = ContextCompat.getColor(context, R.color.shell_action_fill)
    val selected get() = ContextCompat.getColor(context, R.color.shell_selected)
    val destructive get() = ContextCompat.getColor(context, R.color.shell_destructive)

    fun rounded(color: Int = surface, radius: Int = 24, border: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border) setStroke(dp(1), line)
    }

    fun label(text: CharSequence, size: Float = 16f, emphasized: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(if (emphasized) ink else secondary)
        if (emphasized) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    fun button(text: CharSequence, primary: Boolean = false) = MaterialButton(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48); minimumHeight = dp(48)
        minWidth = 0; minimumWidth = 0
        insetTop = 0; insetBottom = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        cornerRadius = dp(16)
        strokeWidth = 0
        elevation = 0f
        stateListAnimator = null
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(line, if (primary) actionFill else selected))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(secondary, if (primary) android.graphics.Color.WHITE else blue)))
        rippleColor = ColorStateList.valueOf(line)
    }

    fun icon(@DrawableRes drawable: Int, description: CharSequence) = ImageButton(context).apply {
        setImageDrawable(AppCompatResources.getDrawable(context, drawable))
        contentDescription = description
        imageTintList = ColorStateList.valueOf(blue)
        background = RippleDrawable(ColorStateList.valueOf(line), rounded(selected, 24), rounded(surface, 24))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48); minimumHeight = dp(48)
        isFocusable = true
    }

    fun column(padding: Int = 16) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun divider() = View(context).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }
}
