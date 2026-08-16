package com.syntaxgenie.hfx05attendance.fingerprint.repository

sealed class RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>()

    data class Error(
        val error: RepositoryError,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
    ) : RepositoryResult<Nothing>()
}
