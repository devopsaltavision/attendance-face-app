package com.syntaxgenie.hfx05attendance.ui

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keeps kiosk content outside status/navigation bars across Android versions. */
object KioskWindowInsets {
    fun apply(activity: Activity, root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val initial = Insets.of(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
            )
            view.setPadding(
                initial.left + bars.left,
                initial.top + bars.top,
                initial.right + bars.right,
                initial.bottom + bars.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
