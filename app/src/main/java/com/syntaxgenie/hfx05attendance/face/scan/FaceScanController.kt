package com.syntaxgenie.hfx05attendance.face.scan

import com.syntaxgenie.hfx05attendance.face.detection.DetectedFace
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionInput
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionOutcome
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionRunner
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionPerformance
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetector

/** Engine-independent scan guidance and newest-frame-wins detector orchestration. */
class FaceScanController(
    detector: FaceDetector,
    private val onPresentation: (FaceScanPresentation) -> Unit,
    private val onPerformance: (FaceDetectionPerformance) -> Unit = {},
    private val onDetectionOutcome: (FaceDetectionInput, FaceDetectionOutcome) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val runner = FaceDetectionRunner(detector) { input, outcome, performance ->
        onPerformance(performance)
        onDetectionOutcome(input, outcome)
        onOutcome(input, outcome)
    }

    fun starting() = present(FaceScanState.StartingCamera, null, "Starting camera…")
    fun submit(input: FaceDetectionInput) = runner.submit(input)

    private fun onOutcome(input: FaceDetectionInput, outcome: FaceDetectionOutcome) {
        when (outcome) {
            is FaceDetectionOutcome.Error -> present(FaceScanState.Error(outcome.message), null, "Camera or detector error")
            is FaceDetectionOutcome.NoFace -> {
                val state = if (input.frame.diagnostics.averageY < FaceScanGuidance.MIN_LUMA) FaceScanState.TooDark else FaceScanState.SearchingForFace
                present(state, null, if (state == FaceScanState.TooDark) "Too dark" else "No face detected")
            }
            is FaceDetectionOutcome.Detected -> when {
                outcome.faces.size > 1 -> present(FaceScanState.MultipleFaces, null, "Multiple faces detected")
                // A valid detected face is actionable even on the probable IR/NIR camera;
                // low-light guidance is reserved for frames where no usable face is found.
                else -> guide(input, outcome.faces.single())
            }
        }
    }

    private fun guide(input: FaceDetectionInput, face: DetectedFace) {
        val size = maxOf(face.boundingBox.width / input.frame.width, face.boundingBox.height / input.frame.height)
        val centerX = (face.boundingBox.left + face.boundingBox.right) / 2 / input.frame.width
        val centerY = (face.boundingBox.top + face.boundingBox.bottom) / 2 / input.frame.height
        when {
            size < FaceScanGuidance.MIN_FACE_FRACTION -> present(FaceScanState.FaceTooSmall, face, "Move closer")
            size > FaceScanGuidance.MAX_FACE_FRACTION -> present(FaceScanState.FaceTooLarge, face, "Move back")
            centerX !in FaceScanGuidance.TARGET_LEFT..FaceScanGuidance.TARGET_RIGHT ||
                centerY !in FaceScanGuidance.TARGET_TOP..FaceScanGuidance.TARGET_BOTTOM -> present(FaceScanState.FaceDetected, face, "Center your face")
            else -> present(FaceScanState.HoldStill, face, "Hold still")
        }
    }

    private fun present(state: FaceScanState, face: DetectedFace?, text: String) = onPresentation(FaceScanPresentation(state, face, text))
    override fun close() = runner.close()
}
