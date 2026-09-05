package com.syntaxgenie.hfx05attendance.face.scan

sealed interface FaceScanState {
    data object Idle : FaceScanState
    data object StartingCamera : FaceScanState
    data object SearchingForFace : FaceScanState
    data object FaceDetected : FaceScanState
    data object FaceTooSmall : FaceScanState
    data object FaceTooLarge : FaceScanState
    data object MultipleFaces : FaceScanState
    data object TooDark : FaceScanState
    data object HoldStill : FaceScanState
    data class Error(val message: String) : FaceScanState
}

data class FaceScanPresentation(
    val state: FaceScanState,
    val face: com.syntaxgenie.hfx05attendance.face.detection.DetectedFace? = null,
    val status: String,
)

object FaceScanGuidance {
    const val MIN_LUMA = 42.0
    const val MIN_FACE_FRACTION = 0.16f
    const val MAX_FACE_FRACTION = 0.65f
    const val TARGET_LEFT = 0.20f
    const val TARGET_TOP = 0.14f
    const val TARGET_RIGHT = 0.80f
    const val TARGET_BOTTOM = 0.86f
}
