package com.syntaxgenie.hfx05attendance.fingerprint.repository

enum class FingerPosition(val persistedValue: String) {
    RIGHT_THUMB("right_thumb"),
    RIGHT_INDEX("right_index"),
    RIGHT_MIDDLE("right_middle"),
    RIGHT_RING("right_ring"),
    RIGHT_LITTLE("right_little"),
    LEFT_THUMB("left_thumb"),
    LEFT_INDEX("left_index"),
    LEFT_MIDDLE("left_middle"),
    LEFT_RING("left_ring"),
    LEFT_LITTLE("left_little");

    companion object {
        fun fromPersistedValue(value: String): FingerPosition? =
            entries.firstOrNull { it.persistedValue == value }
    }
}
