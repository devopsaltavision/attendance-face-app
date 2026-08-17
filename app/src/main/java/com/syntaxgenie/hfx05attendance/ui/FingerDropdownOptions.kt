package com.syntaxgenie.hfx05attendance.ui

import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

/** Presentation-only ordering for the touch-only registration dropdown. */
object FingerDropdownOptions {
    val positions = listOf(
        FingerPosition.LEFT_THUMB,
        FingerPosition.LEFT_INDEX,
        FingerPosition.LEFT_MIDDLE,
        FingerPosition.LEFT_RING,
        FingerPosition.LEFT_LITTLE,
        FingerPosition.RIGHT_THUMB,
        FingerPosition.RIGHT_INDEX,
        FingerPosition.RIGHT_MIDDLE,
        FingerPosition.RIGHT_RING,
        FingerPosition.RIGHT_LITTLE,
    )

    val default = FingerPosition.RIGHT_INDEX

    fun positionAt(index: Int): FingerPosition = positions[index]
}
