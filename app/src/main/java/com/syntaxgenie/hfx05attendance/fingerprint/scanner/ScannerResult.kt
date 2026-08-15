package com.syntaxgenie.hfx05attendance.fingerprint.scanner

sealed class ScannerResult<out T> {
    data class Success<T>(val value: T) : ScannerResult<T>()

    data class Error(
        val error: ScannerError,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
    ) : ScannerResult<Nothing>()
}
