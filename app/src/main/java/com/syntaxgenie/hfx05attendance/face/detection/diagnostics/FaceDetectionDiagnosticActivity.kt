@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.detection.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCamera
import com.syntaxgenie.hfx05attendance.face.camera.FaceDetectionCameraMetrics
import com.syntaxgenie.hfx05attendance.face.detection.DetectedFace
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionInput
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionOutcome
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionPerformance
import com.syntaxgenie.hfx05attendance.face.detection.FaceDetectionRunner
import com.syntaxgenie.hfx05attendance.face.engine.mlkit.MlKitFaceDetectorAdapter
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.util.Locale

class FaceDetectionDiagnosticActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var camera: FaceDetectionCamera
    private lateinit var preview: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var start0: Button
    private lateinit var start1: Button
    private lateinit var stop: Button
    private var runner: FaceDetectionRunner? = null
    private var surfaceReady = false
    private var pendingCameraId: Int? = null
    private var latestCameraMetrics: FaceDetectionCameraMetrics? = null
    private var latestOutcome: FaceDetectionOutcome? = null
    private var latestPerformance: FaceDetectionPerformance? = null
    private var heapBaselineBytes = 0L
    private var maximumHeapGrowthBytes = 0L

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cameraId = pendingCameraId
        pendingCameraId = null
        if (granted && cameraId != null) startDetection(cameraId)
        else showError("Camera permission denied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_detection_diagnostic)
        KioskWindowInsets.apply(this, findViewById(R.id.faceDetectionRoot))
        findViewById<MaterialToolbar>(R.id.faceDetectionToolbar).setNavigationOnClickListener { finish() }
        preview = findViewById(R.id.faceDetectionPreview)
        statusText = findViewById(R.id.faceDetectionStatusText)
        start0 = findViewById(R.id.startDetectionCamera0Button)
        start1 = findViewById(R.id.startDetectionCamera1Button)
        stop = findViewById(R.id.stopDetectionButton)
        preview.holder.addCallback(this)
        camera = FaceDetectionCamera(
            onDetectionFrame = { frame, rotation, mirrored ->
                runner?.submit(FaceDetectionInput(frame, rotation, mirrored))
            },
            onMetrics = { metrics -> runOnUiThread {
                latestCameraMetrics = metrics
                updateHeapGrowth()
                render()
            } },
            onError = { message -> runOnUiThread { showError(message) } },
        )
        start0.setOnClickListener { requestStart(0) }
        start1.setOnClickListener { requestStart(1) }
        stop.setOnClickListener { stopDetection("Detection stopped; camera and detector released.") }
    }

    private fun requestStart(cameraId: Int) {
        if (!surfaceReady) return showError("Preview surface is not ready.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startDetection(cameraId)
        } else {
            pendingCameraId = cameraId
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startDetection(cameraId: Int) {
        stopDetection(null)
        latestCameraMetrics = null
        latestOutcome = null
        latestPerformance = null
        heapBaselineBytes = usedHeapBytes()
        maximumHeapGrowthBytes = 0
        runner = FaceDetectionRunner(MlKitFaceDetectorAdapter()) { _, outcome, performance ->
            runOnUiThread {
                latestOutcome = outcome
                latestPerformance = performance
                Log.i("MLKIT_DIAGNOSTIC", "outcome=${outcome::class.simpleName} latencyMs=${outcome.latencyNanos / 1_000_000.0} faces=${(outcome as? FaceDetectionOutcome.Detected)?.faces?.size ?: 0} details=${(outcome as? FaceDetectionOutcome.Detected)?.faces?.joinToString { "box=${it.boundingBox} landmarks=${it.landmarks.size} tracking=${it.trackingId}" } ?: ""} detectionFps=${performance.detectionFramesPerSecond}")
                updateHeapGrowth()
                render()
            }
        }
        camera.open(cameraId, preview.holder, windowManager.defaultDisplay.rotation)
        val opened = camera.isOpen()
        stop.isEnabled = opened
        start0.isEnabled = !opened
        start1.isEnabled = !opened
        if (opened) statusText.text = "Camera $cameraId started; waiting for detector results..."
    }

    private fun render() {
        val metrics = latestCameraMetrics
        val outcome = latestOutcome
        val performance = latestPerformance
        statusText.text = buildString {
            appendLine(when (outcome) {
                is FaceDetectionOutcome.Detected -> "FACE DETECTED"
                is FaceDetectionOutcome.NoFace -> "NO FACE"
                is FaceDetectionOutcome.Error -> "DETECTOR ERROR"
                null -> "WAITING FOR DETECTOR"
            })
            if (metrics != null) {
                appendLine("Camera: ${metrics.cameraId}")
                appendLine("Camera FPS: ${format(metrics.framesPerSecond)}")
                appendLine("Frame bytes: ${metrics.frame.byteCount}")
                appendLine("Rotation/mirrored: ${metrics.rotationDegrees} / ${metrics.mirrorHorizontally}")
                appendLine("Y avg/min/max: ${format(metrics.frame.diagnostics.averageY)} / " +
                    "${metrics.frame.diagnostics.minimumY} / ${metrics.frame.diagnostics.maximumY}")
                appendLine("Y variation: ${metrics.frame.diagnostics.hasLumaVariation}")
            }
            if (performance != null) {
                appendLine("Detection FPS: ${format(performance.detectionFramesPerSecond)}")
                appendLine("Latency avg/p95/max: ${format(performance.averageLatencyMillis)} / " +
                    "${format(performance.p95LatencyMillis)} / ${format(performance.maximumLatencyMillis)} ms")
                appendLine("Dropped/replaced requests: ${performance.replacedPendingFrames}")
            }
            when (outcome) {
                is FaceDetectionOutcome.Detected -> {
                    appendLine("Face count: ${outcome.faces.size}")
                    outcome.faces.forEachIndexed { index, face -> appendFace(index, face) }
                }
                is FaceDetectionOutcome.Error -> appendLine("${outcome.code}: ${outcome.message}")
                else -> Unit
            }
            appendLine("Heap growth/current: ${formatBytes(maximumHeapGrowthBytes)} / " +
                formatBytes((usedHeapBytes() - heapBaselineBytes).coerceAtLeast(0)))
            append("Confidence/blur/quality: unavailable from temporary ML Kit adapter")
        }
    }

    private fun StringBuilder.appendFace(index: Int, face: DetectedFace) {
        appendLine("Face ${index + 1} box: ${face.boundingBox.left.toInt()},${face.boundingBox.top.toInt()} " +
            "${face.boundingBox.right.toInt()},${face.boundingBox.bottom.toInt()}")
        appendLine("Face ${index + 1} size: ${face.boundingBox.width.toInt()}x${face.boundingBox.height.toInt()}")
        appendLine("Face ${index + 1} yaw/pitch/roll: ${format(face.pose.yawDegrees)} / " +
            "${format(face.pose.pitchDegrees)} / ${format(face.pose.rollDegrees)}")
        appendLine("Face ${index + 1} landmarks/tracking: ${face.landmarks.size} / ${face.trackingId ?: "n/a"}")
    }

    private fun stopDetection(message: String?) {
        camera.close()
        runner?.close()
        runner = null
        stop.isEnabled = false
        start0.isEnabled = true
        start1.isEnabled = true
        if (message != null) statusText.text = message
    }

    private fun showError(message: String) {
        stopDetection(null)
        statusText.text = message
    }

    private fun updateHeapGrowth() {
        maximumHeapGrowthBytes = maxOf(maximumHeapGrowthBytes, usedHeapBytes() - heapBaselineBytes)
    }

    private fun usedHeapBytes(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }
    private fun format(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun format(value: Float?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"
    private fun formatBytes(value: Long): String = String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopDetection("Preview closed; detection released.") }
    override fun onPause() { stopDetection("Detection released while diagnostics are paused."); super.onPause() }
    override fun onDestroy() { stopDetection(null); preview.holder.removeCallback(this); super.onDestroy() }
}
