package com.syntaxgenie.hfx05attendance.face.repository

/** Engine/model compatibility metadata. It deliberately contains no template payload. */
data class FaceTemplateMetadata(
    val engineId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormatVersion: String,
    val backupSchemaVersion: Int,
    val qualityScore: Double? = null,
    val enrollmentSampleCount: Int? = null,
) {
    init {
        require(engineId.isNotBlank()) { "Engine ID must not be blank." }
        require(modelId.isNotBlank()) { "Model ID must not be blank." }
        require(modelVersion.isNotBlank()) { "Model version must not be blank." }
        require(templateFormatVersion.isNotBlank()) { "Template format version must not be blank." }
        require(backupSchemaVersion > 0) { "Backup schema version must be positive." }
        require(qualityScore == null || qualityScore.isFinite()) { "Quality score must be finite." }
        require(enrollmentSampleCount == null || enrollmentSampleCount > 0) {
            "Enrollment sample count must be positive when supplied."
        }
    }
}

/** Exact compatibility boundary: templates outside it must not be compared. */
data class FaceTemplateCompatibility(
    val engineId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormatVersion: String,
) {
    init {
        require(engineId.isNotBlank() && modelId.isNotBlank() && modelVersion.isNotBlank() &&
            templateFormatVersion.isNotBlank()) { "Compatibility fields must not be blank." }
    }
}
