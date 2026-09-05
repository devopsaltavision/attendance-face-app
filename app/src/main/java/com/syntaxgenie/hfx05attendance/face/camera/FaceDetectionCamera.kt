@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.camera

import android.graphics.ImageFormat
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame
import kotlin.math.ceil

data class FaceDetectionCameraMetrics(
    val cameraId: Int,
    val framesPerSecond: Double,
    val frameCount: Long,
    val emittedDetectionFrames: Long,
    val frame: FaceCameraFrame,
    val rotationDegrees: Int,
    val mirrorHorizontally: Boolean,
)

/** Single-camera owner used only by the Layer-3 detection diagnostic. */
class FaceDetectionCamera(
    private val onDetectionFrame: (FaceCameraFrame, Int, Boolean) -> Unit,
    private val onMetrics: (FaceDetectionCameraMetrics) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var camera: Camera? = null
    private var frameCount = 0L
    private var emittedFrameCount = 0L
    private var firstFrameNanos = 0L
    private var lastDetectionFrameNanos = 0L
    private var lastMetricsNanos = 0L

    @Synchronized
    fun open(cameraId: Int, holder: SurfaceHolder, displayRotation: Int, orientationOverride: Int? = null) {
        close()
        var opened: Camera? = null
        try {
            require(cameraId in 0 until Camera.getNumberOfCameras()) { "Camera $cameraId does not exist." }
            val info = Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
            opened = Camera.open(cameraId)
            val parameters = opened.parameters
            val size = selectPreviewSize(parameters.supportedPreviewSizes.orEmpty())
            val format = if (ImageFormat.NV21 in parameters.supportedPreviewFormats.orEmpty()) ImageFormat.NV21
                else error("Camera $cameraId does not support NV21.")
            parameters.setPreviewSize(size.width, size.height)
            parameters.previewFormat = format
            opened.parameters = parameters
            // Some HF-X05 modules are physically mounted 180 degrees from their Android metadata.
            val displayOrientation = orientationOverride ?: displayOrientation(info, displayRotation)
            val detectionRotation = orientationOverride ?: detectionRotation(info, displayRotation)
            val mirrored = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
            opened.setDisplayOrientation(displayOrientation)
            opened.setPreviewDisplay(holder)

            frameCount = 0
            emittedFrameCount = 0
            firstFrameNanos = 0
            lastDetectionFrameNanos = 0
            lastMetricsNanos = 0
            val bufferBytes = frameBufferBytes(size.width, size.height, format)
            camera = opened
            opened.setPreviewCallbackWithBuffer { data, source ->
                val now = System.nanoTime()
                if (data != null && source === camera) {
                    if (firstFrameNanos == 0L) firstFrameNanos = now
                    frameCount++
                    if (lastDetectionFrameNanos == 0L || now - lastDetectionFrameNanos >= DETECTION_INTERVAL_NANOS) {
                        lastDetectionFrameNanos = now
                        emittedFrameCount++
                        val frame = FaceCameraFrame.copyFromCameraBuffer(
                            cameraId, size.width, size.height, format, now, info.orientation, info.facing, data,
                        )
                        onDetectionFrame(frame, detectionRotation, mirrored)
                        if (lastMetricsNanos == 0L || now - lastMetricsNanos >= METRICS_INTERVAL_NANOS) {
                            lastMetricsNanos = now
                            onMetrics(FaceDetectionCameraMetrics(
                                cameraId = cameraId,
                                framesPerSecond = fps(now),
                                frameCount = frameCount,
                                emittedDetectionFrames = emittedFrameCount,
                                frame = frame,
                                rotationDegrees = detectionRotation,
                                mirrorHorizontally = mirrored,
                            ))
                        }
                    }
                }
                if (data != null && source === camera) runCatching { source.addCallbackBuffer(data) }
            }
            repeat(CALLBACK_BUFFER_COUNT) { opened.addCallbackBuffer(ByteArray(bufferBytes)) }
            opened.startPreview()
        } catch (error: Exception) {
            runCatching { opened?.setPreviewCallbackWithBuffer(null) }
            runCatching { opened?.stopPreview() }
            runCatching { opened?.release() }
            camera = null
            onError("Could not open detection Camera $cameraId: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun close() {
        val current = camera ?: return
        camera = null
        runCatching { current.setPreviewCallbackWithBuffer(null) }
        runCatching { current.stopPreview() }
        runCatching { current.release() }
    }

    fun isOpen(): Boolean = camera != null

    private fun fps(now: Long): Double {
        val seconds = (now - firstFrameNanos) / 1_000_000_000.0
        return if (seconds > 0) frameCount / seconds else 0.0
    }

    private fun selectPreviewSize(sizes: List<Camera.Size>): Camera.Size =
        sizes.firstOrNull { it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT }
            ?: error("1280x720 is required for the proven HF-X05 detection path.")

    private fun displayOrientation(info: Camera.CameraInfo, rotation: Int): Int {
        val degrees = displayDegrees(rotation)
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + degrees) % 360) % 360
        } else (info.orientation - degrees + 360) % 360
    }

    private fun detectionRotation(info: Camera.CameraInfo, rotation: Int): Int {
        val degrees = displayDegrees(rotation)
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (info.orientation + degrees) % 360
        } else (info.orientation - degrees + 360) % 360
    }

    private fun displayDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun frameBufferBytes(width: Int, height: Int, format: Int): Int {
        val bitsPerPixel = ImageFormat.getBitsPerPixel(format).takeIf { it > 0 } ?: 16
        return ceil(width.toDouble() * height * bitsPerPixel / 8.0).toInt()
    }

    companion object {
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
        private const val CALLBACK_BUFFER_COUNT = 3
        private const val TARGET_DETECTION_FPS = 8
        private const val DETECTION_INTERVAL_NANOS = 1_000_000_000L / TARGET_DETECTION_FPS
        private const val METRICS_INTERVAL_NANOS = 500_000_000L
    }
}
