package com.syntaxgenie.hfx05attendance.backend

sealed class BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>()
    data class Error(
        val error: BackendApiError,
        val httpStatus: Int? = null,
        val backendCode: String? = null,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
    ) : BackendResult<Nothing>()
}
