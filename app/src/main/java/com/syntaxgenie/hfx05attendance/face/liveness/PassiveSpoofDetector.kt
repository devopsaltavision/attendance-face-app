package com.syntaxgenie.hfx05attendance.face.liveness

import android.util.Log
import com.syntaxgenie.hfx05attendance.face.detection.DetectedFace
import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.ArrayDeque

enum class PassiveSpoofMode { OBSERVE_ONLY, ENFORCE }
enum class PassiveSpoofResult { PASS, SUSPICIOUS, INSUFFICIENT_DATA }
enum class PassiveSpoofReason { SCREEN_RECTANGLE, DISPLAY_TEXTURE, GLARE, TEMPORAL_SUSPICION }

data class PassiveSpoofSignals(
    val frameCount: Int,
    val rectangleScore: Double = 0.0,
    val textureScore: Double = 0.0,
    val glareScore: Double = 0.0,
    val temporalScore: Double = 0.0,
) {
    fun reasons(): Set<PassiveSpoofReason> = buildSet {
        if (rectangleScore >= 0.70) add(PassiveSpoofReason.SCREEN_RECTANGLE)
        if (textureScore >= 450.0) add(PassiveSpoofReason.DISPLAY_TEXTURE)
        if (glareScore >= 0.015) add(PassiveSpoofReason.GLARE)
        if (temporalScore >= 0.70) add(PassiveSpoofReason.TEMPORAL_SUSPICION)
    }
}

data class PassiveSpoofAssessment(val result: PassiveSpoofResult, val signals: PassiveSpoofSignals, val reasons: Set<PassiveSpoofReason>)

/** Conservative RGB-only presentation-attack heuristic. It stores only numeric rolling signals. */
class PassiveSpoofDetector {
    private val history = ArrayDeque<PassiveSpoofSignals>()
    private var lastLog = ""

    /** Starts a fresh, in-memory-only burst for each recognition session. */
    fun reset() {
        history.clear()
        lastLog = ""
    }

    fun analyze(frame: FaceCameraFrame, face: DetectedFace): PassiveSpoofAssessment {
        val grayFrame = Mat(frame.height, frame.width, CvType.CV_8UC1)
        grayFrame.put(0, 0, frame.copyData(), 0, frame.width * frame.height)
        return try {
            val faceRect = face.boundingBox
            val padX = (faceRect.width * 0.45f).toInt(); val padY = (faceRect.height * 0.45f).toInt()
            val left = (faceRect.left.toInt() - padX).coerceAtLeast(0); val top = (faceRect.top.toInt() - padY).coerceAtLeast(0)
            val right = (faceRect.right.toInt() + padX).coerceAtMost(grayFrame.cols()); val bottom = (faceRect.bottom.toInt() + padY).coerceAtMost(grayFrame.rows())
            if (right - left < 32 || bottom - top < 32) return assessed(PassiveSpoofSignals(history.size))
            val roi = grayFrame.submat(Rect(left, top, right - left, bottom - top))
            val small = Mat(); Imgproc.resize(roi, small, Size(160.0, 160.0))
            val texture = laplacianVariance(small)
            val bright = threshold(small, 245.0)
            val glare = Core.countNonZero(bright).toDouble() / (small.rows() * small.cols()).toDouble()
            bright.release()
            val rectangle = surroundingRectangleScore(small, faceRect.left - left, faceRect.top - top, faceRect.right - left, faceRect.bottom - top, roi.cols(), roi.rows())
            roi.release(); small.release()
            assessed(PassiveSpoofSignals(history.size + 1, rectangle, texture, glare))
        } finally { grayFrame.release() }
    }

    private fun assessed(signals: PassiveSpoofSignals): PassiveSpoofAssessment {
        history.addLast(signals); while (history.size > WINDOW) history.removeFirst()
        val adjusted = signals.copy(frameCount = history.size, temporalScore = temporalScore())
        val assessment = PassiveSpoofPolicy.decide(adjusted)
        val summary = "result=${assessment.result} rect=${"%.2f".format(adjusted.rectangleScore)} " +
            "texture=${"%.0f".format(adjusted.textureScore)} glare=${"%.3f".format(adjusted.glareScore)} " +
            "temporal=${"%.2f".format(adjusted.temporalScore)} reasons=${assessment.reasons}"
        if (summary != lastLog) { Log.i(TAG, summary); lastLog = summary }
        return assessment
    }

    /**
     * Temporal evidence is only present when a surrounding boundary and a separate
     * display/glare artifact recur together. A repeated rectangle alone cannot become
     * the second signal used for a suspicious decision.
     */
    private fun temporalScore(): Double = history.count {
        it.rectangleScore >= 0.70 && (it.textureScore >= 450.0 || it.glareScore >= 0.015)
    }.toDouble() / WINDOW

    private fun threshold(source: Mat, value: Double): Mat = Mat().also { Imgproc.threshold(source, it, value, 255.0, Imgproc.THRESH_BINARY) }
    private fun laplacianVariance(source: Mat): Double {
        val laplacian = Mat(); Imgproc.Laplacian(source, laplacian, org.opencv.core.CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble(); val deviation = org.opencv.core.MatOfDouble(); Core.meanStdDev(laplacian, mean, deviation)
        val value = deviation.get(0, 0)[0] * deviation.get(0, 0)[0]; laplacian.release(); mean.release(); deviation.release(); return value
    }
    private fun surroundingRectangleScore(gray: Mat, fl: Float, ft: Float, fr: Float, fb: Float, originalWidth: Int, originalHeight: Int): Double {
        val edges = Mat(); Imgproc.Canny(gray, edges, 80.0, 160.0)
        val contours = ArrayList<MatOfPoint>(); val hierarchy = Mat(); Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val scaleX = gray.cols().toFloat() / originalWidth; val scaleY = gray.rows().toFloat() / originalHeight
        val score = contours.mapNotNull { contour ->
            val curve = org.opencv.core.MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(curve, true)
            val polygon = org.opencv.core.MatOfPoint2f(); Imgproc.approxPolyDP(curve, polygon, perimeter * 0.03, true)
            val rect = Imgproc.boundingRect(contour); val contains = rect.x < fl * scaleX && rect.y < ft * scaleY && rect.x + rect.width > fr * scaleX && rect.y + rect.height > fb * scaleY
            val touchesCrop = rect.x <= 1 || rect.y <= 1 || rect.x + rect.width >= gray.cols() - 1 || rect.y + rect.height >= gray.rows() - 1
            val value = if (polygon.total() == 4L && contains && !touchesCrop) (rect.width * rect.height).toDouble() / (gray.cols() * gray.rows()) else null
            curve.release(); polygon.release(); contour.release(); value
        }.maxOrNull() ?: 0.0
        hierarchy.release(); edges.release(); return score.coerceIn(0.0, 1.0)
    }
    companion object { private const val WINDOW = 6; private const val TAG = "PassiveSpoof" }
}

object PassiveSpoofPolicy {
    fun allowsRecognition(mode: PassiveSpoofMode, result: PassiveSpoofResult): Boolean =
        mode == PassiveSpoofMode.OBSERVE_ONLY || result != PassiveSpoofResult.SUSPICIOUS

    fun decide(signals: PassiveSpoofSignals): PassiveSpoofAssessment {
        if (signals.frameCount < 5) return PassiveSpoofAssessment(PassiveSpoofResult.INSUFFICIENT_DATA, signals, emptySet())
        val reasons = signals.reasons(); val suspicious = PassiveSpoofReason.SCREEN_RECTANGLE in reasons &&
            reasons.any { it != PassiveSpoofReason.SCREEN_RECTANGLE }
        return PassiveSpoofAssessment(if (suspicious) PassiveSpoofResult.SUSPICIOUS else PassiveSpoofResult.PASS, signals, reasons)
    }
}
