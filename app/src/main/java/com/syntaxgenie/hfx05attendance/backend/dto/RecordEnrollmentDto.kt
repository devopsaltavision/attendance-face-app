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

data class FaceEnrollmentTemplateDto(val templateSlot: Int, val templateDataBase64: String)

data class FaceEnrollmentRequestDto(
    val enrollmentId: String,
    val deviceId: String,
    val userId: String,
    val employeeId: String,
    val biometricType: String = "FACE",
    val engineId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormat: String,
    val enrolledAtDevice: String,
    val templates: List<FaceEnrollmentTemplateDto>,
)

data class FaceEnrollmentResponseDto(
    val success: Boolean,
    val enrollmentId: String,
    val userId: String? = null,
    val status: String,
    val biometricType: String? = null,
    val templateCount: Int? = null,
    val serverTimestamp: String? = null,
)

data class FaceEnrollmentRemoteDto(
    val enrollmentId: String,
    val userId: String,
    val employeeId: String,
    val biometricType: String,
    val engineId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormat: String,
    val templates: List<FaceEnrollmentTemplateDto>,
)

data class FaceEnrollmentLookupResponseDto(val enrollment: FaceEnrollmentRemoteDto?)
