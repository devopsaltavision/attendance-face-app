package com.syntaxgenie.hfx05attendance.fingerprint.matcher

data class MatcherComparisonResult(
    val score: Double,
    val matcher: MatcherMetadata,
    val durationNanos: Long,
) {
    init {
        require(score.isFinite()) { "Matcher score must be finite" }
        require(durationNanos >= 0) { "Comparison duration must not be negative" }
    }

    fun meetsProvisionalThreshold(threshold: Double): Boolean {
        require(threshold.isFinite()) { "Threshold must be finite" }
        return score >= threshold
    }
}
