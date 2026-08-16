package com.syntaxgenie.hfx05attendance.fingerprint.repository

data class BiometricRepositorySnapshot(
    val lastOperation: String? = null,
    val lastRecordCount: Int = 0,
    val lastDurationNanos: Long? = null,
)
