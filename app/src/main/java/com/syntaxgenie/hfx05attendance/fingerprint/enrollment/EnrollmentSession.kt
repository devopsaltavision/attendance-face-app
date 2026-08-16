package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

data class EnrollmentSession(
    val enrollmentId: String,
    val employeeId: String,
    val fingerPosition: FingerPosition,
    val requiredCaptures: Int,
    val completedCaptures: Int,
    val state: EnrollmentState,
    val lastError: EnrollmentError? = null,
) {
    init {
        require(enrollmentId.isNotBlank())
        require(requiredCaptures > 0)
        require(completedCaptures in 0..requiredCaptures)
    }

    val nextCaptureNumber: Int
        get() = (completedCaptures + 1).coerceAtMost(requiredCaptures)
}
