package com.syntaxgenie.hfx05attendance.face.engine.opencv

import android.util.Log
import com.syntaxgenie.hfx05attendance.face.detection.FaceLandmarkType
import com.syntaxgenie.hfx05attendance.face.feature.*
import org.opencv.core.*
import org.opencv.objdetect.FaceRecognizerSF

/** SFace alignment/feature adapter. OpenCV types do not cross this package boundary. */
class SFaceFeatureExtractorAdapter(modelPath: String) : FaceFeatureExtractor {
    private val recognizer = FaceRecognizerSF.create(modelPath, "")
    private val instanceId = System.identityHashCode(this)

    init {
        Log.d("SFace", "SFACE_CREATE thread=${Thread.currentThread().name} instance=$instanceId native=${System.identityHashCode(recognizer)}")
    }

    /** Production extraction: face-row coordinates address the rotated source Mat given to alignCrop. */
    override suspend fun extract(input: FaceFeatureExtractionInput): FaceFeatureExtractionOutcome = extractInternal(input, referenceGeometry = true)

    /** DEBUG comparison only: preserves the former presentation-coordinate mapping for measurement. */
    suspend fun extractLegacy(input: FaceFeatureExtractionInput): FaceFeatureExtractionOutcome = extractInternal(input, referenceGeometry = false)

    private suspend fun extractInternal(input: FaceFeatureExtractionInput, referenceGeometry: Boolean): FaceFeatureExtractionOutcome {
        val started = System.nanoTime()
        val invalid = SFaceAlignmentMapper.validate(input.detectedFace, input.frame.width, input.frame.height)
        if (invalid != null) return FaceFeatureExtractionOutcome.InvalidFace(invalid)
        var image: Mat? = null; var oriented: Mat? = null; var faceRow: Mat? = null; var aligned: Mat? = null; var feature: Mat? = null
        return try {
            image = OpenCvFrameConverter.nv21ToBgr(input.frame)
            oriented = rotate(image!!, input.rotationDegrees)
            faceRow = Mat(1, 15, CvType.CV_32FC1)
            val values = if (referenceGeometry) {
                SFaceAlignmentMapper.toDetectorImageFaceRowValues(input.detectedFace, input.frame.width, input.frame.height, input.rotationDegrees)
            } else {
                SFaceAlignmentMapper.toOpenCvFaceRowValues(input.detectedFace, input.frame.width, input.frame.height, input.rotationDegrees, input.mirrorHorizontally)
            }
            faceRow.put(0, 0, values)
            aligned = Mat(); recognizer.alignCrop(oriented, faceRow, aligned)
            if (aligned!!.empty()) return FaceFeatureExtractionOutcome.AlignmentFailed("alignCrop returned empty", System.nanoTime() - started)
            feature = Mat(); recognizer.feature(aligned, feature)
            val bytes = SFaceFeatureCodec.encode(feature!!)
            FaceFeatureExtractionOutcome.Success(FaceFeature(FaceFeatureMetadata(SFaceModelConfiguration.ENGINE_ID, SFaceModelConfiguration.MODEL_ID, SFaceModelConfiguration.MODEL_VERSION, SFaceModelConfiguration.TEMPLATE_FORMAT_VERSION), bytes), System.nanoTime() - started)
        } catch (e: Exception) {
            FaceFeatureExtractionOutcome.ExtractionFailed(e.message ?: e.javaClass.simpleName, System.nanoTime() - started)
        } finally { feature?.release(); aligned?.release(); faceRow?.release(); oriented?.release(); image?.release() }
    }

    private fun rotate(source: Mat, degrees: Int): Mat = Mat().also { target -> when (degrees) {
        0 -> source.copyTo(target); 90 -> Core.rotate(source, target, Core.ROTATE_90_CLOCKWISE); 180 -> Core.rotate(source, target, Core.ROTATE_180); 270 -> Core.rotate(source, target, Core.ROTATE_90_COUNTERCLOCKWISE)
    } }
    override fun close() {
        Log.d("SFace", "SFACE_CLOSE thread=${Thread.currentThread().name} instance=$instanceId")
    }
}
