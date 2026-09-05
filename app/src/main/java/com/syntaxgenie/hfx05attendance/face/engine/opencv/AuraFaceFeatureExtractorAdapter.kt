package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.detection.*
import com.syntaxgenie.hfx05attendance.face.feature.*
import org.opencv.core.*
import org.opencv.calib3d.Calib3d
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc

/** Diagnostic AuraFace adapter. All OpenCV values remain inside this package. */
class AuraFaceFeatureExtractorAdapter(modelPath: String) : FaceFeatureExtractor {
    private val net = Dnn.readNet(modelPath)
    override suspend fun extract(input: FaceFeatureExtractionInput): FaceFeatureExtractionOutcome {
        val started = System.nanoTime()
        val invalid = SFaceAlignmentMapper.validate(input.detectedFace, input.frame.width, input.frame.height)
        if (invalid != null) return FaceFeatureExtractionOutcome.InvalidFace(invalid)
        var image: Mat? = null; var oriented: Mat? = null; var aligned: Mat? = null; var blob: Mat? = null; var output: Mat? = null
        var src: MatOfPoint2f? = null; var dst: MatOfPoint2f? = null; var transform: Mat? = null
        return try {
            image = OpenCvFrameConverter.nv21ToBgr(input.frame)
            oriented = rotate(image!!, input.rotationDegrees)
            val p = SFaceAlignmentMapper.orientedLandmarks(input)
            src = MatOfPoint2f(
                point(p, FaceLandmarkType.LEFT_EYE), point(p, FaceLandmarkType.RIGHT_EYE),
                point(p, FaceLandmarkType.NOSE_BASE), point(p, FaceLandmarkType.MOUTH_LEFT), point(p, FaceLandmarkType.MOUTH_RIGHT))
            dst = MatOfPoint2f(Point(38.2946, 51.6963), Point(73.5318, 51.5014), Point(56.0252, 71.7366), Point(41.5493, 92.3655), Point(70.7299, 92.2041))
            transform = Calib3d.estimateAffinePartial2D(src, dst)
            if (transform!!.empty()) return FaceFeatureExtractionOutcome.AlignmentFailed("alignment transform empty", System.nanoTime() - started)
            aligned = Mat(); Imgproc.warpAffine(oriented, aligned, transform, Size(112.0, 112.0))
            blob = Dnn.blobFromImage(aligned, 1.0 / 127.5, Size(112.0, 112.0), Scalar(127.5, 127.5, 127.5), true, false)
            net.setInput(blob); output = net.forward()
            val values = FloatArray((output!!.total() * output!!.channels()).toInt()); output!!.get(0, 0, values)
            require(values.size == AuraFaceModelConfiguration.EMBEDDING_SIZE)
            var norm = 0.0; values.forEach { norm += it.toDouble() * it }
            norm = kotlin.math.sqrt(norm); require(norm > 0.0)
            values.indices.forEach { values[it] = (values[it] / norm).toFloat() }
            FaceFeatureExtractionOutcome.Success(FaceFeature(FaceFeatureMetadata(AuraFaceModelConfiguration.ENGINE_ID, AuraFaceModelConfiguration.MODEL_ID, AuraFaceModelConfiguration.MODEL_VERSION, AuraFaceModelConfiguration.TEMPLATE_FORMAT_VERSION), AuraFaceFeatureCodec.encode(values)), System.nanoTime() - started)
        } catch (e: Exception) { FaceFeatureExtractionOutcome.ExtractionFailed(e.message ?: e.javaClass.simpleName, System.nanoTime() - started) }
        finally { output?.release(); blob?.release(); aligned?.release(); transform?.release(); src?.release(); dst?.release(); oriented?.release(); image?.release() }
    }
    private fun point(points: Map<FaceLandmarkType, FacePoint>, type: FaceLandmarkType) = Point(points[type]!!.x.toDouble(), points[type]!!.y.toDouble())
    private fun rotate(source: Mat, degrees: Int) = Mat().also { t -> when (degrees) { 0 -> source.copyTo(t); 90 -> Core.rotate(source, t, Core.ROTATE_90_CLOCKWISE); 180 -> Core.rotate(source, t, Core.ROTATE_180); 270 -> Core.rotate(source, t, Core.ROTATE_90_COUNTERCLOCKWISE) } }
    override fun close() { }
}
