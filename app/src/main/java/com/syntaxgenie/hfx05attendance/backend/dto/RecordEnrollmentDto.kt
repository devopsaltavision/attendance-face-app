package com.syntaxgenie.hfx05attendance.backend.dto

data class EnrollmentTemplateRecordDto(
    val templateRecordId: String,
    val templateSlot: Int,
)

data class RecordEnrollmentRequestDto(
    val enrollmentId: String,
    val deviceId: String,
    val userId: String,
    val employeeId: String,
    val fingerPosition: String,
    val matcherEngine: String,
    val matcherImplementationVersion: String,
    val templateFormat: String,
    val templateFormatVersion: Int,
    val enrolledAtDevice: String,
    val templates: List<EnrollmentTemplateRecordDto>,
)

data class RecordEnrollmentResponseDto(
    val success: Boolean,
    val enrollmentId: String,
    val status: String,
    val serverTimestamp: String? = null,
    val message: String? = null,
)
