@file:Suppress("DEPRECATION")

package com.syntaxgenie.hfx05attendance.face.camera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.R
import com.syntaxgenie.hfx05attendance.face.frame.FaceFrameSynchronizationSnapshot
import com.syntaxgenie.hfx05attendance.face.frame.FaceFrameSynchronizer
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.util.Locale

class FaceCameraDiagnosticActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var manager: FaceCameraDiagnosticManager
    private lateinit var dualCamera: Hfx05DualFaceCamera
    private val synchronizer = FaceFrameSynchronizer()
    private lateinit var detectedText: TextView
    private lateinit var reportText: TextView
    private lateinit var frameText: TextView
    private lateinit var dualText: TextView
    private lateinit var preview: SurfaceView
    private lateinit var open0: Button
    private lateinit var open1: Button
    private lateinit var close: Button
    private lateinit var startDual: Button
    private lateinit var stopDual: Button
    private val dualStateLock = Any()
    private var latestCamera0State: DualCameraFrameState? = null
    private var latestCamera1State: DualCameraFrameState? = null
    private var lastDualUiNanos = 0L
    private var surfaceReady = false
    private var pendingCameraId: Int? = null
    private var pendingEnumeration = false

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (pendingEnumeration) enumerateCameras() else pendingCameraId?.also(::openCamera)
        } else {
            showError("Camera permission denied. Camera inspection and preview are unavailable.")
        }
        pendingCameraId = null
        pendingEnumeration = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_camera_diagnostic)
        KioskWindowInsets.apply(this, findViewById(R.id.faceCameraRoot))
        findViewById<MaterialToolbar>(R.id.faceCameraToolbar).setNavigationOnClickListener { finish() }
        detectedText = findViewById(R.id.detectedCameraText)
        reportText = findViewById(R.id.cameraReportText)
        frameText = findViewById(R.id.frameMetadataText)
        dualText = findViewById(R.id.dualCameraMetadataText)
        preview = findViewById(R.id.cameraPreview)
        open0 = findViewById(R.id.openCamera0Button)
        open1 = findViewById(R.id.openCamera1Button)
        close = findViewById(R.id.closeCameraButton)
        startDual = findViewById(R.id.startDualCameraButton)
        stopDual = findViewById(R.id.stopDualCameraButton)
        preview.holder.addCallback(this)
        manager = FaceCameraDiagnosticManager(
            onState = { state -> runOnUiThread { showState(state) } },
            onError = { message -> runOnUiThread { showError(message) } },
        )
        dualCamera = Hfx05DualFaceCamera(
            onFrameState = ::handleDualFrameState,
            onBothCamerasActive = { runOnUiThread { dualText.text = "Both cameras active; collecting frames..." } },
            onError = { message -> runOnUiThread { showDualError(message) } },
        )
        findViewById<Button>(R.id.enumerateCamerasButton).setOnClickListener { enumerateCameras() }
        open0.setOnClickListener { requestOpen(0) }
        open1.setOnClickListener { requestOpen(1) }
        close.setOnClickListener { closeCamera("Camera closed.") }
        startDual.setOnClickListener { requestStartDual() }
        stopDual.setOnClickListener { stopDualCamera("Dual camera test stopped; both cameras released.") }
        enumerateCameras()
    }

    private fun enumerateCameras() {
        closeAllCameras(null)
        val count = runCatching { Camera.getNumberOfCameras() }.getOrElse {
            detectedText.text = "Camera enumeration failed"
            reportText.text = it.message ?: it.javaClass.simpleName
            updateButtons(0)
            return
        }
        detectedText.text = "Detected cameras: $count"
        updateButtons(count)
        if (count == 0) {
            reportText.text = "No cameras reported by this device."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            reportText.text = "Camera permission is required to inspect supported sizes and formats."
            pendingEnumeration = true
            permissionRequest.launch(Manifest.permission.CAMERA)
            return
        }
        reportText.text = manager.enumerate().mapIndexed { id, result ->
            result.fold({ it.usefulReport() }, { "Camera $id: inspection failed: ${it.message ?: it.javaClass.simpleName}" })
        }.joinToString("\n\n")
    }

    private fun requestOpen(id: Int) {
        if (!surfaceReady) return showError("Preview surface is not ready. Try again.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera(id)
        } else {
            pendingCameraId = id
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera(id: Int) {
        stopDualCamera(null)
        manager.open(id, preview.holder, windowManager.defaultDisplay.rotation)
        close.isEnabled = manager.isOpen()
    }

    private fun showState(state: CameraDiagnosticState) {
        preview.visibility = View.VISIBLE
        close.isEnabled = true
        frameText.text = "Camera ID: ${state.id}\n" +
            "Facing: ${FaceCameraInfo.facingName(state.facing)} (${state.facing})\n" +
            "Sensor orientation: ${state.sensorOrientation} degrees\n" +
            "Display rotation applied: ${state.displayOrientation} degrees\n" +
            "Preview: ${state.width}x${state.height}\n" +
            "Format: ${FaceCameraInfo.formatName(state.format)}\n" +
            "Frame count: ${state.frameCount}\n" +
            "Approx FPS: ${String.format(Locale.US, "%.1f", state.fps)}\n" +
            "Last frame byte length: ${state.lastFrameBytes}" + formatDiagnostics(state.diagnostics)
    }

    private fun requestStartDual() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            showDualError("Camera permission is required for the dual camera test.")
            return
        }
        manager.close()
        close.isEnabled = false
        synchronizer.reset()
        synchronized(dualStateLock) {
            latestCamera0State = null
            latestCamera1State = null
            lastDualUiNanos = 0L
        }
        if (dualCamera.start()) {
            startDual.isEnabled = false
            stopDual.isEnabled = true
            open0.isEnabled = false
            open1.isEnabled = false
            dualText.text = "Opening Camera 0; Camera 1 will open after Camera 0 delivers its first frame..."
        }
    }

    private fun handleDualFrameState(state: DualCameraFrameState) {
        synchronizer.accept(state.frame)
        val shouldRender = synchronized(dualStateLock) {
            if (state.cameraId == 0) latestCamera0State = state else latestCamera1State = state
            val now = System.nanoTime()
            if (lastDualUiNanos == 0L || now - lastDualUiNanos >= DUAL_UI_INTERVAL_NANOS) {
                lastDualUiNanos = now
                true
            } else false
        }
        if (shouldRender) runOnUiThread { renderDualState() }
    }

    private fun renderDualState() {
        if (!dualCamera.isRunning()) return
        val (camera0State, camera1State) = synchronized(dualStateLock) {
            latestCamera0State to latestCamera1State
        }
        val pairing = synchronizer.snapshot()
        dualText.text = buildString {
            appendCameraState("CAMERA 0", camera0State)
            appendLine()
            appendCameraState("CAMERA 1", camera1State)
            appendLine()
            appendPairing(pairing)
        }
    }

    private fun StringBuilder.appendCameraState(label: String, state: DualCameraFrameState?) {
        appendLine(label)
        if (state == null) {
            appendLine("Waiting for frames...")
            return
        }
        appendLine("FPS: ${String.format(Locale.US, "%.1f", state.framesPerSecond)}")
        appendLine("Frame bytes: ${state.frame.byteCount}")
        appendLine("Preview: ${state.width}x${state.height} ${FaceCameraInfo.formatName(state.format)}")
        appendLine("Y avg/min/max: ${String.format(Locale.US, "%.1f", state.frame.diagnostics.averageY)} / " +
            "${state.frame.diagnostics.minimumY} / ${state.frame.diagnostics.maximumY}")
        appendLine("Y std dev: ${String.format(Locale.US, "%.1f", state.frame.diagnostics.standardDeviationY)}")
        appendLine("Near 0/255: ${String.format(Locale.US, "%.1f", state.frame.diagnostics.nearZeroPercent)}% / " +
            "${String.format(Locale.US, "%.1f", state.frame.diagnostics.nearWhitePercent)}%")
        appendLine("Variation: ${if (state.frame.diagnostics.hasLumaVariation) "yes" else "no"}")
        appendLine("U/V avg: ${formatOptional(state.frame.diagnostics.averageU)} / ${formatOptional(state.frame.diagnostics.averageV)}")
        append("Chroma std dev: ${formatOptional(state.frame.diagnostics.chromaStandardDeviation)}")
    }

    private fun StringBuilder.appendPairing(snapshot: FaceFrameSynchronizationSnapshot) {
        appendLine("DUAL PAIRING")
        appendLine("Paired frames: ${snapshot.pairedFrameCount}")
        appendLine("Average delta: ${String.format(Locale.US, "%.2f", snapshot.averageDeltaMillis)} ms")
        appendLine("Maximum delta: ${String.format(Locale.US, "%.2f", snapshot.maximumDeltaMillis)} ms")
        append("Dropped/pending unpaired: ${snapshot.droppedFrameCount} / ${snapshot.pendingUnpairedFrameCount}")
    }

    private fun formatDiagnostics(value: com.syntaxgenie.hfx05attendance.face.frame.FaceFrameDiagnostics?): String {
        value ?: return ""
        return "\nY avg/min/max: ${String.format(Locale.US, "%.1f", value.averageY)} / ${value.minimumY} / ${value.maximumY}" +
            "\nY std dev: ${String.format(Locale.US, "%.1f", value.standardDeviationY)}" +
            "\nNear 0/255: ${String.format(Locale.US, "%.1f", value.nearZeroPercent)}% / " +
            "${String.format(Locale.US, "%.1f", value.nearWhitePercent)}%" +
            "\nVariation: ${if (value.hasLumaVariation) "yes" else "no"}" +
            "\nU/V avg: ${formatOptional(value.averageU)} / ${formatOptional(value.averageV)}" +
            "\nChroma std dev: ${formatOptional(value.chromaStandardDeviation)}"
    }

    private fun formatOptional(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "n/a"

    private fun closeCamera(message: String?) {
        manager.close()
        close.isEnabled = false
        if (message != null) frameText.text = message
    }

    private fun stopDualCamera(message: String?) {
        if (dualCamera.isRunning()) dualCamera.stop()
        startDual.isEnabled = open1.visibility == View.VISIBLE
        stopDual.isEnabled = false
        open0.isEnabled = true
        open1.isEnabled = open1.visibility == View.VISIBLE
        if (message != null) dualText.text = message
    }

    private fun closeAllCameras(message: String?) {
        closeCamera(message)
        stopDualCamera(message)
    }

    private fun showDualError(message: String) {
        startDual.isEnabled = open1.visibility == View.VISIBLE
        stopDual.isEnabled = false
        open0.isEnabled = true
        open1.isEnabled = open1.visibility == View.VISIBLE
        dualText.text = message
    }

    private fun showError(message: String) {
        closeCamera(null)
        frameText.text = message
    }

    private fun updateButtons(count: Int) {
        open0.isEnabled = count > 0
        open1.isEnabled = count > 1
        open1.visibility = if (count > 1) View.VISIBLE else View.GONE
        startDual.visibility = if (count > 1) View.VISIBLE else View.GONE
        stopDual.visibility = if (count > 1) View.VISIBLE else View.GONE
        startDual.isEnabled = count > 1 && !dualCamera.isRunning()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; closeAllCameras("Preview surface closed; all cameras released.") }
    override fun onPause() { closeAllCameras("All cameras released while diagnostics are paused."); super.onPause() }
    override fun onDestroy() { manager.close(); dualCamera.stop(); preview.holder.removeCallback(this); super.onDestroy() }

    companion object { private const val DUAL_UI_INTERVAL_NANOS = 500_000_000L }
}
