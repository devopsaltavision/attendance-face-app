@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.camera

import android.graphics.ImageFormat
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import com.syntaxgenie.hfx05attendance.face.frame.FaceFrameDiagnostics
import com.syntaxgenie.hfx05attendance.face.frame.FaceFrameDiagnosticsCalculator
import kotlin.math.ceil

class FaceCameraDiagnosticManager(
    private val onState: (CameraDiagnosticState) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var camera: Camera? = null
    private var frameCount = 0L
    private var firstFrameNanos = 0L
    private var lastUiNanos = 0L

    fun enumerate(): List<Result<FaceCameraInfo>> {
        val count = runCatching { Camera.getNumberOfCameras() }.getOrElse { return listOf(Result.failure(it)) }
        return (0 until count).map { id ->
            runCatching {
                val metadata = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }
                val temporary = Camera.open(id)
                try {
                    val parameters = temporary.parameters
                    FaceCameraInfo(
                        id, metadata.facing, metadata.orientation,
                        parameters.supportedPreviewSizes.orEmpty().map { "${it.width}x${it.height}" },
                        parameters.supportedPreviewFormats.orEmpty(),
                    )
                } finally {
                    temporary.release()
                }
            }
        }
    }

    @Synchronized
    fun open(cameraId: Int, holder: SurfaceHolder, displayRotation: Int) {
        close()
        var opened: Camera? = null
        try {
            val count = Camera.getNumberOfCameras()
            require(cameraId in 0 until count) { "Camera $cameraId does not exist (detected $count)." }
            val info = Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
            opened = Camera.open(cameraId)
            val parameters = opened.parameters
            val sizes = parameters.supportedPreviewSizes.orEmpty()
            val selectedSize = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                ?: sizes.minByOrNull { size ->
                    val areaDifference = kotlin.math.abs(size.width.toLong() * size.height - 1280L * 720)
                    val aspectDifference = kotlin.math.abs(size.width.toDouble() / size.height - 16.0 / 9.0)
                    areaDifference + (aspectDifference * 1_000_000).toLong()
                }
                ?: error("Camera $cameraId reports no supported preview sizes.")
            val formats = parameters.supportedPreviewFormats.orEmpty()
            val selectedFormat = if (ImageFormat.NV21 in formats) ImageFormat.NV21
                else formats.firstOrNull() ?: error("Camera $cameraId reports no supported preview formats.")
            parameters.setPreviewSize(selectedSize.width, selectedSize.height)
            parameters.previewFormat = selectedFormat
            opened.parameters = parameters
            val displayOrientation = calculateDisplayOrientation(info, displayRotation)
            opened.setDisplayOrientation(displayOrientation)
            opened.setPreviewDisplay(holder)

            val bitsPerPixel = ImageFormat.getBitsPerPixel(selectedFormat).takeIf { it > 0 } ?: 16
            val bufferBytes = ceil(selectedSize.width.toDouble() * selectedSize.height * bitsPerPixel / 8.0).toInt()
            frameCount = 0
            firstFrameNanos = 0
            lastUiNanos = 0
            opened.setPreviewCallbackWithBuffer { data, source ->
                val now = System.nanoTime()
                if (firstFrameNanos == 0L) firstFrameNanos = now
                frameCount++
                if (lastUiNanos == 0L || now - lastUiNanos >= UI_INTERVAL_NANOS) {
                    lastUiNanos = now
                    val seconds = (now - firstFrameNanos) / 1_000_000_000.0
                    val fps = if (seconds > 0.0) frameCount / seconds else 0.0
                    val diagnostics = data?.let {
                        FaceFrameDiagnosticsCalculator.calculateNv21(it, selectedSize.width, selectedSize.height)
                    }
                    onState(CameraDiagnosticState(cameraId, info.facing, info.orientation,
                        selectedSize.width, selectedSize.height, selectedFormat, displayOrientation,
                        frameCount, fps, data?.size ?: 0, diagnostics))
                }
                if (data != null && source === camera) runCatching { source.addCallbackBuffer(data) }
            }
            repeat(3) { opened.addCallbackBuffer(ByteArray(bufferBytes)) }
            camera = opened
            opened.startPreview()
            onState(CameraDiagnosticState(cameraId, info.facing, info.orientation,
                selectedSize.width, selectedSize.height, selectedFormat, displayOrientation, 0, 0.0, 0, null))
        } catch (error: Exception) {
            runCatching { opened?.setPreviewCallbackWithBuffer(null) }
            runCatching { opened?.stopPreview() }
            runCatching { opened?.release() }
            camera = null
            onError("Could not open Camera $cameraId: ${error.message ?: error.javaClass.simpleName}")
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

    private fun calculateDisplayOrientation(info: Camera.CameraInfo, rotation: Int): Int {
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (360 - (info.orientation + degrees) % 360) % 360
        } else {
            (info.orientation - degrees + 360) % 360
        }
    }

    companion object { private const val UI_INTERVAL_NANOS = 500_000_000L }
}

data class CameraDiagnosticState(
    val id: Int, val facing: Int, val sensorOrientation: Int,
    val width: Int, val height: Int, val format: Int, val displayOrientation: Int,
    val frameCount: Long, val fps: Double, val lastFrameBytes: Int,
    val diagnostics: FaceFrameDiagnostics?,
)
