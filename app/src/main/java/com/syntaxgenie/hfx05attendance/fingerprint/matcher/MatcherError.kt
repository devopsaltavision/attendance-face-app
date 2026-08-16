package com.syntaxgenie.hfx05attendance.fingerprint.matcher

enum class MatcherError(val code: String, val userMessage: String) {
    MATCHER_UNAVAILABLE("MCH-001", "Fingerprint matching is unavailable on this device."),
    INVALID_IMAGE("MCH-002", "The fingerprint image is invalid for template creation."),
    TEMPLATE_EXTRACTION_FAILED("MCH-003", "Fingerprint template creation failed."),
    INVALID_TEMPLATE("MCH-004", "The fingerprint template is invalid."),
    TEMPLATE_SERIALIZATION_FAILED("MCH-005", "Fingerprint template serialization failed."),
    TEMPLATE_DESERIALIZATION_FAILED("MCH-006", "Fingerprint template decoding failed."),
    COMPARISON_FAILED("MCH-101", "Fingerprint comparison failed."),
    INCOMPATIBLE_TEMPLATES("MCH-102", "The fingerprint templates are incompatible."),
    UNKNOWN("MCH-999", "An unexpected fingerprint matcher error occurred."),
}
