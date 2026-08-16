package com.syntaxgenie.hfx05attendance

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentLowerLayerError
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentProgress
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentRequest
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentResult
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentState
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.FingerprintEnrollmentService
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05.Hfx05FingerprintScanner
import com.syntaxgenie.hfx05attendance.ui.FingerprintVisualView

class FingerprintRegistrationActivity : AppCompatActivity() {
    private lateinit var employeeId: EditText
    private lateinit var finger: Spinner
    private lateinit var visual: FingerprintVisualView
    private lateinit var progressText: TextView
    private lateinit var detailsText: TextView
    private lateinit var dots: LinearLayout
    private lateinit var startButton: Button
    private lateinit var captureButton: Button
    private lateinit var cancelButton: Button
    private var running = false
    private val matcher by lazy { SourceAfisFingerprintMatcher() }
    private val database by lazy { BiometricDatabase.create(applicationContext) }
    private val repository by lazy { LocalBiometricRepository(database.biometricTemplateDao(), matcher.metadata) }
    private val service by lazy { FingerprintEnrollmentService(Hfx05FingerprintScanner(), matcher, repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint_registration)
        employeeId = findViewById(R.id.registrationEmployeeId)
        finger = findViewById(R.id.registrationFinger)
        visual = findViewById(R.id.registrationFingerprintVisual)
        progressText = findViewById(R.id.registrationProgress)
        detailsText = findViewById(R.id.registrationDetails)
        dots = findViewById(R.id.registrationDots)
        startButton = findViewById(R.id.registrationStartButton)
        captureButton = findViewById(R.id.registrationCaptureButton)
        cancelButton = findViewById(R.id.registrationCancelButton)
        finger.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            FingerPosition.entries.map(FingerPosition::name))
        findViewById<Button>(R.id.registrationBackButton).setOnClickListener { finish() }
        startButton.setOnClickListener { startEnrollment() }
        captureButton.setOnClickListener { captureNext() }
        cancelButton.setOnClickListener { render(service.cancel()) }
        updateProgress(0, 5)
        refreshControls()
    }

    private fun startEnrollment() {
        val id = employeeId.text.toString().trim()
        if (id.isBlank()) { employeeId.error = getString(R.string.employee_id_required); return }
        val selected = FingerPosition.entries[finger.selectedItemPosition]
        runEnrollmentOperation("fingerprint-enrollment-start") { service.start(EnrollmentRequest(id, selected)) }
    }

    private fun captureNext() = runEnrollmentOperation("fingerprint-enrollment-capture") {
        if (service.currentSession()?.state == EnrollmentState.FAILED) service.retrySave()
        else service.captureNext { progress -> runOnUiThread { renderProgress(progress) } }
    }

    private fun runEnrollmentOperation(name: String, operation: () -> EnrollmentResult<*>) {
        if (running) return
        running = true
        refreshControls()
        Thread {
            val result = operation()
            runOnUiThread { running = false; render(result) }
        }.apply { this.name = name }.start()
    }

    private fun render(result: EnrollmentResult<*>) {
        val session = service.currentSession() ?: (result as? EnrollmentResult.Error)?.session
        updateProgress(session?.completedCaptures ?: 0, session?.requiredCaptures ?: 5)
        when (result) {
            is EnrollmentResult.Success -> when (session?.state) {
                EnrollmentState.COMPLETED -> {
                    visual.render(FingerprintVisualView.State.SUCCESS)
                    progressText.setText(R.string.fingerprint_registered)
                    detailsText.text = getString(R.string.registration_complete_details,
                        session.employeeId, session.fingerPosition.name, session.completedCaptures)
                }
                EnrollmentState.CANCELLED -> {
                    visual.render(FingerprintVisualView.State.READY)
                    progressText.setText(R.string.enrollment_cancelled)
                    detailsText.text = ""
                }
                else -> {
                    visual.render(FingerprintVisualView.State.READY)
                    progressText.text = getString(R.string.capture_progress, session?.completedCaptures ?: 0, 5)
                    detailsText.text = ""
                }
            }
            is EnrollmentResult.Error -> {
                visual.render(FingerprintVisualView.State.ERROR)
                progressText.text = "${result.error.userMessage}\n${result.error.code}"
                detailsText.text = lowerLayerMessage(result.lowerLayerError)
            }
        }
        refreshControls()
    }

    private fun renderProgress(progress: EnrollmentProgress) {
        visual.render(if (progress.state == EnrollmentState.CAPTURING) FingerprintVisualView.State.SCANNING
            else FingerprintVisualView.State.READY)
        progressText.text = when (progress.state) {
            EnrollmentState.CAPTURING -> getString(R.string.capturing_progress, progress.captureNumber, progress.requiredCaptures)
            EnrollmentState.PROCESSING -> getString(R.string.processing_progress, progress.captureNumber, progress.requiredCaptures)
            EnrollmentState.SAVING -> getString(R.string.saving_enrollment)
            else -> getString(R.string.capture_progress, progress.completedCaptures, progress.requiredCaptures)
        }
    }

    private fun updateProgress(completed: Int, required: Int) {
        dots.removeAllViews()
        repeat(required) { index ->
            android.view.View(this).also { dot ->
                val size = resources.getDimensionPixelSize(R.dimen.enrollment_dot_size)
                val margin = resources.getDimensionPixelSize(R.dimen.enrollment_dot_margin)
                dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
                dot.setBackgroundResource(if (index < completed) R.drawable.progress_dot_complete else R.drawable.progress_dot_pending)
                dots.addView(dot)
            }
        }
    }

    private fun refreshControls() {
        val state = service.currentSession()?.state
        startButton.isEnabled = !running && state !in setOf(EnrollmentState.READY, EnrollmentState.CAPTURING,
            EnrollmentState.PROCESSING, EnrollmentState.SAVING)
        captureButton.isEnabled = !running && state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)
        captureButton.setText(if (state == EnrollmentState.FAILED) R.string.retry_enrollment_save else R.string.capture_next)
        cancelButton.isEnabled = !running && state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)
        employeeId.isEnabled = startButton.isEnabled
        finger.isEnabled = startButton.isEnabled
    }

    private fun lowerLayerMessage(error: EnrollmentLowerLayerError?): String = when (error) {
        is EnrollmentLowerLayerError.Scanner -> "Scanner: ${error.value.error.code} ${error.value.error.userMessage}"
        is EnrollmentLowerLayerError.Matcher -> "Matcher: ${error.value.error.code} ${error.value.error.userMessage}"
        is EnrollmentLowerLayerError.Repository -> "Repository: ${error.value.error.code} ${error.value.error.userMessage}"
        null -> ""
    }
}
