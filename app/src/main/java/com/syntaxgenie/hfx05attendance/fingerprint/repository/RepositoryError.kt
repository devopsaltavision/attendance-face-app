package com.syntaxgenie.hfx05attendance.fingerprint.repository

enum class RepositoryError(val code: String, val userMessage: String) {
    REPOSITORY_UNAVAILABLE("BIO-001", "Biometric storage is unavailable."),
    TEMPLATE_SAVE_FAILED("BIO-002", "The fingerprint template could not be saved."),
    TEMPLATE_READ_FAILED("BIO-003", "Stored fingerprint templates could not be read."),
    INVALID_RECORD("BIO-004", "The biometric record is invalid."),
    TEMPLATE_DELETE_FAILED("BIO-005", "The fingerprint template could not be deleted."),
    INCOMPATIBLE_TEMPLATE("BIO-006", "A stored fingerprint template is incompatible with the active matcher."),
    DUPLICATE_TEMPLATE_SLOT("BIO-007", "That fingerprint template slot is already occupied."),
    UNKNOWN("BIO-999", "An unexpected biometric storage error occurred."),
}
