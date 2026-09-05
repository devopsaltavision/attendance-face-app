package com.syntaxgenie.hfx05attendance.face.engine.opencv

import com.syntaxgenie.hfx05attendance.face.detection.FacePoint

/** Exact geometry for aspect-preserving YuNet letterbox preprocessing. */
data class YuNetLetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int = OpenCvEngineConfiguration.DETECTOR_WIDTH,
    val targetHeight: Int = OpenCvEngineConfiguration.DETECTOR_HEIGHT,
) {
    val scale: Float = minOf(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
    val resizedWidth: Int = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val resizedHeight: Int = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val padX: Int = (targetWidth - resizedWidth) / 2
    val padY: Int = (targetHeight - resizedHeight) / 2

    fun detectorToSource(point: FacePoint): FacePoint = FacePoint(
        ((point.x - padX) / scale).coerceIn(0f, sourceWidth.toFloat()),
        ((point.y - padY) / scale).coerceIn(0f, sourceHeight.toFloat()),
    )

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}
