package com.syntaxgenie.hfx05attendance.face.calibration

import android.graphics.Bitmap
import android.graphics.ImageFormat
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionInput
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionOutcome
import com.syntaxgenie.hfx05attendance.face.engine.opencv.OpenCvEngineLifecycle
import com.syntaxgenie.hfx05attendance.face.engine.opencv.SFaceFeatureExtractorAdapter
import com.syntaxgenie.hfx05attendance.face.engine.opencv.YuNetFaceDetectorAdapter
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceAggregationMethod
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDebugIdentification
import com.syntaxgenie.hfx05attendance.face.engine.opencv.diagnostics.SFaceDebugRank
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeatureExtractionInput
import com.syntaxgenie.hfx05attendance.face.feature.FaceFeatureExtractionOutcome
import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame
import com.syntaxgenie.hfx05attendance.face.index.FaceTemplateIndexSnapshot
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch

data class StaticImageCalibrationResult(val ranks: List<SFaceDebugRank>)

/** Calibration-only static-image entry point using the same YuNet/SFace adapters as production. */
class StaticImageFaceCalibrationProcessor(private val context: android.content.Context) {
    fun evaluate(bitmap: Bitmap, index: FaceTemplateIndexSnapshot): Result<StaticImageCalibrationResult> = runCatching {
        require(index.employeeCount > 0)
        val frame = bitmap.toNv21Frame()
        val lifecycle = OpenCvEngineLifecycle(context.applicationContext)
        val detector = YuNetFaceDetectorAdapter(lifecycle.prepareYuNetModel().absolutePath)
        val extractor = SFaceFeatureExtractorAdapter(lifecycle.prepareSFaceModel().absolutePath)
        try {
            val detected = await { detector.detect(FaceDetectionInput(frame, 0, false)) }
            val face = (detected as? FaceDetectionOutcome.Detected)?.faces?.singleOrNull()
                ?: throw IllegalArgumentException("Select an image with exactly one face.")
            val extracted = await { extractor.extract(FaceFeatureExtractionInput(frame, face, 0, false)) }
            val feature = (extracted as? FaceFeatureExtractionOutcome.Success)?.feature?.copyPayload()
                ?: throw IllegalArgumentException("Face feature extraction failed.")
            // The current ranking helper aggregates three query samples; repeat this one static query
            // rather than introducing a second matcher or score calculation.
            StaticImageCalibrationResult(SFaceDebugIdentification.rankAll(listOf(feature, feature, feature), index.candidates(), SFaceAggregationMethod.MEAN_3))
        } finally { detector.close(); extractor.close() }
    }

    private fun <T> await(block: suspend () -> T): T {
        val latch = CountDownLatch(1); var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it; latch.countDown() })
        latch.await(); return result!!.getOrThrow()
    }

    private fun Bitmap.toNv21Frame(): FaceCameraFrame {
        val width = width; val height = height; val pixels = IntArray(width * height); getPixels(pixels, 0, width, 0, 0, width, height)
        val nv21 = ByteArray(width * height + width * height / 2); var y = 0; var uv = width * height
        for (row in 0 until height) for (col in 0 until width) {
            val p = pixels[y]; val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            nv21[y++] = ((66*r + 129*g + 25*b + 128 shr 8) + 16).coerceIn(0,255).toByte()
            if (row % 2 == 0 && col % 2 == 0) { nv21[uv++] = ((112*r - 94*g - 18*b + 128 shr 8) + 128).coerceIn(0,255).toByte(); nv21[uv++] = ((-38*r - 74*g + 112*b + 128 shr 8) + 128).coerceIn(0,255).toByte() }
        }
        return FaceCameraFrame.copyFromCameraBuffer(0, width, height, ImageFormat.NV21, System.nanoTime(), 90, 0, nv21)
    }
}

enum class StaticGroundTruth { KNOWN, UNKNOWN, AMBIGUOUS_TEST }
data class StaticCalibrationEvent(val groundTruth: StaticGroundTruth, val trueEmployeeId: String?, val topEmployeeId: String, val topScore: Double, val secondEmployeeId: String?, val secondScore: Double?, val margin: Double?, val correctEmployeeScore: Double?, val classification: String)
