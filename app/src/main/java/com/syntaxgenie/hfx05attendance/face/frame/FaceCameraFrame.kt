package com.syntaxgenie.hfx05attendance.face.frame

/**
 * One camera frame with ownership detached from the Camera callback buffer.
 * Raw bytes are never exposed directly; consumers receive a copy.
 */
class FaceCameraFrame private constructor(
    val cameraId: Int,
    val width: Int,
    val height: Int,
    val format: Int,
    val timestampNanos: Long,
    val sensorOrientation: Int,
    val facing: Int,
    private val ownedData: ByteArray,
    val diagnostics: FaceFrameDiagnostics,
) {
    val byteCount: Int get() = ownedData.size

    fun copyData(): ByteArray = ownedData.copyOf()

    companion object {
        fun copyFromCameraBuffer(
            cameraId: Int,
            width: Int,
            height: Int,
            format: Int,
            timestampNanos: Long,
            sensorOrientation: Int,
            facing: Int,
            callbackData: ByteArray,
        ): FaceCameraFrame {
            val ownedData = callbackData.copyOf()
            return FaceCameraFrame(
                cameraId = cameraId,
                width = width,
                height = height,
                format = format,
                timestampNanos = timestampNanos,
                sensorOrientation = sensorOrientation,
                facing = facing,
                ownedData = ownedData,
                diagnostics = FaceFrameDiagnosticsCalculator.calculateNv21(ownedData, width, height),
            )
        }
    }
}

