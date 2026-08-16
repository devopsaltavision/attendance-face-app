package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

data class EnrollmentSummary(
    val employeeId: String,
    val fingerPosition: FingerPosition,
    val templatesStored: Int,
)
