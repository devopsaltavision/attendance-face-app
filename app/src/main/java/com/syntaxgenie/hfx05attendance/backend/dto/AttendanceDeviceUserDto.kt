package com.syntaxgenie.hfx05attendance.backend.dto

data class AttendanceDeviceUserDto(
    val userId: String,
    val employeeId: String,
    val displayName: String,
    val active: Boolean,
    val fingerprintEnrolled: Boolean,
    val fingerprintEnrollmentId: String?,
    val faceEnrolled: Boolean = false,
    val faceEnrollmentId: String? = null,
    val updatedAt: String,
)
