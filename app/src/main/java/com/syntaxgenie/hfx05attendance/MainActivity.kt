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
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeModel
import com.syntaxgenie.hfx05attendance.attendance.AttendanceHomeState
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordOutcome
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRecordStatus
import com.syntaxgenie.hfx05attendance.attendance.AttendanceService
import com.syntaxgenie.hfx05attendance.attendance.PendingAttendanceSyncScheduler
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
import com.syntaxgenie.hfx05attendance.ui.SemanticResultView
import com.syntaxgenie.hfx05attendance.ui.AppSoundManager
import com.syntaxgenie.hfx05attendance.ui.KioskWindowInsets
import com.syntaxgenie.hfx05attendance.face.scan.FaceScanActivity
import com.syntaxgenie.hfx05attendance.attendance.AttendanceActivity
import com.syntaxgenie.hfx05attendance.biometric.CurrentBiometricRuntimePolicy
import com.syntaxgenie.hfx05attendance.biometric.BiometricRuntimeMode
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
            deviceConfiguration::deviceId, LocalAttendanceRepository(employeeDatabase.attendanceDao()), ::networkAvailable,
            pendingSyncScheduler = { PendingAttendanceSyncScheduler.enqueueIfPending(applicationContext) })
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
    private lateinit var resultView: SemanticResultView
    private val soundManager by lazy { AppSoundManager(applicationContext) }
    private var emulatorEmployees: List<EmployeeRecord> = emptyList()
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
        resultView = findViewById(R.id.attendanceResult)
        configureEmulatorControls()
        findViewById<ImageButton>(R.id.adminButton).setOnClickListener {
            val destination = if (FirebaseAuth.getInstance().currentUser == null) {
                AdminLoginActivity::class.java
            } else {
                AdminDashboardActivity::class.java
            }
            startActivity(Intent(this, destination))
        }
        findViewById<Button>(R.id.scanFaceButton).setOnClickListener {
            if (!CurrentBiometricRuntimePolicy.isFaceEnabled(this)) {
                render(AttendanceHomeModel(AttendanceHomeState.WARNING, "Face Recognition is disabled", "Please use Fingerprint or contact an administrator."))
                return@setOnClickListener
            }
            startActivity(Intent(this, FaceScanActivity::class.java).apply {
                if (BuildConfig.DEBUG) putExtra(FaceScanActivity.EXTRA_DEBUG_IDENTIFICATION, true)
            })
        }
        findViewById<Button>(R.id.scanFingerprintButton).setOnClickListener {
            startActivity(Intent(this, AttendanceActivity::class.java))
        }
        render(defaultReadyModel())
    }

    override fun onStart() {
        super.onStart()
        PendingAttendanceSyncScheduler.enqueueIfPending(applicationContext)
        handler.post(clockTick)
        scanActive = true
        scanGeneration++
        render(defaultReadyModel())
        if (CurrentBiometricRuntimePolicy.isFingerprintEnabled(this) &&
            !BuildConfig.FINGERPRINT_EMULATOR && !BuildConfig.FINGERPRINT_GUIDE_MODE) startScanWorker()
    }

    override fun onStop() {
        scanActive = false
        scanGeneration++
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(resetReady)
        super.onStop()
    }

    override fun onDestroy() {
        soundManager.release()
        super.onDestroy()
    }

    private fun startScanWorker() {
        if (!CurrentBiometricRuntimePolicy.isFingerprintEnabled(this)) return
        if (BuildConfig.FINGERPRINT_EMULATOR || BuildConfig.FINGERPRINT_GUIDE_MODE ||
            !scanActive || !scanWorkerRunning.compareAndSet(false, true)) return
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
                else handleSuccessfulIdentification(generation, employee)
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

    private fun handleSuccessfulIdentification(generation: Int, employee: EmployeeRecord) {
        showAttendanceResult(generation, employee,
            attendanceService.record(employee.userId, employee.employeeId))
    }

    private fun showAttendanceResult(
        generation: Int,
        employee: EmployeeRecord,
        outcome: AttendanceRecordOutcome,
    ) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        if (outcome.status == AttendanceRecordStatus.DUPLICATE_IGNORED) {
            soundManager.play(AppSoundManager.Event.WARNING)
            render(AttendanceHomeModel(
                AttendanceHomeState.WARNING,
                getString(R.string.attendance_already_recorded),
                getString(R.string.attendance_duplicate_try_again),
                employeeName = employee.displayName,
                employeeId = employee.employeeId,
            ))
            handler.postDelayed(resetReady, SUCCESS_DURATION_MS)
            return@runOnUiThread
        }
        val synced = outcome.status == AttendanceRecordStatus.SYNCED
        soundManager.play(AppSoundManager.Event.SUCCESS)
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
        soundManager.play(if (state == AttendanceHomeState.WARNING) AppSoundManager.Event.WARNING else AppSoundManager.Event.ERROR)
        render(AttendanceHomeModel(state, message, getString(R.string.place_finger_on_sensor)))
        handler.postDelayed(resetReady, SUCCESS_DURATION_MS)
    }

    private fun showTerminalScannerError(generation: Int, message: String) = runOnUiThread {
        if (!isCurrentScan(generation)) return@runOnUiThread
        handler.removeCallbacks(resetReady)
        soundManager.play(AppSoundManager.Event.ERROR)
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
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.attendance_ready_background))
        visual.render(when (model.state) {
            AttendanceHomeState.READY -> FingerprintVisualView.State.READY
            AttendanceHomeState.SCANNING -> FingerprintVisualView.State.SCANNING
            AttendanceHomeState.SUCCESS -> FingerprintVisualView.State.SUCCESS
            AttendanceHomeState.FAILURE -> FingerprintVisualView.State.ERROR
            AttendanceHomeState.WARNING -> FingerprintVisualView.State.WARNING
        })
        val detail = listOfNotNull(model.instruction, model.employeeName, model.employeeId,
            model.attendanceActionLabel, model.attendanceTimeLabel).joinToString("\n")
        val kind = when (model.state) {
            AttendanceHomeState.SUCCESS -> SemanticResultView.Kind.SUCCESS
            AttendanceHomeState.WARNING -> SemanticResultView.Kind.WARNING
            AttendanceHomeState.FAILURE -> SemanticResultView.Kind.ERROR
            else -> SemanticResultView.Kind.INFO
        }
        resultView.show(kind, model.title, detail)
    }

    private fun defaultReadyModel() = AttendanceHomeModel(
        state = AttendanceHomeState.READY,
        title = getString(R.string.ready_to_scan),
        instruction = "Tap Face Recognition to begin",
    )

    private fun configureEmulatorControls() {
        val controls = findViewById<View>(R.id.homeEmulatorControls)
        val emulatorEnabled = BuildConfig.FINGERPRINT_EMULATOR && !BuildConfig.FINGERPRINT_GUIDE_MODE
        controls.visibility = if (emulatorEnabled) View.VISIBLE else View.GONE
        if (!emulatorEnabled) return
        val selector = findViewById<Spinner>(R.id.homeEmulatorFinger)
        val button = findViewById<Button>(R.id.homeEmulatorCapture)
        button.isEnabled = false
        Thread {
            val employees = employeeDirectory.getAll().filter { it.active }
            runOnUiThread {
                emulatorEmployees = employees
                selector.adapter = ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    employees.map { "${it.displayName} (${it.employeeId})" })
                button.isEnabled = employees.isNotEmpty()
            }
        }.apply { name = "emulator-employee-load" }.start()
        button.setOnClickListener {
            val employee = emulatorEmployees.getOrNull(selector.selectedItemPosition) ?: return@setOnClickListener
            val generation = scanGeneration
            Thread { handleSuccessfulIdentification(generation, employee) }
                .apply { name = "emulator-attendance-scan" }.start()
        }
    }

    private fun updateClock() {
        val now = Date()
        timeText.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        dateText.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val SUCCESS_DURATION_MS = 3_500L
    }
}
