package com.syntaxgenie.hfx05attendance.fingerprint.repository.cache

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

data class CachedBiometricRecord(
    val recordId: String,
    val employeeId: String,
    val fingerPosition: FingerPosition,
    val templateSlot: Int,
    val template: FingerprintTemplate,
) {
    companion object {
        fun from(record: BiometricRecord) = CachedBiometricRecord(
            record.recordId,
            record.employeeId,
            record.fingerPosition,
            record.templateSlot,
            record.template,
        )
    }
}
