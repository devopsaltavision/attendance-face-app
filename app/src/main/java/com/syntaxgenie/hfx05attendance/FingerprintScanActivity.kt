package com.syntaxgenie.hfx05attendance

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordOutcome
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordStatus
import com.syntaxgenie.hfx05attendance.attendance.AttendanceService
import com.syntaxgenie.hfx05attendance.attendance.PendingAttendanceSyncScheduler
import com.syntaxgenie.hfx05attendance.attendance.local.LocalAttendanceRepository
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
import com.syntaxgenie.hfx05attendance.biometric.CurrentBiometricRuntimePolicy
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.local.EmployeeDirectoryDatabase
import com.syntaxgenie.hfx05attendance.employee.local.LocalEmployeeDirectory
import com.syntaxgenie.hfx05attendance.fingerprint.identification.IdentificationResult
import com.syntaxgenie.hfx05attendance.fingerprint.identification.IdentificationService
import com.syntaxgenie.hfx05attendance.fingerprint.identification.ScoreThresholdIdentificationPolicy
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.sourceafis.SourceAfisFingerprintMatcher
import com.syntaxgenie.hfx05attendance.fingerprint.repository.cache.BiometricTemplateCache
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.BiometricDatabase
import com.syntaxgenie.hfx05attendance.fingerprint.repository.local.LocalBiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerError
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05.Hfx05FingerprintScanner
import com.syntaxgenie.hfx05attendance.ui.AppSoundManager
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.ui.SemanticResultView
import java.util.concurrent.atomic.AtomicBoolean

private object FingerprintCaptureGate {
    private val busy = AtomicBoolean(false)
    fun tryAcquire() = busy.compareAndSet(false, true)
    fun release() = busy.set(false)
}

class FingerprintScanActivity : AppCompatActivity() {
    private val scanner by lazy { Hfx05FingerprintScanner() }
    private val matcher by lazy { SourceAfisFingerprintMatcher() }
    private val biometricDatabase by lazy { BiometricDatabase.create(applicationContext) }
    private val templateCache by lazy {
        val repository = LocalBiometricRepository(biometricDatabase.biometricTemplateDao(), matcher.metadata)
        BiometricTemplateCache(repository)
    }
    private val identificationService by lazy {
        IdentificationService(matcher, templateCache, ScoreThresholdIdentificationPolicy(
            ApplicationIdentificationConfig.PROVISIONAL_IDENTIFICATION_MIN_SCORE,
            ApplicationIdentificationConfig.PROVISIONAL_IDENTIFICATION_MIN_MARGIN,
        ))
    }
    private val employeeDatabase by lazy { EmployeeDirectoryDatabase.create(applicationContext) }
    private val employeeDirectory by lazy { LocalEmployeeDirectory(employeeDatabase.employeeDao()) }
    private val deviceConfiguration by lazy { DeviceConfigurationRepository(this) }
    private val attendanceService by lazy {
        val environment = BackendEnvironmentConfig()
        AttendanceService(FingerprintApiClient(environment).create(), environment,
            deviceConfiguration::deviceId, LocalAttendanceRepository(employeeDatabase.attendanceDao()), ::networkAvailable,
            pendingSyncScheduler = { PendingAttendanceSyncScheduler.enqueueIfPending(applicationContext) })
    }
    private val scanWorkerRunning = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var resultView: SemanticResultView
    private lateinit var actionButton: Button
    private val soundManager by lazy { AppSoundManager(applicationContext) }
    @Volatile private var scanActive = false
    @Volatile private var scanGeneration = 0
    private var initialAttemptStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fingerprint_scan)
        KioskWindowInsets.apply(this, findViewById(R.id.fingerprintScanRoot))
        findViewById<MaterialToolbar>(R.id.fingerprintScanToolbar).setNavigationOnClickListener { finish() }
        resultView = findViewById(R.id.fingerprintScanResult)
        actionButton = findViewById(R.id.fingerprintScanAction)
        actionButton.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        scanActive = true
        scanGeneration++
        if (!CurrentBiometricRuntimePolicy.isFingerprintEnabled(this)) {
            showBackResult(SemanticResultView.Kind.WARNING, getString(R.string.fingerprint_recognition_disabled),
                getString(R.string.fingerprint_recognition_disabled_message))
        } else if (!initialAttemptStarted) {
            initialAttemptStarted = true
            startOneScan()
        }
    }

    override fun onStop() {
        scanActive = false
        scanGeneration++
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        soundManager.release()
        super.onDestroy()
    }

    private fun startOneScan() {
        if (!CurrentBiometricRuntimePolicy.isFingerprintEnabled(this) || !scanActive || scanWorkerRunning.get()) return
        if (BuildConfig.FINGERPRINT_EMULATOR || BuildConfig.FINGERPRINT_GUIDE_MODE) {
            showBackResult(SemanticResultView.Kind.ERROR, getString(R.string.fingerprint_scanner_unavailable),
                getString(R.string.fingerprint_scanner_unavailable_message))
            return
        }
        if (!scanWorkerRunning.compareAndSet(false, true)) {
            showTryAgainResult(SemanticResultView.Kind.WARNING, getString(R.string.fingerprint_scan_busy),
                getString(R.string.fingerprint_scan_busy_message))
            return
        }
        if (!FingerprintCaptureGate.tryAcquire()) {
            scanWorkerRunning.set(false)
            showTryAgainResult(SemanticResultView.Kind.WARNING, getString(R.string.fingerprint_scan_busy),
                getString(R.string.fingerprint_scan_busy_message))
            return
        }
        val generation = scanGeneration
        showScanning(generation)
        Thread {
            try {
                identificationService.reloadTemplates()?.let { error ->
                    showTryAgainResult(generation, SemanticResultView.Kind.WARNING,
                        getString(R.string.fingerprint_templates_unavailable), error.error.userMessage)
                    return@Thread
                }
                if (templateCache.snapshot().records.isEmpty()) {
                    showBackResult(generation, SemanticResultView.Kind.WARNING, getString(R.string.no_fingerprints_enrolled),
                        getString(R.string.no_fingerprints_enrolled_message))
                    return@Thread
                }
                if (!isCurrentScan(generation)) return@Thread
                when (val capture = scanner.capture { progress ->
                    if (progress == ScannerProgress.CAPTURING) showScanning(generation)
                }) {
                    is ScannerResult.Success -> if (isCurrentScan(generation)) {
                        handleIdentification(generation, identificationService.identify(capture.value))
                    }
                    is ScannerResult.Error -> if (capture.error == ScannerError.HARDWARE_UNAVAILABLE) {
                        showBackResult(generation, SemanticResultView.Kind.ERROR, getString(R.string.fingerprint_scanner_unavailable),
                            getString(R.string.fingerprint_scanner_unavailable_message))
                    } else {
                        showTryAgainResult(generation, SemanticResultView.Kind.ERROR, getString(R.string.fingerprint_scan_failed),
                            capture.error.userMessage)
                    }
                }
            } finally {
                scanWorkerRunning.set(false)
                FingerprintCaptureGate.release()
            }
        }.apply { name = "fingerprint-recognition-scan" }.start()
    }

    private fun handleIdentification(generation: Int, result: IdentificationResult) {
        when (result) {
            is IdentificationResult.Identified -> {
                val employee = employeeDirectory.search(result.employeeId)
                    .firstOrNull { it.employeeId.equals(result.employeeId, ignoreCase = true) }
                if (employee == null) showTryAgainResult(generation, SemanticResultView.Kind.ERROR,
                    getString(R.string.employee_record_not_found), getString(R.string.try_again))
                else recordAttendance(generation, employee)
            }
            is IdentificationResult.Unknown -> showTryAgainResult(generation, SemanticResultView.Kind.WARNING,
                getString(R.string.fingerprint_not_recognized), getString(R.string.try_again))
            is IdentificationResult.Ambiguous -> showTryAgainResult(generation, SemanticResultView.Kind.WARNING,
                getString(R.string.fingerprint_match_ambiguous), getString(R.string.try_again))
            IdentificationResult.NoTemplates -> showBackResult(generation, SemanticResultView.Kind.WARNING,
                getString(R.string.no_fingerprints_enrolled), getString(R.string.no_fingerprints_enrolled_message))
            is IdentificationResult.Error -> showTryAgainResult(generation, SemanticResultView.Kind.WARNING,
                getString(R.string.fingerprint_scan_failed), result.error.userMessage)
        }
    }

    private fun recordAttendance(generation: Int, employee: EmployeeRecord) {
        runOnUiThread {
            if (isCurrentScan(generation)) resultView.show(SemanticResultView.Kind.INFO,
                getString(R.string.recording_attendance), getString(R.string.recording_attendance_message))
        }
        if (!isCurrentScan(generation)) return
        showAttendanceResult(generation, employee, attendanceService.record(employee.userId, employee.employeeId))
    }

    private fun showAttendanceResult(generation: Int, employee: EmployeeRecord, outcome: AttendanceRecordOutcome) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        actionButton.visibility = View.GONE
        when (outcome.status) {
            AttendanceRecordStatus.DUPLICATE_IGNORED -> {
                soundManager.play(AppSoundManager.Event.WARNING)
                resultView.show(SemanticResultView.Kind.WARNING, getString(R.string.attendance_already_recorded),
                    getString(R.string.attendance_duplicate_try_again))
            }
            AttendanceRecordStatus.REJECTED -> {
                soundManager.play(AppSoundManager.Event.ERROR)
                resultView.show(SemanticResultView.Kind.ERROR, getString(R.string.attendance_could_not_be_recorded),
                    getString(R.string.try_again))
            }
            else -> {
                soundManager.play(AppSoundManager.Event.SUCCESS)
                val synced = outcome.status == AttendanceRecordStatus.SYNCED
                resultView.show(SemanticResultView.Kind.SUCCESS,
                    getString(if (synced) R.string.attendance_recorded_synced else R.string.attendance_recorded_pending,
                        *if (synced) arrayOf(outcome.event.attendanceAction ?: getString(R.string.attendance_action_recorded)) else emptyArray()),
                    listOf(employee.displayName, employee.employeeId, outcome.attendanceTime()).joinToString("\n"))
            }
        }
        handler.postDelayed({ if (!isFinishing) finish() }, RESULT_DURATION_MS)
    }

    private fun AttendanceRecordOutcome.attendanceTime() = event.serverTimestamp ?: event.deviceTimestamp

    private fun showScanning(generation: Int) = runOnUiThread {
        if (isCurrentScan(generation)) {
            actionButton.visibility = View.VISIBLE
            actionButton.setText(R.string.cancel)
            actionButton.setOnClickListener { finish() }
            resultView.show(SemanticResultView.Kind.INFO, getString(R.string.place_your_finger),
                getString(R.string.place_finger_on_sensor))
        }
    }

    private fun showTryAgainResult(kind: SemanticResultView.Kind, title: String, message: String) =
        showTryAgainResult(scanGeneration, kind, title, message)

    private fun showTryAgainResult(generation: Int, kind: SemanticResultView.Kind, title: String, message: String) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        soundManager.play(if (kind == SemanticResultView.Kind.ERROR) AppSoundManager.Event.ERROR else AppSoundManager.Event.WARNING)
        resultView.show(kind, title, message)
        actionButton.visibility = View.VISIBLE
        actionButton.setText(R.string.try_again)
        actionButton.setOnClickListener { if (!scanWorkerRunning.get()) startOneScan() }
    }

    private fun showBackResult(kind: SemanticResultView.Kind, title: String, message: String) =
        showBackResult(scanGeneration, kind, title, message)

    private fun showBackResult(generation: Int, kind: SemanticResultView.Kind, title: String, message: String) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        soundManager.play(if (kind == SemanticResultView.Kind.ERROR) AppSoundManager.Event.ERROR else AppSoundManager.Event.WARNING)
        resultView.show(kind, title, message)
        actionButton.visibility = View.VISIBLE
        actionButton.setText(R.string.back_to_home)
        actionButton.setOnClickListener { finish() }
    }

    private fun isCurrentScan(generation: Int) = scanActive && generation == scanGeneration && !isFinishing && !isDestroyed

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private companion object {
        const val RESULT_DURATION_MS = 3_500L
    }
}
