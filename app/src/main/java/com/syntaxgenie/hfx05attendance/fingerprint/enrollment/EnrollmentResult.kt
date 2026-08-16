package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherResult
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult

sealed class EnrollmentLowerLayerError {
    data class Scanner(val value: ScannerResult.Error) : EnrollmentLowerLayerError()
    data class Matcher(val value: MatcherResult.Error) : EnrollmentLowerLayerError()
    data class Repository(val value: RepositoryResult.Error) : EnrollmentLowerLayerError()
}

sealed class EnrollmentResult<out T> {
    data class Success<T>(val value: T) : EnrollmentResult<T>()

    data class Error(
        val error: EnrollmentError,
        val session: EnrollmentSession? = null,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
        val lowerLayerError: EnrollmentLowerLayerError? = null,
    ) : EnrollmentResult<Nothing>()
}
