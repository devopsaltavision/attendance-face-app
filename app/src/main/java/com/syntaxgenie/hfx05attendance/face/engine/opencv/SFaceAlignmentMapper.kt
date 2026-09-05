package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.detection.*
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeatureExtractionInput

/** Pure geometry for converting detector/original-frame coordinates to the rotated OpenCV image. */
object SFaceAlignmentMapper {
    /** FaceDetectorYN/SFace row: box, right eye, left eye, nose, right mouth, left mouth, score. */
    fun toOpenCvFaceRowValues(face: DetectedFace, width: Int, height: Int, rotationDegrees: Int, mirror: Boolean = false): FloatArray {
        require(validate(face, width, height) == null)
        val box = orientedBox(face.boundingBox, width, height, rotationDegrees, mirror)
        val points = face.landmarks.associate { it.type to orientedPoint(it.position, width, height, rotationDegrees, mirror) }
        val order = listOf(FaceLandmarkType.RIGHT_EYE, FaceLandmarkType.LEFT_EYE, FaceLandmarkType.NOSE_BASE, FaceLandmarkType.MOUTH_RIGHT, FaceLandmarkType.MOUTH_LEFT)
        return FloatArray(15).also { out ->
            out[0] = box.left; out[1] = box.top; out[2] = box.width; out[3] = box.height
            order.forEachIndexed { i, type -> val p = points[type]!!; out[4 + i * 2] = p.x; out[5 + i * 2] = p.y }
            out[14] = face.quality?.confidence ?: 1f
        }
    }
    fun validate(face: DetectedFace, width: Int, height: Int): String? {
        if (face.boundingBox.width <= 1f || face.boundingBox.height <= 1f) return "non-positive face bounds"
        val required = setOf(FaceLandmarkType.LEFT_EYE, FaceLandmarkType.RIGHT_EYE, FaceLandmarkType.NOSE_BASE, FaceLandmarkType.MOUTH_LEFT, FaceLandmarkType.MOUTH_RIGHT)
        if (!required.all { type -> face.landmarks.any { it.type == type } }) return "five landmarks required"
        if (face.landmarks.any { !it.position.x.isFinite() || !it.position.y.isFinite() }) return "non-finite landmark"
        if (face.boundingBox.left < 0f || face.boundingBox.top < 0f || face.boundingBox.right > width || face.boundingBox.bottom > height) return "face bounds outside frame"
        return null
    }

    fun orientedPoint(point: FacePoint, width: Int, height: Int, rotationDegrees: Int, mirror: Boolean = false): FacePoint {
        var p = when (rotationDegrees) {
            0 -> point
            90 -> FacePoint(height - point.y, point.x)
            180 -> FacePoint(width - point.x, height - point.y)
            270 -> FacePoint(point.y, width - point.x)
            else -> error("rotation must be 0/90/180/270")
        }
        val orientedWidth = if (rotationDegrees == 0 || rotationDegrees == 180) width else height
        if (mirror) p = FacePoint(orientedWidth - p.x, p.y)
        return p
    }

    fun orientedBox(face: FaceRect, width: Int, height: Int, rotationDegrees: Int, mirror: Boolean = false): FaceRect {
        val points = listOf(FacePoint(face.left, face.top), FacePoint(face.right, face.top), FacePoint(face.left, face.bottom), FacePoint(face.right, face.bottom))
            .map { orientedPoint(it, width, height, rotationDegrees, mirror) }
        return FaceRect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    fun orientedLandmarks(input: FaceFeatureExtractionInput): Map<FaceLandmarkType, FacePoint> =
        input.detectedFace.landmarks.associate { it.type to orientedPoint(it.position, input.frame.width, input.frame.height, input.rotationDegrees, input.mirrorHorizontally) }

    /**
     * Converts the presentation coordinates emitted by [YuNetFaceDetectorAdapter] back to
     * the coordinates of the rotated, unmirrored Mat that was actually passed to YuNet.
     *
     * FaceScanOverlayView applies this same transform to draw a detected face.  SFace's
     * alignCrop input must instead use it to address the rotated source image itself.
     */
    fun toDetectorImageFaceRowValues(face: DetectedFace, width: Int, height: Int, rotationDegrees: Int): FloatArray {
        require(validate(face, width, height) == null)
        val box = detectorImageBox(face.boundingBox, width, height, rotationDegrees)
        val points = face.landmarks.associate { it.type to detectorImagePoint(it.position, width, height, rotationDegrees) }
        val order = listOf(FaceLandmarkType.RIGHT_EYE, FaceLandmarkType.LEFT_EYE, FaceLandmarkType.NOSE_BASE, FaceLandmarkType.MOUTH_RIGHT, FaceLandmarkType.MOUTH_LEFT)
        return FloatArray(15).also { out ->
            out[0] = box.left; out[1] = box.top; out[2] = box.width; out[3] = box.height
            order.forEachIndexed { i, type -> val p = points[type]!!; out[4 + i * 2] = p.x; out[5 + i * 2] = p.y }
            out[14] = face.quality?.confidence ?: 1f
        }
    }

    private fun detectorImagePoint(point: FacePoint, width: Int, height: Int, rotationDegrees: Int): FacePoint = when (rotationDegrees) {
        0 -> point
        90 -> FacePoint(point.y, width - point.x)
        180 -> FacePoint(width - point.x, height - point.y)
        270 -> FacePoint(height - point.y, point.x)
        else -> error("rotation must be 0/90/180/270")
    }

    private fun detectorImageBox(face: FaceRect, width: Int, height: Int, rotationDegrees: Int): FaceRect {
        val points = listOf(FacePoint(face.left, face.top), FacePoint(face.right, face.top), FacePoint(face.left, face.bottom), FacePoint(face.right, face.bottom))
            .map { detectorImagePoint(it, width, height, rotationDegrees) }
        return FaceRect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
}
