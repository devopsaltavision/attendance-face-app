package com.syntaxgenie.hfx05attendance.face.feature

import com.syntaxgenie.hfx05attendance.face.detection.DetectedFace
import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame

data class FaceFeatureExtractionInput(
    val frame: FaceCameraFrame,
    val detectedFace: DetectedFace,
    val rotationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
) {
    init { require(rotationDegrees in setOf(0, 90, 180, 270)) }
}
