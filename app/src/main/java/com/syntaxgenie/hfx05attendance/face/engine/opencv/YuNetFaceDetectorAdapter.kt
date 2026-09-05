package com.syntaxgenie.hfx05attendance.face.engine.opencv

import android.util.Log
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
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.Rect
import org.opencv.objdetect.FaceDetectorYN

/** YuNet adapter. All OpenCV types deliberately stop at this engine package boundary. */
class YuNetFaceDetectorAdapter(modelPath: String) : FaceDetector {
    private val detector = FaceDetectorYN.create(modelPath, "", Size(
        OpenCvEngineConfiguration.DETECTOR_WIDTH.toDouble(), OpenCvEngineConfiguration.DETECTOR_HEIGHT.toDouble()),
        OpenCvEngineConfiguration.SCORE_THRESHOLD, OpenCvEngineConfiguration.NMS_THRESHOLD, OpenCvEngineConfiguration.TOP_K,
    )

    private val instanceId = System.identityHashCode(this)

    init {
        Log.d("YuNet", "YUNET_CREATE thread=${Thread.currentThread().name} instance=$instanceId native=${System.identityHashCode(detector)}")
    }

    override suspend fun detect(input: FaceDetectionInput): FaceDetectionOutcome {
        val started = System.nanoTime()
        Log.d("YuNet", "YUNET_DETECT_ENTER thread=${Thread.currentThread().name} instance=$instanceId camera=${input.frame.cameraId}")
        return try {
            val bgr = OpenCvFrameConverter.nv21ToBgr(input.frame)
            val rotated = rotate(bgr, input.rotationDegrees)
            bgr.release()
            val transform = YuNetLetterboxTransform(rotated.cols(), rotated.rows())
            val resized = Mat()
            org.opencv.imgproc.Imgproc.resize(rotated, resized, Size(transform.resizedWidth.toDouble(), transform.resizedHeight.toDouble()))
            val detectorInput = Mat.zeros(transform.targetHeight, transform.targetWidth, resized.type())
            val roi = detectorInput.submat(Rect(transform.padX, transform.padY, transform.resizedWidth, transform.resizedHeight))
            resized.copyTo(roi); roi.release(); resized.release()
            val output = Mat()
            detector.detect(detectorInput, output)
            Log.d("YuNet", "YUNET_DETECT_RETURN thread=${Thread.currentThread().name} instance=$instanceId rows=${output.rows()}")
            val faces = (0 until output.rows()).map { row -> mapFace(output, row, transform, input) }
            output.release(); detectorInput.release(); rotated.release()
            val latency = System.nanoTime() - started
            if (faces.isEmpty()) FaceDetectionOutcome.NoFace(latency) else FaceDetectionOutcome.Detected(faces, latency)
        } catch (error: Exception) {
            FaceDetectionOutcome.Error("YUNET_ERROR", error.message ?: error.javaClass.simpleName, true, System.nanoTime() - started)
        }
    }


    private fun rotate(source: Mat, rotation: Int): Mat = Mat().also { target ->
        when (rotation) {
            0 -> source.copyTo(target)
            90 -> Core.rotate(source, target, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(source, target, Core.ROTATE_180)
            270 -> Core.rotate(source, target, Core.ROTATE_90_COUNTERCLOCKWISE)
        }
    }

    private fun mapFace(output: Mat, row: Int, transform: YuNetLetterboxTransform, input: FaceDetectionInput): DetectedFace {
        val values = FloatArray(15); output.get(row, 0, values)
        fun raw(point: FacePoint): FacePoint {
            val source = transform.detectorToSource(point)
            val x = source.x; val y = source.y
            val original = when (input.rotationDegrees) {
                0 -> FacePoint(x, y)
                90 -> FacePoint(input.frame.width - y, x)
                180 -> FacePoint(input.frame.width - x, input.frame.height - y)
                else -> FacePoint(y, input.frame.height - x)
            }
            return FacePoint(original.x.coerceIn(0f, input.frame.width.toFloat()), original.y.coerceIn(0f, input.frame.height.toFloat()))
        }
        val corners = listOf(raw(FacePoint(values[0], values[1])), raw(FacePoint(values[0] + values[2], values[1])),
            raw(FacePoint(values[0], values[1] + values[3])), raw(FacePoint(values[0] + values[2], values[1] + values[3])))
        val kinds = listOf(FaceLandmarkType.RIGHT_EYE, FaceLandmarkType.LEFT_EYE, FaceLandmarkType.NOSE_BASE,
            FaceLandmarkType.MOUTH_RIGHT, FaceLandmarkType.MOUTH_LEFT)
        return DetectedFace(
            FaceRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y }),
            kinds.mapIndexed { index, type -> FaceLandmark(type, raw(FacePoint(values[4 + index * 2], values[5 + index * 2]))) },
            FacePose(null, null, null), FaceQuality(confidence = values[14]), null, input.frame.timestampNanos, input.frame.cameraId,
        )
    }

    // OpenCV 4.10's generated Java wrapper exposes no explicit native release for FaceDetectorYN.
    // Per-frame Mats are released above; this long-lived detector is eligible for wrapper finalization at session end.
    override fun close() {
        Log.d("YuNet", "YUNET_CLOSE thread=${Thread.currentThread().name} instance=$instanceId")
    }
}
