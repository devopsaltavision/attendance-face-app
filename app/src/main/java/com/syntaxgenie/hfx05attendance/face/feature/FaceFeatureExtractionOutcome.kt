package com.syntaxgenie.hfx05attendance.face.feature

sealed interface FaceFeatureExtractionOutcome {
    val latencyNanos: Long
    data class Success(val feature: FaceFeature, override val latencyNanos: Long) : FaceFeatureExtractionOutcome
    data class InvalidFace(val reason: String, override val latencyNanos: Long = 0) : FaceFeatureExtractionOutcome
    data class AlignmentFailed(val reason: String, override val latencyNanos: Long) : FaceFeatureExtractionOutcome
    data class ExtractionFailed(val reason: String, override val latencyNanos: Long) : FaceFeatureExtractionOutcome
    data class Error(val code: String, val reason: String, val recoverable: Boolean, override val latencyNanos: Long) : FaceFeatureExtractionOutcome
}
