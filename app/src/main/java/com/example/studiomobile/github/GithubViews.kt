package com.example.studiomobile.github

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.studiomobile.R
import kotlin.math.roundToInt

internal class GithubViews(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(value: Int): Int = (value * density).roundToInt()

    fun res(id: Int): Int = ContextCompat.getColor(context, id)

    fun text(value: CharSequence, size: Float = 14f, colorRes: Int = R.color.cosmic_ink, bold: Boolean = false): TextView {
        val view = TextView(context)
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        view.setTextColor(res(colorRes))
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        return view
    }

    fun mono(): TextView {
        val view = text("", 12f, R.color.log_text)
        view.typeface = Typeface.MONOSPACE
        view.setTextIsSelectable(true)
        return view
    }

    fun label(value: String): TextView {
        val view = text(value, 13f, R.color.cosmic_muted, true)
        view.setPadding(dp(4), dp(20), 0, dp(6))
        return view
    }

    fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val view = text(label, 15f, if (primary) R.color.cosmic_white else R.color.cosmic_primary_dark, true)
        view.gravity = Gravity.CENTER
        view.setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        view.minHeight = dp(52)
        view.setPadding(dp(20), dp(12), dp(20), dp(12))
        view.isClickable = true
        view.setOnClickListener { onClick() }
        return view
    }

    fun input(hint: String): EditText {
        val view = EditText(context)
        view.hint = hint
        view.setSingleLine(true)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        view.setTextColor(res(R.color.cosmic_ink))
        view.setHintTextColor(res(R.color.cosmic_muted))
        view.setBackgroundResource(R.drawable.bg_input)
        view.setPadding(dp(18), dp(14), dp(18), dp(14))
        return view
    }

    fun card(selected: Boolean = false): LinearLayout {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundResource(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card)
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        return box
    }

    fun box(): LinearLayout {
        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        return box
    }

    fun params(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(width, height)
        params.topMargin = dp(top)
        return params
    }

    fun spinner(): ProgressBar {
        val bar = ProgressBar(context)
        bar.isIndeterminate = true
        bar.indeterminateTintList = ColorStateList.valueOf(res(R.color.cosmic_primary))
        return bar
    }
}
