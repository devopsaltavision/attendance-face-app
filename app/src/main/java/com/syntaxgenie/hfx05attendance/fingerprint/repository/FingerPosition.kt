package com.syntaxgenie.hfx05attendance.fingerprint.repository

enum class FingerPosition(val persistedValue: String, val backendApiValue: String) {
    RIGHT_THUMB("right_thumb", "RIGHT_THUMB"),
    RIGHT_INDEX("right_index", "RIGHT_INDEX"),
    RIGHT_MIDDLE("right_middle", "RIGHT_MIDDLE"),
    RIGHT_RING("right_ring", "RIGHT_RING"),
    RIGHT_LITTLE("right_little", "RIGHT_LITTLE"),
    LEFT_THUMB("left_thumb", "LEFT_THUMB"),
    LEFT_INDEX("left_index", "LEFT_INDEX"),
    LEFT_MIDDLE("left_middle", "LEFT_MIDDLE"),
    LEFT_RING("left_ring", "LEFT_RING"),
    LEFT_LITTLE("left_little", "LEFT_LITTLE");

    companion object {
        fun fromPersistedValue(value: String): FingerPosition? =
            entries.firstOrNull { it.persistedValue == value }
    }
}
