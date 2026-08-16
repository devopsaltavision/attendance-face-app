package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

enum class EnrollmentError(val code: String, val userMessage: String) {
    ENROLLMENT_NOT_STARTED("ENR-001", "Fingerprint enrollment has not been started."),
    INVALID_EMPLOYEE_ID("ENR-002", "Enter a valid employee ID."),
    INVALID_FINGER_POSITION("ENR-003", "Select a valid finger position."),
    CAPTURE_FAILED("ENR-004", "Fingerprint capture failed. Please try again."),
    TEMPLATE_CREATION_FAILED("ENR-005", "The fingerprint template could not be created."),
    TEMPLATE_STORAGE_FAILED("ENR-006", "The fingerprint enrollment could not be saved."),
    CAPTURE_REJECTED("ENR-007", "The fingerprint image was unusable. Please reposition and try again."),
    ENROLLMENT_INCOMPLETE("ENR-008", "Five accepted fingerprint captures are required."),
    ENROLLMENT_ALREADY_EXISTS("ENR-009", "This employee and finger are already enrolled."),
    ENROLLMENT_CANCELLED("ENR-010", "Fingerprint enrollment was cancelled."),
    ENROLLMENT_ALREADY_COMPLETE("ENR-011", "Fingerprint enrollment is already complete."),
    SESSION_BUSY("ENR-012", "Fingerprint enrollment is already processing an operation."),
    UNKNOWN("ENR-999", "An unexpected fingerprint enrollment error occurred."),
}
