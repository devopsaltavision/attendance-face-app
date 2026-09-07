package com.syntaxgenie.hfx05attendance.face.calibration

/**
 * User-selection feedback for calibration only. This deliberately carries no image, template,
 * embedding, or other biometric payload.
 */
data class FaceCalibrationEvent(
    val deviceId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormat: String,
    val topEmployeeId: String,
    val topScore: Double,
    val secondEmployeeId: String?,
    val secondScore: Double?,
    val margin: Double?,
    val selectedEmployeeId: String?,
    val selectionType: FaceCalibrationSelectionType,
    val candidateCount: Int,
    val configVersion: Int?,
)

enum class FaceCalibrationSelectionType {
    TOP_MATCH_ACCEPTED,
    ALTERNATE_SELECTED,
    CANCELLED,
}

enum class FaceRecognitionConfigMode {
    CALIBRATION,
    PRODUCTION,
}

data class FaceRecognitionTelemetryEvent(
    val decision: String, val topEmployeeId: String?, val topScore: Double?,
    val secondEmployeeId: String?, val secondScore: Double?, val margin: Double?,
    val matchThresholdUsed: Double, val minMatchMarginUsed: Double, val configVersion: Int?,
)

object FaceRecognitionTelemetryPolicy { fun shouldWrite(enabled: Boolean) = enabled }

/**
 * A validated remote configuration. Reading/caching it does not activate any face decision.
 */
data class FaceRecognitionThresholdConfig(
    val mode: FaceRecognitionConfigMode,
    val matchThreshold: Double?,
    val minMatchMargin: Double?,
    val accuracyLoggingEnabled: Boolean,
    val candidateMinimumScore: Double?,
    val candidateMaximumGap: Double?,
    val duplicateEnrollmentThreshold: Double?,
    val configVersion: Int?,
)
