package com.syntaxgenie.hfx05attendance

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentLowerLayerError
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentProgress
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentRequest
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentResult
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.EnrollmentState
import com.syntaxgenie.hfx05attendance.fingerprint.enrollment.FingerprintEnrollmentService
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05.Hfx05FingerprintScanner
import com.syntaxgenie.hfx05attendance.ui.FingerprintVisualView
import com.syntaxgenie.hfx05attendance.ui.FingerDropdownOptions
import com.syntaxgenie.hfx05attendance.ui.RegistrationEmployeeUiModel
import com.syntaxgenie.hfx05attendance.ui.displayName
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets

class FingerprintRegistrationActivity : AppCompatActivity() {
    private lateinit var visual: FingerprintVisualView
    private lateinit var progressText: TextView
    private lateinit var detailsText: TextView
    private lateinit var guidanceText: TextView
    private lateinit var dots: LinearLayout
    private lateinit var startButton: Button
    private lateinit var captureButton: Button
    private lateinit var cancelButton: Button
    private lateinit var doneButton: Button
    private lateinit var fingerDropdown: MaterialAutoCompleteTextView
    private var selectedFinger = FingerDropdownOptions.default
    private var employeeModel: RegistrationEmployeeUiModel? = null
    private var previewOnly = false
    private var running = false
    private val matcher by lazy { SourceAfisFingerprintMatcher() }
    private val database by lazy { BiometricDatabase.create(applicationContext) }
    private val repository by lazy { LocalBiometricRepository(database.biometricTemplateDao(), matcher.metadata) }
    private val service by lazy { FingerprintEnrollmentService(Hfx05FingerprintScanner(), matcher, repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint_registration)
        KioskWindowInsets.apply(this, findViewById(R.id.registrationRoot))
        visual = findViewById(R.id.registrationFingerprintVisual)
        progressText = findViewById(R.id.registrationProgress)
        detailsText = findViewById(R.id.registrationDetails)
        guidanceText = findViewById(R.id.registrationGuidance)
        dots = findViewById(R.id.registrationDots)
        startButton = findViewById(R.id.registrationStartButton)
        captureButton = findViewById(R.id.registrationCaptureButton)
        cancelButton = findViewById(R.id.registrationCancelButton)
        doneButton = findViewById(R.id.registrationDoneButton)
        fingerDropdown = findViewById(R.id.registrationFingerDropdown)
        findViewById<MaterialToolbar>(R.id.registrationToolbar).setNavigationOnClickListener { finish() }
        configureFingerSelection()
        configureEmployee()
        findViewById<Button>(R.id.backToUserManagementButton).setOnClickListener {
            startActivity(Intent(this, UserManagementActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
            finish()
        }
        findViewById<Button>(R.id.debugPreviewRegistrationButton).apply {
            visibility = if (isDebugBuild() && employeeModel == null) View.VISIBLE else View.GONE
            setOnClickListener {
                startActivity(createPreviewIntent(this@FingerprintRegistrationActivity))
                finish()
            }
        }
        startButton.setOnClickListener { startEnrollment() }
        captureButton.setOnClickListener { captureNext() }
        cancelButton.setOnClickListener { render(service.cancel()) }
        doneButton.setOnClickListener { finish() }
        updateProgress(0, 5)
        refreshControls()
    }

    private fun startEnrollment() {
        val employee = employeeModel ?: return
        runEnrollmentOperation("fingerprint-enrollment-start") {
            service.start(EnrollmentRequest(employee.employeeId, selectedFinger))
        }
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
                        employeeModel?.displayName ?: session.employeeId,
                        session.employeeId,
                        session.fingerPosition.displayName(this),
                        session.completedCaptures)
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
                progressText.text = if (isDebugBuild()) "${result.error.userMessage}\n${result.error.code}"
                    else result.error.userMessage
                detailsText.text = if (isDebugBuild()) lowerLayerMessage(result.lowerLayerError) else ""
            }
        }
        refreshControls()
    }

    private fun renderProgress(progress: EnrollmentProgress) {
        updateProgress(progress.completedCaptures, progress.requiredCaptures)
        progressText.text = when (progress.state) {
            EnrollmentState.CAPTURING -> getString(R.string.capturing_progress, progress.captureNumber, progress.requiredCaptures)
            EnrollmentState.PROCESSING -> getString(R.string.processing_progress, progress.captureNumber, progress.requiredCaptures)
            EnrollmentState.SAVING -> getString(R.string.saving_enrollment)
            else -> getString(R.string.capture_progress, progress.completedCaptures, progress.requiredCaptures)
        }
    }

    private fun updateProgress(completed: Int, required: Int) {
        visual.renderEnrollmentProgress(completed, required)
        guidanceText.setText(if (completed == 0) R.string.capture_guidance_first else R.string.capture_guidance_repeat)
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
        startButton.isEnabled = employeeModel != null && !previewOnly && !running && state !in setOf(EnrollmentState.READY, EnrollmentState.CAPTURING,
            EnrollmentState.PROCESSING, EnrollmentState.SAVING)
        captureButton.isEnabled = !running && state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)
        captureButton.setText(if (state == EnrollmentState.FAILED) R.string.retry_enrollment_save else R.string.capture_next)
        cancelButton.isEnabled = !running && state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)
        startButton.visibility = if (state == null || state == EnrollmentState.CANCELLED) View.VISIBLE else View.GONE
        captureButton.visibility = if (state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)) View.VISIBLE else View.GONE
        cancelButton.visibility = if (state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)) View.VISIBLE else View.GONE
        doneButton.visibility = if (state == EnrollmentState.COMPLETED) View.VISIBLE else View.GONE
        val selectionEnabled = employeeModel != null && !running &&
            state !in setOf(EnrollmentState.READY, EnrollmentState.CAPTURING,
                EnrollmentState.PROCESSING, EnrollmentState.SAVING, EnrollmentState.FAILED, EnrollmentState.COMPLETED)
        fingerDropdown.isEnabled = selectionEnabled
    }

    private fun configureEmployee() {
        previewOnly = isDebugBuild() && intent.getBooleanExtra(EXTRA_PREVIEW_ONLY, false)
        employeeModel = RegistrationEmployeeUiModel.from(
            intent.getStringExtra(EXTRA_EMPLOYEE_ID),
            intent.getStringExtra(EXTRA_EMPLOYEE_DISPLAY_NAME),
            intent.getStringExtra(EXTRA_USER_ID),
        )
        val content = findViewById<View>(R.id.registrationEnrollmentContent)
        val missing = findViewById<View>(R.id.missingEmployeeCard)
        employeeModel?.let { employee ->
            content.visibility = View.VISIBLE
            missing.visibility = View.GONE
            findViewById<TextView>(R.id.selectedEmployeeName).text = employee.displayName
            findViewById<TextView>(R.id.selectedEmployeeId).text = employee.employeeId
            findViewById<TextView>(R.id.selectedEmployeeUserId).text = getString(R.string.employee_epf, employee.userId)
            findViewById<View>(R.id.debugPreviewLabel).visibility = if (previewOnly) View.VISIBLE else View.GONE
        } ?: run {
            content.visibility = View.GONE
            missing.visibility = View.VISIBLE
        }
    }

    private fun configureFingerSelection() {
        val labels = FingerDropdownOptions.positions.map { it.displayName(this) }
        fingerDropdown.apply {
            setAdapter(ArrayAdapter(this@FingerprintRegistrationActivity, R.layout.item_finger_dropdown, labels))
            setText(selectedFinger.displayName(this@FingerprintRegistrationActivity), false)
            keyListener = null
            showSoftInputOnFocus = false
            setOnClickListener { if (isEnabled) showDropDown() }
            setOnItemClickListener { _, _, position, _ ->
                selectedFinger = FingerDropdownOptions.positionAt(position)
                setText(selectedFinger.displayName(this@FingerprintRegistrationActivity), false)
            }
        }
    }

    private fun isDebugBuild() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun lowerLayerMessage(error: EnrollmentLowerLayerError?): String = when (error) {
        is EnrollmentLowerLayerError.Scanner -> "Scanner: ${error.value.error.code} ${error.value.error.userMessage}"
        is EnrollmentLowerLayerError.Matcher -> "Matcher: ${error.value.error.code} ${error.value.error.userMessage}"
        is EnrollmentLowerLayerError.Repository -> "Repository: ${error.value.error.code} ${error.value.error.userMessage}"
        null -> ""
    }

    companion object {
        const val EXTRA_EMPLOYEE_ID = "employeeId"
        const val EXTRA_EMPLOYEE_DISPLAY_NAME = "employeeDisplayName"
        const val EXTRA_USER_ID = "userId"
        private const val EXTRA_PREVIEW_ONLY = "previewOnly"

        fun createIntent(context: Context, userId: String, employeeId: String, employeeDisplayName: String): Intent =
            Intent(context, FingerprintRegistrationActivity::class.java).apply {
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_EMPLOYEE_ID, employeeId)
                putExtra(EXTRA_EMPLOYEE_DISPLAY_NAME, employeeDisplayName)
            }

        private fun createPreviewIntent(context: Context): Intent =
            createIntent(context, "PREVIEW-EPF", "PREVIEW-EMP", "Preview Employee").apply {
                putExtra(EXTRA_PREVIEW_ONLY, true)
            }
    }
}
