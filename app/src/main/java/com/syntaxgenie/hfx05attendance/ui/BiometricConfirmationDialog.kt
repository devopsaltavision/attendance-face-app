package com.syntaxgenie.hfx05attendance.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.syntaxgenie.hfx05attendance.R

/** Shared kiosk-sized Material confirmation surface for biometric save and delete actions. */
object BiometricConfirmationDialog {
    fun show(
        context: Context,
        title: String,
        employeeId: String,
        message: String,
        confirmLabel: String,
        destructive: Boolean = false,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(TextView(context).apply {
                text = "Employee"; textSize = 14f
                setTextColor(context.getColor(R.color.attendance_text_secondary))
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(TextView(context).apply {
                text = employeeId; textSize = 18f
                setTextColor(context.getColor(R.color.attendance_text))
                setPadding(0, 0, 0, dp(20))
            })
            addView(TextView(context).apply {
                text = message; textSize = 16f
                setTextColor(context.getColor(R.color.attendance_text))
            })
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(content)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(body).create()
        var confirmed = false
        val actions = LinearLayout(context).apply {
            gravity = Gravity.END
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        val buttonParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        val cancel = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "CANCEL"
            layoutParams = buttonParams.apply { marginEnd = dp(12) }
            setOnClickListener { dialog.dismiss(); onCancel() }
        }
        val confirm = MaterialButton(context).apply {
            text = confirmLabel
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            val color = context.getColor(if (destructive) R.color.attendance_failure else R.color.attendance_success)
            backgroundTintList = ColorStateList.valueOf(color)
            setTextColor(context.getColor(R.color.attendance_success_foreground))
            setOnClickListener {
                if (!confirmed) { confirmed = true; dialog.dismiss(); onConfirm() }
            }
        }
        actions.addView(cancel)
        actions.addView(confirm)
        body.addView(actions)
        dialog.setOnCancelListener { onCancel() }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }
}
