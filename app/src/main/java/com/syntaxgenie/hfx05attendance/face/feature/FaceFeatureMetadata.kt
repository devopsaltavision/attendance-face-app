package com.syntaxgenie.hfx05attendance.face.feature

data class FaceFeatureMetadata(
    val engineId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormatVersion: String,
) {
    init {
        require(engineId.isNotBlank() && modelId.isNotBlank() && modelVersion.isNotBlank() && templateFormatVersion.isNotBlank())
    }
}
