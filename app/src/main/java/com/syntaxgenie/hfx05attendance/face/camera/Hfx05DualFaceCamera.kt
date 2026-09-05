@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import com.syntaxgenie.hfx05attendance.face.frame.FaceCameraFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class DualCameraFrameState(
    val cameraId: Int,
    val width: Int,
    val height: Int,
    val format: Int,
    val frameCount: Long,
    val framesPerSecond: Double,
    val frame: FaceCameraFrame,
)

/** Owns both legacy Camera instances for the duration of one dual-camera diagnostic run. */
class Hfx05DualFaceCamera(
    private val onFrameState: (DualCameraFrameState) -> Unit,
    private val onBothCamerasActive: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera0: CameraSlot? = null
    private var camera1: CameraSlot? = null
    private var camera1Started = false

    @Synchronized
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val cameraThread = HandlerThread("hfx05-dual-face-camera").also { it.start() }
        thread = cameraThread
        handler = Handler(cameraThread.looper).also { cameraHandler ->
            cameraHandler.post {
                try {
                    require(Camera.getNumberOfCameras() >= 2) { "Dual test requires Camera 0 and Camera 1." }
                    camera0 = openCamera(0) { firstCamera0Frame() }
                } catch (error: Exception) {
                    failAndRelease("Could not start dual camera test at Camera 0: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
        return true
    }

    fun isRunning(): Boolean = running.get()

    fun stop() {
        val cameraHandler: Handler
        val cameraThread: HandlerThread
        synchronized(this) {
            if (!running.getAndSet(false)) return
            cameraHandler = handler ?: return
            cameraThread = thread ?: return
        }
        val released = CountDownLatch(1)
        cameraHandler.post {
            releaseCameras()
            released.countDown()
        }
        released.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        cameraThread.quitSafely()
        synchronized(this) {
            handler = null
            thread = null
        }
    }

    private fun firstCamera0Frame() {
        if (!running.get() || camera1Started) return
        camera1Started = true
        try {
            camera1 = openCamera(1)
        } catch (error: Exception) {
            failAndRelease("Camera 0 was active, but Camera 1 could not open simultaneously: " +
                (error.message ?: error.javaClass.simpleName))
        }
    }

    private fun openCamera(cameraId: Int, onFirstFrame: (() -> Unit)? = null): CameraSlot {
        val info = Camera.CameraInfo().also { Camera.getCameraInfo(cameraId, it) }
        var opened: Camera? = null
        var texture: SurfaceTexture? = null
        try {
            opened = Camera.open(cameraId)
            val parameters = opened.parameters
            val selectedSize = selectPreviewSize(parameters.supportedPreviewSizes.orEmpty())
            val selectedFormat = if (ImageFormat.NV21 in parameters.supportedPreviewFormats.orEmpty()) {
                ImageFormat.NV21
            } else {
                parameters.supportedPreviewFormats.orEmpty().firstOrNull()
                    ?: error("Camera $cameraId reports no supported preview formats.")
            }
            parameters.setPreviewSize(selectedSize.width, selectedSize.height)
            parameters.previewFormat = selectedFormat
            opened.parameters = parameters
            texture = SurfaceTexture(DUMMY_TEXTURE_BASE + cameraId)
            opened.setPreviewTexture(texture)
            val slot = CameraSlot(cameraId, info, opened, texture, selectedSize.width, selectedSize.height, selectedFormat)
            val bufferBytes = frameBufferBytes(selectedSize.width, selectedSize.height, selectedFormat)
            var firstFrameDelivered = false
            opened.setPreviewCallbackWithBuffer { callbackData, source ->
                if (callbackData != null && running.get() && source === slot.camera) {
                    val now = System.nanoTime()
                    val frame = FaceCameraFrame.copyFromCameraBuffer(
                        cameraId, slot.width, slot.height, slot.format, now,
                        info.orientation, info.facing, callbackData,
                    )
                    slot.recordFrame(now)
                    onFrameState(DualCameraFrameState(
                        cameraId, slot.width, slot.height, slot.format,
                        slot.frameCount, slot.framesPerSecond(now), frame,
                    ))
                    if (!firstFrameDelivered) {
                        firstFrameDelivered = true
                        onFirstFrame?.invoke()
                    }
                }
                if (callbackData != null && running.get() && source === slot.camera) {
                    runCatching { source.addCallbackBuffer(callbackData) }
                }
            }
            repeat(CALLBACK_BUFFER_COUNT) { opened.addCallbackBuffer(ByteArray(bufferBytes)) }
            opened.startPreview()
            if (cameraId == 1) onBothCamerasActive()
            return slot
        } catch (error: Exception) {
            runCatching { opened?.setPreviewCallbackWithBuffer(null) }
            runCatching { opened?.stopPreview() }
            runCatching { opened?.release() }
            runCatching { texture?.release() }
            throw error
        }
    }

    private fun failAndRelease(message: String) {
        running.set(false)
        releaseCameras()
        onError(message)
        thread?.quitSafely()
    }

    private fun releaseCameras() {
        camera1?.release()
        camera1 = null
        camera0?.release()
        camera0 = null
        camera1Started = false
    }

    private fun selectPreviewSize(sizes: List<Camera.Size>): Camera.Size =
        sizes.firstOrNull { it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT }
            ?: sizes.minByOrNull { size ->
                val areaDifference = kotlin.math.abs(size.width.toLong() * size.height - TARGET_WIDTH.toLong() * TARGET_HEIGHT)
                val aspectDifference = kotlin.math.abs(size.width.toDouble() / size.height - 16.0 / 9.0)
                areaDifference + (aspectDifference * 1_000_000).toLong()
            }
            ?: error("Camera reports no supported preview sizes.")

    private fun frameBufferBytes(width: Int, height: Int, format: Int): Int {
        val bitsPerPixel = ImageFormat.getBitsPerPixel(format).takeIf { it > 0 } ?: 16
        return ceil(width.toDouble() * height * bitsPerPixel / 8.0).toInt()
    }

    private class CameraSlot(
        val id: Int,
        val info: Camera.CameraInfo,
        val camera: Camera,
        val texture: SurfaceTexture,
        val width: Int,
        val height: Int,
        val format: Int,
    ) {
        var frameCount = 0L
            private set
        private var firstFrameNanos = 0L

        fun recordFrame(timestampNanos: Long) {
            if (firstFrameNanos == 0L) firstFrameNanos = timestampNanos
            frameCount++
        }

        fun framesPerSecond(timestampNanos: Long): Double {
            val elapsedSeconds = (timestampNanos - firstFrameNanos) / 1_000_000_000.0
            return if (elapsedSeconds > 0.0) frameCount / elapsedSeconds else 0.0
        }

        fun release() {
            runCatching { camera.setPreviewCallbackWithBuffer(null) }
            runCatching { camera.stopPreview() }
            runCatching { camera.release() }
            runCatching { texture.release() }
        }
    }

    companion object {
        private const val TARGET_WIDTH = 1280
        private const val TARGET_HEIGHT = 720
        private const val CALLBACK_BUFFER_COUNT = 3
        private const val DUMMY_TEXTURE_BASE = 20
        private const val RELEASE_TIMEOUT_SECONDS = 2L
    }
}

