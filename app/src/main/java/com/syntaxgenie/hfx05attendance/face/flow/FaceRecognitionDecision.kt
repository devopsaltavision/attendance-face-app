package com.syntaxgenie.hfx05attendance.face.flow

import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionThresholdConfig

enum class FaceRecognitionDecision { MATCHED, UNKNOWN, AMBIGUOUS }

data class FaceRecognitionDecisionResult(
    val decision: FaceRecognitionDecision,
    val employeeId: String?,
    val top: FaceCandidateSeed?,
    val second: FaceCandidateSeed?,
    val configVersion: Int?,
    /** Ranked candidates individually meeting the current threshold; exposed only for AMBIGUOUS manual selection. */
    val ambiguousStrongCandidates: List<FaceCandidateSeed> = emptyList(),
)

/** Pure authorization boundary: only MATCHED can expose an employee id. */
object FaceRecognitionDecisionPolicy {
    fun decide(ranked: List<FaceCandidateSeed>, config: FaceRecognitionThresholdConfig): FaceRecognitionDecisionResult {
        val values = ranked.sortedByDescending { it.score }
        val top = values.firstOrNull()
        val second = values.getOrNull(1)
        val threshold = config.matchThreshold ?: Double.POSITIVE_INFINITY
        val margin = config.minMatchMargin ?: Double.POSITIVE_INFINITY
        val decision = when {
            top == null || top.score < threshold -> FaceRecognitionDecision.UNKNOWN
            second != null && top.score - second.score < margin -> FaceRecognitionDecision.AMBIGUOUS
            else -> FaceRecognitionDecision.MATCHED
        }
        val strongCandidates = if (decision == FaceRecognitionDecision.AMBIGUOUS) values.filter { it.score >= threshold } else emptyList()
        return FaceRecognitionDecisionResult(decision, if (decision == FaceRecognitionDecision.MATCHED) top?.employeeId else null, top, second, config.configVersion, strongCandidates)
    }
}
