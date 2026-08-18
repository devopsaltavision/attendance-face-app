package com.syntaxgenie.hfx05attendance.fingerprint.identification

import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

data class IdentificationCandidate(
    val employeeId: String,
    val score: Double,
    val enrollmentId: String,
    val recordId: String,
    val fingerPosition: FingerPosition,
    val templateSlot: Int,
    val matchedTemplateCount: Int,
)

data class IdentificationScoreReport(
    val templatesSearched: Int,
    val candidates: List<IdentificationCandidate>,
) {
    val bestCandidate: IdentificationCandidate? get() = candidates.firstOrNull()
    val secondBestCandidate: IdentificationCandidate? get() = candidates.getOrNull(1)
    val scoreMargin: Double? get() = bestCandidate?.let { best ->
        secondBestCandidate?.let { second -> best.score - second.score }
    }
}

sealed class IdentificationScoringResult {
    data class Scored(val report: IdentificationScoreReport) : IdentificationScoringResult()
    data object NoTemplates : IdentificationScoringResult()
    data class Error(
        val error: IdentificationError,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
    ) : IdentificationScoringResult()
}

sealed class IdentificationResult {
    data class Identified(
        val employeeId: String,
        val bestScore: Double,
        val secondBestScore: Double?,
        val scoreMargin: Double?,
        val enrollmentId: String,
        val recordId: String,
        val fingerPosition: FingerPosition,
        val templateSlot: Int,
        val templatesSearched: Int,
    ) : IdentificationResult()

    data class Unknown(
        val bestScore: Double?,
        val minimumAcceptedScore: Double,
        val templatesSearched: Int,
    ) : IdentificationResult()

    data class Ambiguous(
        val bestCandidate: IdentificationCandidate,
        val secondBestCandidate: IdentificationCandidate,
        val scoreMargin: Double,
        val requiredMargin: Double,
        val templatesSearched: Int,
    ) : IdentificationResult()

    data object NoTemplates : IdentificationResult()

    data class Error(
        val error: IdentificationError,
        val diagnosticDetails: String? = null,
        val cause: Throwable? = null,
    ) : IdentificationResult()
}
