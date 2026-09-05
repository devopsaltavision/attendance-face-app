package com.syntaxgenie.hfx05attendance.face.flow

/**
 * Central candidate-display policy. Production authorization must supply calibrated absolute and
 * relative requirements; the DEBUG cluster helper below is navigation assistance only.
 */
class FaceCandidateSelectionPolicy {
    data class CalibratedRequirements(
        val minimumScore: Double,
        val maximumGapFromBest: Double,
    )

    fun selectForProduction(
        rankedCandidates: List<FaceCandidate>,
        requirements: CalibratedRequirements,
    ): List<FaceCandidate> {
        val ranked = rankedCandidates.sortedByDescending { it.score }.take(MAX_CANDIDATES)
        val bestScore = ranked.firstOrNull()?.score ?: return emptyList()
        return ranked.filter { candidate ->
            candidate.score >= requirements.minimumScore &&
                bestScore - candidate.score <= requirements.maximumGapFromBest
        }
    }

    /**
     * DEBUG-only display grouping: split at the unique largest adjacent drop among the top three.
     * It always retains the best rank but cannot establish identity because it has no absolute
     * score requirement. Replace with selectForProduction after live calibration is approved.
     */
    fun selectForDebugPreview(rankedCandidates: List<FaceCandidate>): List<FaceCandidate> {
        val ranked = rankedCandidates.sortedByDescending { it.score }.take(MAX_CANDIDATES)
        if (ranked.size < 3) return ranked
        val gaps = ranked.zipWithNext { left, right -> left.score - right.score }
        val largestGap = gaps.maxOrNull() ?: return ranked
        val largestGapIndexes = gaps.indices.filter { index ->
            kotlin.math.abs(gaps[index] - largestGap) <= GAP_TIE_EPSILON
        }
        // No unique cliff means there is no coherent smaller cluster to display.
        return if (largestGapIndexes.size == 1) ranked.take(largestGapIndexes.single() + 1) else ranked
    }

    companion object {
        private const val MAX_CANDIDATES = 3
        private const val GAP_TIE_EPSILON = 0.000001
    }
}
