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
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.util.Locale

class FaceCameraDiagnosticActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var manager: FaceCameraDiagnosticManager
    private lateinit var detectedText: TextView
    private lateinit var reportText: TextView
    private lateinit var frameText: TextView
    private lateinit var preview: SurfaceView
    private lateinit var open0: Button
    private lateinit var open1: Button
    private lateinit var close: Button
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
        preview = findViewById(R.id.cameraPreview)
        open0 = findViewById(R.id.openCamera0Button)
        open1 = findViewById(R.id.openCamera1Button)
        close = findViewById(R.id.closeCameraButton)
        preview.holder.addCallback(this)
        manager = FaceCameraDiagnosticManager(
            onState = { state -> runOnUiThread { showState(state) } },
            onError = { message -> runOnUiThread { showError(message) } },
        )
        findViewById<Button>(R.id.enumerateCamerasButton).setOnClickListener { enumerateCameras() }
        open0.setOnClickListener { requestOpen(0) }
        open1.setOnClickListener { requestOpen(1) }
        close.setOnClickListener { closeCamera("Camera closed.") }
        enumerateCameras()
    }

    private fun enumerateCameras() {
        closeCamera(null)
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
            "Last frame byte length: ${state.lastFrameBytes}"
    }

    private fun closeCamera(message: String?) {
        manager.close()
        close.isEnabled = false
        if (message != null) frameText.text = message
    }

    private fun showError(message: String) {
        closeCamera(null)
        frameText.text = message
    }

    private fun updateButtons(count: Int) {
        open0.isEnabled = count > 0
        open1.isEnabled = count > 1
        open1.visibility = if (count > 1) View.VISIBLE else View.GONE
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; closeCamera("Preview surface closed.") }
    override fun onPause() { closeCamera("Camera released while diagnostics are paused."); super.onPause() }
    override fun onDestroy() { manager.close(); preview.holder.removeCallback(this); super.onDestroy() }
}
