package com.syntaxgenie.hfx05attendance.fingerprint.repository.cache

data class BiometricCacheSnapshot(
    val records: List<CachedBiometricRecord>,
    val lastReloadDurationNanos: Long?,
) {
    val recordCount: Int get() = records.size
}
