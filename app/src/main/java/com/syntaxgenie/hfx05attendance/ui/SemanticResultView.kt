package com.syntaxgenie.hfx05attendance.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.syntaxgenie.hfx05attendance.R

/** Small shared kiosk-sized result surface for employee and admin outcomes. */
class SemanticResultView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    enum class Kind { SUCCESS, WARNING, ERROR, INFO }

    private val icon = ImageView(context)
    private val title = TextView(context)
    private val message = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (16 * d).toInt())
        icon.layoutParams = LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply { marginEnd = (14 * d).toInt() }
        addView(icon)
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(title)
            addView(message)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.textSize = 20f
        message.textSize = 16f
        message.setPadding(0, (3 * d).toInt(), 0, 0)
    }

    fun show(kind: Kind, heading: CharSequence, detail: CharSequence? = null) {
        val (background, foreground, image) = when (kind) {
            Kind.SUCCESS -> Triple(R.drawable.semantic_result_success, R.color.attendance_success, R.drawable.ic_check_circle)
            Kind.WARNING -> Triple(R.drawable.semantic_result_warning, R.color.attendance_warning_text, R.drawable.ic_warning)
            Kind.ERROR -> Triple(R.drawable.semantic_result_error, R.color.attendance_failure, R.drawable.ic_error_circle)
            Kind.INFO -> Triple(R.drawable.semantic_result_info, R.color.attendance_scanning, R.drawable.ic_info)
        }
        setBackgroundResource(background)
        icon.setImageResource(image)
        title.text = heading
        title.setTextColor(context.getColor(foreground))
        message.text = detail?.toString().orEmpty()
        message.visibility = if (detail.isNullOrBlank()) GONE else VISIBLE
        message.setTextColor(context.getColor(R.color.attendance_text))
        contentDescription = listOf(heading, detail).filterNotNull().joinToString(". ")
    }
}
