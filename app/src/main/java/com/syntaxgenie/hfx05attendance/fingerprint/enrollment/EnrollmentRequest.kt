package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

class EnrollmentRequest(
    employeeId: String,
    val fingerPosition: FingerPosition,
) {
    val employeeId: String = employeeId.trim()

    init {
        require(this.employeeId.isNotBlank()) { "Employee ID must not be blank" }
    }
}
