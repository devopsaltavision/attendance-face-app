package com.syntaxgenie.hfx05attendance.backend.dto

data class AttendanceEventDto(
    val attendanceEventId: String,
    val userId: String,
    val employeeId: String,
    val source: String = "FINGERPRINT",
    val deviceTimestamp: String,
    val clientSequence: Long? = null,
    val action: String? = null,
    val biometricType: String? = null,
)

data class RecordAttendanceRequestDto(
    val attendanceEventId: String,
    val deviceId: String,
    val userId: String,
    val employeeId: String,
    val source: String = "FINGERPRINT",
    val deviceTimestamp: String,
    val clientSequence: Long? = null,
    val action: String? = null,
    val biometricType: String? = null,
)

data class RecordAttendanceResponseDto(
    val success: Boolean,
    val attendanceEventId: String,
    val status: String,
    val attendanceRecordId: String?,
    val attendanceAction: String?,
    val serverTimestamp: String? = null,
    val message: String? = null,
)

data class BulkAttendanceRequestDto(
    val deviceId: String,
    val events: List<AttendanceEventDto>,
)

data class BulkAttendanceResultDto(
    val attendanceEventId: String,
    val status: String,
    val attendanceRecordId: String?,
    val attendanceAction: String?,
    val errorCode: String? = null,
    val message: String? = null,
)

data class BulkAttendanceResponseDto(
    val results: List<BulkAttendanceResultDto>,
    val serverTimestamp: String,
)
