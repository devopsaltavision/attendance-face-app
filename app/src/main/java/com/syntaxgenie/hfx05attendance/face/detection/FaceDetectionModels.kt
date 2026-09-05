package com.syntaxgenie.hfx05attendance.face.detection

import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame

data class FacePoint(val x: Float, val y: Float)

data class FaceRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class FacePose(val yawDegrees: Float?, val pitchDegrees: Float?, val rollDegrees: Float?)

enum class FaceLandmarkType {
    LEFT_EYE, RIGHT_EYE, NOSE_BASE, MOUTH_LEFT, MOUTH_RIGHT, MOUTH_BOTTOM,
    LEFT_EAR, RIGHT_EAR, LEFT_CHEEK, RIGHT_CHEEK,
}

data class FaceLandmark(val type: FaceLandmarkType, val position: FacePoint)

data class FaceQuality(
    val confidence: Float? = null,
    val blurScore: Float? = null,
    val occlusionScore: Float? = null,
    val notes: Set<String> = emptySet(),
)

data class DetectedFace(
    val boundingBox: FaceRect,
    val landmarks: List<FaceLandmark>,
    val pose: FacePose,
    val quality: FaceQuality?,
    val trackingId: Int?,
    val frameTimestampNanos: Long,
    val sourceCameraId: Int,
)

data class FaceDetectionInput(
    val frame: FaceCameraFrame,
    val rotationDegrees: Int,
    val mirrorHorizontally: Boolean,
    val regionOfInterest: FaceRect? = null,
) {
    init { require(rotationDegrees in setOf(0, 90, 180, 270)) }
}

sealed interface FaceDetectionOutcome {
    val latencyNanos: Long

    data class Detected(val faces: List<DetectedFace>, override val latencyNanos: Long) : FaceDetectionOutcome
    data class NoFace(override val latencyNanos: Long) : FaceDetectionOutcome
    data class Error(
        val code: String,
        val message: String,
        val recoverable: Boolean,
        override val latencyNanos: Long,
    ) : FaceDetectionOutcome
}

