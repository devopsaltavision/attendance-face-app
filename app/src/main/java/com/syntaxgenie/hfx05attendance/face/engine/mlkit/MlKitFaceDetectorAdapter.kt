package com.syntaxgenie.hfx05attendance.face.engine.mlkit

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark as MlKitLandmark
import com.syntaxgenie.hfx05attendance.face.detection.DetectedFace
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionInput
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionOutcome
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetector
import com.syntaxgenie.hfx05attendance.face.detection.FaceLandmark
import com.syntaxgenie.hfx05attendance.face.detection.FaceLandmarkType
import com.syntaxgenie.hfx05attendance.face.detection.FacePoint
import com.syntaxgenie.hfx05attendance.face.detection.FacePose
import com.syntaxgenie.hfx05attendance.face.detection.FaceQuality
import com.syntaxgenie.hfx05attendance.face.detection.FaceRect
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** TEMPORARY DETECTION ADAPTER ONLY. ML Kit is not a recognition or liveness engine. */
class MlKitFaceDetectorAdapter : FaceDetector {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(MINIMUM_FACE_SIZE)
            .build(),
    )

    override suspend fun detect(input: FaceDetectionInput): FaceDetectionOutcome = suspendCoroutine { continuation ->
        val startedNanos = System.nanoTime()
        val frame = input.frame
        val image = InputImage.fromByteArray(
            frame.copyData(), frame.width, frame.height, input.rotationDegrees, InputImage.IMAGE_FORMAT_NV21,
        )
        detector.process(image)
            .addOnSuccessListener { faces ->
                val latency = System.nanoTime() - startedNanos
                val mapped = faces.map { it.toDetectedFace(input) }
                continuation.resume(
                    if (mapped.isEmpty()) FaceDetectionOutcome.NoFace(latency)
                    else FaceDetectionOutcome.Detected(mapped, latency),
                )
            }
            .addOnFailureListener { error ->
                continuation.resume(FaceDetectionOutcome.Error(
                    code = error.javaClass.simpleName,
                    message = error.message ?: "ML Kit face detection failed.",
                    recoverable = true,
                    latencyNanos = System.nanoTime() - startedNanos,
                ))
            }
    }

    override fun close() = detector.close()

    private fun Face.toDetectedFace(input: FaceDetectionInput): DetectedFace {
        val mapper = MlKitCoordinateMapper(
            input.frame.width, input.frame.height, input.rotationDegrees, input.mirrorHorizontally,
        )
        return DetectedFace(
            boundingBox = mapper.mapRect(
                FaceRect(boundingBox.left.toFloat(), boundingBox.top.toFloat(),
                    boundingBox.right.toFloat(), boundingBox.bottom.toFloat()),
            ),
            landmarks = LANDMARK_TYPES.mapNotNull { (mlKitType, commonType) ->
                getLandmark(mlKitType)?.position?.let { point ->
                    FaceLandmark(commonType, mapper.mapPoint(FacePoint(point.x, point.y)))
                }
            },
            pose = FacePose(
                yawDegrees = headEulerAngleY,
                pitchDegrees = headEulerAngleX,
                rollDegrees = headEulerAngleZ,
            ),
            quality = FaceQuality(notes = setOf("ENGINE_CONFIDENCE_UNAVAILABLE")),
            trackingId = trackingId,
            frameTimestampNanos = input.frame.timestampNanos,
            sourceCameraId = input.frame.cameraId,
        )
    }

    companion object {
        private const val MINIMUM_FACE_SIZE = 0.1f
        private val LANDMARK_TYPES = listOf(
            MlKitLandmark.LEFT_EYE to FaceLandmarkType.LEFT_EYE,
            MlKitLandmark.RIGHT_EYE to FaceLandmarkType.RIGHT_EYE,
            MlKitLandmark.NOSE_BASE to FaceLandmarkType.NOSE_BASE,
            MlKitLandmark.MOUTH_LEFT to FaceLandmarkType.MOUTH_LEFT,
            MlKitLandmark.MOUTH_RIGHT to FaceLandmarkType.MOUTH_RIGHT,
            MlKitLandmark.MOUTH_BOTTOM to FaceLandmarkType.MOUTH_BOTTOM,
            MlKitLandmark.LEFT_EAR to FaceLandmarkType.LEFT_EAR,
            MlKitLandmark.RIGHT_EAR to FaceLandmarkType.RIGHT_EAR,
            MlKitLandmark.LEFT_CHEEK to FaceLandmarkType.LEFT_CHEEK,
            MlKitLandmark.RIGHT_CHEEK to FaceLandmarkType.RIGHT_CHEEK,
        )
    }
}

internal class MlKitCoordinateMapper(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val rotationDegrees: Int,
    private val mirrorHorizontally: Boolean,
) {
    fun mapRect(rect: FaceRect): FaceRect {
        val corners = listOf(
            FacePoint(rect.left, rect.top), FacePoint(rect.right, rect.top),
            FacePoint(rect.left, rect.bottom), FacePoint(rect.right, rect.bottom),
        ).map(::mapPoint)
        return FaceRect(
            corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y },
        )
    }

    fun mapPoint(point: FacePoint): FacePoint {
        val raw = when (rotationDegrees) {
            0 -> point
            90 -> FacePoint(point.y, frameHeight - point.x)
            180 -> FacePoint(frameWidth - point.x, frameHeight - point.y)
            270 -> FacePoint(frameWidth - point.y, point.x)
            else -> error("Unsupported rotation: $rotationDegrees")
        }
        val mappedX = if (mirrorHorizontally) frameWidth - raw.x else raw.x
        return FacePoint(
            mappedX.coerceIn(0f, frameWidth.toFloat()),
            raw.y.coerceIn(0f, frameHeight.toFloat()),
        )
    }
}

