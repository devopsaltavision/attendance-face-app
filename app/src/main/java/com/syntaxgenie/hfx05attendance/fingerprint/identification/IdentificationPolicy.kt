package com.syntaxgenie.hfx05attendance.fingerprint.identification

fun interface IdentificationPolicy {
    fun evaluate(report: IdentificationScoreReport): IdentificationResult
}

class ScoreThresholdIdentificationPolicy(
    val minimumAcceptedScore: Double,
    val minimumScoreMargin: Double,
) : IdentificationPolicy {
    init {
        require(minimumAcceptedScore.isFinite()) { "Minimum accepted score must be finite" }
        require(minimumScoreMargin.isFinite() && minimumScoreMargin >= 0) {
            "Minimum score margin must be finite and non-negative"
        }
    }

    override fun evaluate(report: IdentificationScoreReport): IdentificationResult {
        val best = report.bestCandidate ?: return IdentificationResult.NoTemplates
        if (best.score < minimumAcceptedScore) {
            return IdentificationResult.Unknown(best.score, minimumAcceptedScore, report.templatesSearched)
        }
        val second = report.secondBestCandidate
        val margin = second?.let { best.score - it.score }
        if (second != null && requireNotNull(margin) < minimumScoreMargin) {
            return IdentificationResult.Ambiguous(
                best,
                second,
                margin,
                minimumScoreMargin,
                report.templatesSearched,
            )
        }
        return IdentificationResult.Identified(
            employeeId = best.employeeId,
            bestScore = best.score,
            secondBestScore = second?.score,
            scoreMargin = margin,
            enrollmentId = best.enrollmentId,
            recordId = best.recordId,
            fingerPosition = best.fingerPosition,
            templateSlot = best.templateSlot,
            templatesSearched = report.templatesSearched,
        )
    }
}

object UnconfiguredIdentificationPolicy : IdentificationPolicy {
    override fun evaluate(report: IdentificationScoreReport) = IdentificationResult.Error(
        IdentificationError.POLICY_NOT_CONFIGURED,
        "Candidate scores are available, but no HF-X05-validated acceptance policy is configured.",
    )
}
