package com.syntaxgenie.hfx05attendance.fingerprint.matcher

sealed class MatcherResult<out T> {
    data class Success<T>(val value: T) : MatcherResult<T>()

    data class Error(
        val error: MatcherError,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
    ) : MatcherResult<Nothing>()
}
