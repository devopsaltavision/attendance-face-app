package com.syntaxgenie.hfx05attendance.backend

enum class BackendApiError(val userMessage: String, val retryable: Boolean = false) {
    CONFIGURATION_REQUIRED("Device configuration required"),
    NETWORK_UNAVAILABLE("Network unavailable", true),
    NETWORK_FAILURE("Could not connect to the attendance server", true),
    UNAUTHORIZED("Attendance API credential is invalid or unavailable"),
    DEVICE_NOT_FOUND("Attendance device is not provisioned"),
    DEVICE_DISABLED("This attendance device is disabled"),
    INVALID_SYNC_CURSOR("The user sync cursor is no longer valid"),
    SERVER_FAILURE("Attendance server is temporarily unavailable", true),
    INVALID_RESPONSE("Attendance server returned an invalid response"),
    UNKNOWN("Unexpected user synchronization error"),
}
