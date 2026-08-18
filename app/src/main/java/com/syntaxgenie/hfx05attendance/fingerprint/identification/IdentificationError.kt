package com.syntaxgenie.hfx05attendance.fingerprint.identification

enum class IdentificationError(val code: String, val userMessage: String) {
    POLICY_NOT_CONFIGURED("IDN-001", "Fingerprint identification policy is not configured."),
    TEMPLATE_CACHE_UNAVAILABLE("IDN-002", "Enrolled fingerprint templates are unavailable."),
    PROBE_TEMPLATE_FAILED("IDN-003", "The scanned fingerprint could not be processed."),
    CANDIDATE_COMPARISON_FAILED("IDN-004", "Fingerprint comparison could not be completed."),
    IDENTIFICATION_BUSY("IDN-005", "Fingerprint identification is already in progress."),
    UNKNOWN("IDN-999", "An unexpected fingerprint identification error occurred."),
}
