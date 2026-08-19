package com.syntaxgenie.hfx05attendance

import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeModel
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeState
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordOutcome
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordStatus
import com.syntaxgenie.hfx05attendance.attendance.AttendanceService
import com.syntaxgenie.hfx05attendance.attendance.local.LocalAttendanceRepository
import com.syntaxgenie.hfx05attendance.backend.FingerprintApiClient
import com.syntaxgenie.hfx05attendance.backend.config.BackendEnvironmentConfig
import com.syntaxgenie.hfx05attendance.backend.config.DeviceConfigurationRepository
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
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerError
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05.Hfx05FingerprintScanner
import com.syntaxgenie.hfx05attendance.ui.FingerprintVisualView
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val scanner by lazy { Hfx05FingerprintScanner() }
    private val matcher by lazy { SourceAfisFingerprintMatcher() }
    private val biometricDatabase by lazy { BiometricDatabase.create(applicationContext) }
    private val identificationService by lazy {
        val repository = LocalBiometricRepository(biometricDatabase.biometricTemplateDao(), matcher.metadata)
        IdentificationService(matcher, BiometricTemplateCache(repository), ScoreThresholdIdentificationPolicy(
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
            deviceConfiguration::deviceId, LocalAttendanceRepository(employeeDatabase.attendanceDao()), ::networkAvailable)
    }
    private val scanWorkerRunning = AtomicBoolean(false)
    @Volatile private var scanActive = false
    @Volatile private var scanGeneration = 0
    @Volatile private var templatesLoadedGeneration = -1
    private val syncConfigured by lazy {
        val environment = BackendEnvironmentConfig()
        val deviceConfiguration = DeviceConfigurationRepository(this, environment)
        environment.apiKeyConfigured && environment.baseUrl.isNotBlank() &&
            deviceConfiguration.deviceId().isNotBlank()
    }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: android.view.View
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var visual: FingerprintVisualView
    private lateinit var titleText: TextView
    private lateinit var instructionText: TextView
    private lateinit var detailText: TextView
    private lateinit var syncStatusText: TextView
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, MILLIS_PER_MINUTE)
        }
    }
    private val resetReady = Runnable {
        if (scanActive) {
            render(defaultReadyModel())
            startScanWorker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.attendanceHomeRoot)
        KioskWindowInsets.apply(this, root)
        timeText = findViewById(R.id.currentTime)
        dateText = findViewById(R.id.currentDate)
        visual = findViewById(R.id.fingerprintVisual)
        titleText = findViewById(R.id.attendanceStatusTitle)
        instructionText = findViewById(R.id.attendanceInstruction)
        detailText = findViewById(R.id.attendanceDetail)
        syncStatusText = findViewById(R.id.homeSyncStatus)
        findViewById<ImageButton>(R.id.adminButton).setOnClickListener {
            val destination = if (FirebaseAuth.getInstance().currentUser == null) {
                AdminLoginActivity::class.java
            } else {
                AdminDashboardActivity::class.java
            }
            startActivity(Intent(this, destination))
        }
        render(defaultReadyModel())
    }

    override fun onStart() {
        super.onStart()
        handler.post(clockTick)
        scanActive = true
        scanGeneration++
        render(defaultReadyModel())
        startScanWorker()
    }

    override fun onStop() {
        scanActive = false
        scanGeneration++
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(resetReady)
        super.onStop()
    }

    private fun startScanWorker() {
        if (!scanActive || !scanWorkerRunning.compareAndSet(false, true)) return
        val generation = scanGeneration
        Thread {
            try {
                if (templatesLoadedGeneration != generation) {
                    identificationService.reloadTemplates()?.let { error ->
                        showScanResult(generation, AttendanceHomeState.WARNING, error.error.userMessage)
                        return@Thread
                    }
                    templatesLoadedGeneration = generation
                }
                val capture = scanner.capture { progress ->
                    if (progress == ScannerProgress.CAPTURING) {
                        runOnUiThread {
                            if (isCurrentScan(generation)) render(AttendanceHomeModel(
                                AttendanceHomeState.SCANNING,
                                getString(R.string.scanning_fingerprint),
                                getString(R.string.place_finger_on_sensor),
                            ))
                        }
                    }
                }
                if (!isCurrentScan(generation)) return@Thread
                when (capture) {
                    is ScannerResult.Success -> handleIdentification(generation,
                        identificationService.identify(capture.value))
                    is ScannerResult.Error -> if (capture.error == ScannerError.HARDWARE_UNAVAILABLE) {
                        showTerminalScannerError(generation, capture.error.userMessage)
                    } else {
                        showScanResult(generation, AttendanceHomeState.FAILURE, capture.error.userMessage)
                    }
                }
            } finally {
                scanWorkerRunning.set(false)
                if (scanActive && generation != scanGeneration) runOnUiThread { startScanWorker() }
            }
        }.apply { name = "home-fingerprint-scan" }.start()
    }

    private fun handleIdentification(generation: Int, result: IdentificationResult) {
        when (result) {
            is IdentificationResult.Identified -> {
                val employee = employeeDirectory.search(result.employeeId)
                    .firstOrNull { it.employeeId.equals(result.employeeId, ignoreCase = true) }
                if (employee == null) showScanResult(generation, AttendanceHomeState.FAILURE,
                    getString(R.string.employee_record_not_found))
                else showAttendanceResult(generation, employee,
                    attendanceService.record(employee.userId, employee.employeeId))
            }
            is IdentificationResult.Unknown -> showScanResult(generation, AttendanceHomeState.FAILURE,
                getString(R.string.fingerprint_not_recognized))
            is IdentificationResult.Ambiguous -> showScanResult(generation, AttendanceHomeState.WARNING,
                getString(R.string.fingerprint_match_ambiguous))
            IdentificationResult.NoTemplates -> showScanResult(generation, AttendanceHomeState.WARNING,
                getString(R.string.no_fingerprint_templates))
            is IdentificationResult.Error -> showScanResult(generation, AttendanceHomeState.WARNING,
                result.error.userMessage)
        }
    }

    private fun showAttendanceResult(
        generation: Int,
        employee: EmployeeRecord,
        outcome: AttendanceRecordOutcome,
    ) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        val synced = outcome.status == AttendanceRecordStatus.SYNCED
        render(AttendanceHomeModel(
            AttendanceHomeState.SUCCESS,
            getString(if (synced) R.string.attendance_recorded_synced else R.string.attendance_recorded_pending,
                *if (synced) arrayOf(outcome.event.attendanceAction ?: getString(R.string.attendance_action_recorded)) else emptyArray()),
            employeeName = employee.displayName,
            employeeId = employee.employeeId,
            attendanceActionLabel = outcome.event.attendanceAction,
            attendanceTimeLabel = outcome.event.serverTimestamp ?: outcome.event.deviceTimestamp,
        ))
        handler.postDelayed(resetReady, SUCCESS_DURATION_MS)
    }

    private fun showScanResult(generation: Int, state: AttendanceHomeState, message: String) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        render(AttendanceHomeModel(state, message, getString(R.string.place_finger_on_sensor)))
        handler.postDelayed(resetReady, SUCCESS_DURATION_MS)
    }

    private fun showTerminalScannerError(generation: Int, message: String) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        handler.removeCallbacks(resetReady)
        render(AttendanceHomeModel(AttendanceHomeState.FAILURE, message))
    }

    private fun isCurrentScan(generation: Int) = scanActive && generation == scanGeneration &&
        !isFinishing && !isDestroyed

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun render(model: AttendanceHomeModel) {
        handler.removeCallbacks(resetReady)
        val background = when (model.state) {
            AttendanceHomeState.SUCCESS -> R.color.attendance_success
            else -> R.color.attendance_ready_background
        }
        root.setBackgroundColor(ContextCompat.getColor(this, background))
        visual.render(when (model.state) {
            AttendanceHomeState.READY -> FingerprintVisualView.State.READY
            AttendanceHomeState.SCANNING -> FingerprintVisualView.State.SCANNING
            AttendanceHomeState.SUCCESS -> FingerprintVisualView.State.SUCCESS
            AttendanceHomeState.FAILURE -> FingerprintVisualView.State.ERROR
            AttendanceHomeState.WARNING -> FingerprintVisualView.State.WARNING
        })
        titleText.text = model.title
        instructionText.text = model.instruction.orEmpty()
        detailText.text = listOfNotNull(model.employeeName, model.employeeId,
            model.attendanceActionLabel, model.attendanceTimeLabel).joinToString("\n")
        syncStatusText.visibility = if (syncConfigured) View.GONE else View.VISIBLE
        val textColor = ContextCompat.getColor(this,
            if (model.state == AttendanceHomeState.SUCCESS) R.color.white else R.color.attendance_text)
        listOf(timeText, dateText, titleText, instructionText, detailText).forEach { it.setTextColor(textColor) }
    }

    private fun defaultReadyModel() = AttendanceHomeModel(
        state = AttendanceHomeState.READY,
        title = getString(R.string.ready_to_scan),
        instruction = getString(R.string.place_finger_on_sensor),
    )

    private fun updateClock() {
        val now = Date()
        timeText.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val SUCCESS_DURATION_MS = 2_000L
    }
}
