package com.syntaxgenie.hfx05attendance.ui

import android.content.Context
import androidx.annotation.StringRes
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

@StringRes
fun FingerPosition.displayNameResource(): Int = when (this) {
    FingerPosition.RIGHT_THUMB -> R.string.finger_right_thumb
    FingerPosition.RIGHT_INDEX -> R.string.finger_right_index
    FingerPosition.RIGHT_MIDDLE -> R.string.finger_right_middle
    FingerPosition.RIGHT_RING -> R.string.finger_right_ring
    FingerPosition.RIGHT_LITTLE -> R.string.finger_right_little
    FingerPosition.LEFT_THUMB -> R.string.finger_left_thumb
    FingerPosition.LEFT_INDEX -> R.string.finger_left_index
    FingerPosition.LEFT_MIDDLE -> R.string.finger_left_middle
    FingerPosition.LEFT_RING -> R.string.finger_left_ring
    FingerPosition.LEFT_LITTLE -> R.string.finger_left_little
}

fun FingerPosition.displayName(context: Context): String = context.getString(displayNameResource())
