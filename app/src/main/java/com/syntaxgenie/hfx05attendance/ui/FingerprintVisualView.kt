package com.syntaxgenie.hfx05attendance.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.ColorRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import com.syntaxgenie.hfx05attendance.R

class FingerprintVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {
    enum class State { READY, SCANNING, SUCCESS, ERROR, WARNING }

    init {
        setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.fingerprint_visual))
        scaleType = ScaleType.FIT_CENTER
        render(State.READY)
    }

    fun render(state: State) {
        animate().cancel()
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        @ColorRes val color = when (state) {
            State.READY -> R.color.attendance_ready_visual
            State.SCANNING -> R.color.attendance_scanning
            State.SUCCESS -> R.color.attendance_success_foreground
            State.ERROR -> R.color.attendance_failure
            State.WARNING -> R.color.attendance_warning
        }
        setColorFilter(ContextCompat.getColor(context, color))
        if (state == State.SCANNING) pulse()
    }

    private fun pulse() {
        animate().alpha(0.55f).scaleX(1.04f).scaleY(1.04f).setDuration(650L)
            .withEndAction {
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650L)
                    .withEndAction(::pulse).start()
            }.start()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }
}
