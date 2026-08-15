package com.syntaxgenie.hfx05attendance.fingerprint.scanner

enum class ScannerError(val code: String, val userMessage: String) {
    HARDWARE_UNAVAILABLE("SCN-001", "Fingerprint scanner hardware is unavailable."),
    DEVICE_ACCESS_FAILED("SCN-002", "Fingerprint scanner access failed."),
    POWER_CONTROL_FAILED("SCN-101", "Fingerprint scanner power control failed."),
    RESET_FAILED("SCN-102", "Fingerprint scanner reset failed."),
    SENSOR_NOT_DETECTED("SCN-103", "Fingerprint sensor was not detected."),
    SENSOR_INITIALIZATION_FAILED("SCN-104", "Fingerprint scanner initialization failed."),
    SPI_COMMUNICATION_FAILED("SCN-105", "Fingerprint scanner communication failed."),
    CAPTURE_FAILED("SCN-201", "Fingerprint capture failed. Please try again."),
    CAPTURE_TIMEOUT("SCN-202", "Fingerprint capture timed out. Please try again."),
    INVALID_IMAGE("SCN-204", "The fingerprint scanner returned an invalid image."),
    CLEANUP_FAILED("SCN-205", "Fingerprint scanner cleanup failed."),
    UNKNOWN("SCN-999", "An unexpected fingerprint scanner error occurred."),
}
