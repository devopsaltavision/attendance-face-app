package com.syntaxgenie.hfx05attendance.fingerprint.repository

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate

data class BiometricRecord(
    val recordId: String,
    val employeeId: String,
    val fingerPosition: FingerPosition,
    val templateSlot: Int,
    val template: FingerprintTemplate,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(recordId.isNotBlank()) { "Biometric record ID must not be blank" }
        require(employeeId.isNotBlank()) { "Employee ID must not be blank" }
        require(templateSlot in VALID_TEMPLATE_SLOTS) { "Template slot must be between 1 and 5" }
        require(createdAtEpochMillis > 0) { "Created timestamp must be positive" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Updated timestamp must not precede created timestamp"
        }
    }

    companion object {
        val VALID_TEMPLATE_SLOTS: IntRange = 1..5
    }
}
