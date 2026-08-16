package com.syntaxgenie.hfx05attendance.employee

data class EmployeeRecord(
    val userId: String,
    val employeeId: String,
    val displayName: String,
    val active: Boolean,
    val fingerprintEnrolled: Boolean,
    val fingerprintEnrollmentId: String?,
    val updatedAt: String,
) {
    init { require(userId.isNotBlank() && employeeId.isNotBlank() && displayName.isNotBlank() && updatedAt.isNotBlank()) }
}
