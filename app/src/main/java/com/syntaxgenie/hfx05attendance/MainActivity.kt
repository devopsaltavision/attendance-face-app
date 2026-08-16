package com.syntaxgenie.hfx05attendance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.fingerprint.LowLevelAccessProbe
import com.syntaxgenie.hfx05attendance.fingerprint.RawCaptureStorage
import com.syntaxgenie.hfx05attendance.fingerprint.X05HardwareProbe
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
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.CaptureImageStatisticsCalculator
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05.Hfx05FingerprintScanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var reportText: TextView
    private lateinit var runButton: Button
    private lateinit var activeButton: Button
    private lateinit var copyButton: Button
    private lateinit var captureButton: Button
    private lateinit var scannerLayerCaptureButton: Button
    private lateinit var viewCaptureButton: Button
    private lateinit var capturePreview: ImageView
    private lateinit var enrollmentEmployeeId: EditText
    private lateinit var enrollmentFingerPosition: Spinner
    private lateinit var enrollmentProgressText: TextView
    private lateinit var startEnrollmentButton: Button
    private lateinit var captureEnrollmentButton: Button
    private lateinit var cancelEnrollmentButton: Button
    private var report = "No diagnostic report has been collected yet."
    private var running = false
    private val fingerprintScanner by lazy { Hfx05FingerprintScanner() }
    private val fingerprintMatcher by lazy { SourceAfisFingerprintMatcher() }
    private val biometricDatabaseDelegate = lazy { BiometricDatabase.create(applicationContext) }
    private val biometricDatabase by biometricDatabaseDelegate
    private val biometricRepository by lazy {
        LocalBiometricRepository(biometricDatabase.biometricTemplateDao(), fingerprintMatcher.metadata)
    }
    private val enrollmentServiceDelegate = lazy {
        FingerprintEnrollmentService(fingerprintScanner, fingerprintMatcher, biometricRepository)
    }
    private val enrollmentService by enrollmentServiceDelegate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        reportText = findViewById(R.id.diagnosticText)
        runButton = findViewById(R.id.runFullDiagnosticsButton)
        activeButton = findViewById(R.id.activeSensorProbeButton)
        copyButton = findViewById(R.id.copyDiagnosticsButton)
        captureButton = findViewById(R.id.realCaptureButton)
        scannerLayerCaptureButton = findViewById(R.id.scannerLayerCaptureButton)
        viewCaptureButton = findViewById(R.id.viewLastCaptureButton)
        capturePreview = findViewById(R.id.capturePreview)
        enrollmentEmployeeId = findViewById(R.id.enrollmentEmployeeId)
        enrollmentFingerPosition = findViewById(R.id.enrollmentFingerPosition)
        enrollmentProgressText = findViewById(R.id.enrollmentProgressText)
        startEnrollmentButton = findViewById(R.id.startEnrollmentButton)
        captureEnrollmentButton = findViewById(R.id.captureEnrollmentButton)
        cancelEnrollmentButton = findViewById(R.id.cancelEnrollmentButton)
        enrollmentFingerPosition.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            FingerPosition.entries.map(FingerPosition::name),
        )

        val saved = File(filesDir, REPORT_FILE).takeIf(File::isFile)?.readText()
        if (saved != null) {
            report = saved
            reportText.text = saved
            copyButton.isEnabled = true
        }
        statusText.setText(R.string.diagnostics_ready)
        runButton.setOnClickListener { runFullDiagnostics() }
        activeButton.setOnClickListener { confirmActiveProbe() }
        copyButton.setOnClickListener { copyReport() }
        captureButton.setOnClickListener { confirmCaptureTest() }
        scannerLayerCaptureButton.setOnClickListener { confirmScannerLayerCapture() }
        viewCaptureButton.setOnClickListener { showLastCapture() }
        startEnrollmentButton.setOnClickListener { startEnrollment() }
        captureEnrollmentButton.setOnClickListener { captureEnrollmentNext() }
        cancelEnrollmentButton.setOnClickListener { cancelEnrollment() }
        viewCaptureButton.isEnabled = captureRawFile().length() == CAPTURE_BYTES.toLong()
    }

    private fun runFullDiagnostics() {
        if (running) return
        setRunning(true)
        Thread {
            val result = runCatching {
                X05HardwareProbe(applicationContext).inspect { progress -> showProgress(progress) }
                    .asText("Capture intentionally not implemented in this diagnostic APK")
            }.getOrElse { error ->
                "HF-X05 Hardware Diagnostic Report\nCollection failed: ${error.javaClass.simpleName}: ${error.message}"
            }
            File(filesDir, REPORT_FILE).writeText(result)
            runOnUiThread {
                report = result
                reportText.text = result
                statusText.text = "Finished."
                copyButton.isEnabled = true
                setRunning(false)
            }
        }.apply { name = "hfx05-full-diagnostics" }.start()
    }

    private fun showProgress(message: String) = runOnUiThread { statusText.text = message }

    private fun confirmActiveProbe() {
        if (!isX05()) {
            statusText.setText(R.string.active_probe_wrong_device)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.active_probe_title)
            .setMessage(R.string.active_probe_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.run_sensor_probe) { _, _ -> runActiveProbe() }
            .show()
    }

    private fun runActiveProbe() {
        if (running) return
        setRunning(true)
        statusText.setText(R.string.active_probe_running)
        Thread {
            val section = runCatching { formatActiveResult(LowLevelAccessProbe().readSensorId()) }
                .getOrElse { "ACTIVE SENSOR PROBE\nFAILED: ${it.javaClass.simpleName}: ${it.message}" }
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
            val combined = "$report\n\n=== LIVE ACTIVE SENSOR PROBE ===\nTimestamp: $timestamp\n$section"
            File(filesDir, REPORT_FILE).writeText(combined)
            runOnUiThread {
                report = combined
                reportText.text = combined
                statusText.text = "Sensor probe finished."
                copyButton.isEnabled = true
                setRunning(false)
            }
        }.apply { name = "hfx05-active-id-probe" }.start()
    }

    private fun formatActiveResult(result: LowLevelAccessProbe.ActiveProbeResult): String = buildString {
        appendLine("SPI open O_RDWR: ${if (result.spi.openSuccess) "SUCCESS" else "FAILED (${errno(result.spi.openErrno)})"}")
        appendLine("Mode: ${if (result.spi.openSuccess) result.spi.mode.describe() else "NOT ATTEMPTED"}")
        appendLine("Bits per word: ${if (result.spi.openSuccess) result.spi.bitsPerWord.describe() else "NOT ATTEMPTED"}")
        appendLine("Max speed Hz: ${if (result.spi.openSuccess) result.spi.maxSpeedHz.describe() else "NOT ATTEMPTED"}")
        appendLine("LSB first: ${if (result.spi.openSuccess) result.spi.lsbFirst.describe() else "NOT ATTEMPTED"}")
        appendLine("TX: 11 00 FE")
        appendLine("ioctl return value: ${result.ioctlReturn}")
        appendLine("Transfer: ${if (result.transferSuccess) "SUCCESS" else "NOT PERFORMED/FAILED (${errno(result.transferErrno)})"}")
        if (result.transferSuccess) {
            val formatted = result.rx.map(::hexByte)
            appendLine("RX: ${formatted.joinToString(" ")}")
            formatted.forEachIndexed { index, value -> appendLine("RX[$index]: $value") }
            val sensorId = result.rx[2]
            appendLine(if (sensorId == 0x33 || sensorId == 0x66) {
                "RECOGNIZED EXPECTED SENSOR ID\nFingerprint sensor appears to already be powered/responding."
            } else {
                "UNKNOWN SENSOR RESPONSE\nSensor communication attempted but power state/protocol remains unresolved."
            })
        } else {
            appendLine("RX: unavailable")
            appendLine("No sensor response. Configuration must be mode=0, bits=0/8, and LSB-first=0. The transfer requests 6000000 Hz without changing the persistent default.")
        }
        append("Close: ${if (!result.spi.openSuccess) "NOT ATTEMPTED" else if (result.spi.closeSuccess) "SUCCESS" else "FAILED (${errno(result.spi.closeErrno)})"}")
    }

    private fun setRunning(value: Boolean) {
        running = value
        runButton.isEnabled = !value
        activeButton.isEnabled = !value
        captureButton.isEnabled = !value
        scannerLayerCaptureButton.isEnabled = !value
        refreshEnrollmentControls()
    }

    private fun startEnrollment() {
        if (running) return
        val employeeId = enrollmentEmployeeId.text.toString().trim()
        if (employeeId.isBlank()) {
            enrollmentEmployeeId.error = getString(R.string.employee_id_required)
            return
        }
        val finger = FingerPosition.entries[enrollmentFingerPosition.selectedItemPosition]
        setRunning(true)
        Thread {
            val result = enrollmentService.start(EnrollmentRequest(employeeId, finger))
            runOnUiThread {
                renderEnrollmentResult(result)
                setRunning(false)
            }
        }.apply { name = "fingerprint-enrollment-start" }.start()
    }

    private fun captureEnrollmentNext() {
        if (running) return
        setRunning(true)
        Thread {
            val result = if (enrollmentService.currentSession()?.state == EnrollmentState.FAILED) {
                enrollmentService.retrySave()
            } else {
                enrollmentService.captureNext { progress ->
                    runOnUiThread { enrollmentProgressText.text = enrollmentProgressMessage(progress) }
                }
            }
            runOnUiThread {
                renderEnrollmentResult(result)
                setRunning(false)
            }
        }.apply { name = "fingerprint-enrollment-step" }.start()
    }

    private fun cancelEnrollment() {
        if (running) return
        renderEnrollmentResult(enrollmentService.cancel())
        refreshEnrollmentControls()
    }

    private fun renderEnrollmentResult(result: EnrollmentResult<*>) {
        when (result) {
            is EnrollmentResult.Success -> {
                val session = enrollmentService.currentSession()
                enrollmentProgressText.text = when (session?.state) {
                    EnrollmentState.COMPLETED -> buildString {
                        appendLine("Enrollment complete")
                        appendLine("Enrollment ID: ${session.enrollmentId}")
                        appendLine("Employee: ${session.employeeId}")
                        appendLine("Finger: ${session.fingerPosition.name}")
                        append("Templates stored: ${session.completedCaptures}")
                    }
                    EnrollmentState.CANCELLED -> "Enrollment cancelled. No templates stored."
                    else -> "Enrollment progress: ${session?.completedCaptures ?: 0} / 5"
                }
            }
            is EnrollmentResult.Error -> {
                val lower = formatEnrollmentLowerError(result.lowerLayerError)
                enrollmentProgressText.text = buildString {
                    appendLine("${result.error.userMessage}")
                    append("Error code: ${result.error.code}")
                    if (lower != null) append("\n$lower")
                }
                appendCaptureReport(buildString {
                    appendLine("=== ENGINEERING ENROLLMENT ===")
                    appendLine("error: ${result.error.code} ${result.error.name}")
                    appendLine("enrollment ID: ${result.session?.enrollmentId ?: "not started"}")
                    appendLine("employee: ${result.session?.employeeId ?: "not started"}")
                    appendLine("finger: ${result.session?.fingerPosition?.name ?: "not selected"}")
                    appendLine("progress: ${result.session?.completedCaptures ?: 0} / 5")
                    lower?.let { appendLine("lower layer: $it") }
                    result.diagnosticDetails?.let { appendLine("technical details: $it") }
                })
                reportText.text = report
                copyButton.isEnabled = true
            }
        }
        refreshEnrollmentControls()
    }

    private fun formatEnrollmentLowerError(error: EnrollmentLowerLayerError?): String? = when (error) {
        is EnrollmentLowerLayerError.Scanner ->
            "Scanner: ${error.value.error.code} ${error.value.error.userMessage}"
        is EnrollmentLowerLayerError.Matcher ->
            "Matcher: ${error.value.error.code} ${error.value.error.userMessage}"
        is EnrollmentLowerLayerError.Repository ->
            "Repository: ${error.value.error.code} ${error.value.error.userMessage}"
        null -> null
    }

    private fun enrollmentProgressMessage(progress: EnrollmentProgress): String = when (progress.state) {
        EnrollmentState.CAPTURING ->
            "Capture ${progress.captureNumber} of ${progress.requiredCaptures}: ${progress.scannerProgress?.let(::scannerProgressMessage) ?: "starting..."}"
        EnrollmentState.PROCESSING ->
            "Processing capture ${progress.captureNumber} of ${progress.requiredCaptures}..."
        EnrollmentState.SAVING -> "Saving all five templates atomically..."
        else -> "Enrollment progress: ${progress.completedCaptures} / ${progress.requiredCaptures}"
    }

    private fun refreshEnrollmentControls() {
        if (!::startEnrollmentButton.isInitialized) return
        val state = if (enrollmentServiceDelegate.isInitialized()) enrollmentService.currentSession()?.state else null
        startEnrollmentButton.isEnabled = !running && state !in setOf(
            EnrollmentState.READY,
            EnrollmentState.CAPTURING,
            EnrollmentState.PROCESSING,
            EnrollmentState.SAVING,
        )
        captureEnrollmentButton.isEnabled = !running && state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)
        captureEnrollmentButton.setText(
            if (state == EnrollmentState.FAILED) R.string.retry_enrollment_save else R.string.capture_next,
        )
        cancelEnrollmentButton.isEnabled = !running && state in setOf(EnrollmentState.READY, EnrollmentState.FAILED)
        enrollmentEmployeeId.isEnabled = startEnrollmentButton.isEnabled
        enrollmentFingerPosition.isEnabled = startEnrollmentButton.isEnabled
    }

    private fun confirmCaptureTest() {
        if (!isX05()) {
            statusText.setText(R.string.capture_hardware_unavailable)
            return
        }
        if (!File("/dev/mtgpio").exists() || !File("/dev/spidev3.0").exists()) {
            statusText.setText(R.string.capture_nodes_missing)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.real_capture_test)
            .setMessage(R.string.capture_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.run_capture_test) { _, _ -> runCaptureTest() }
            .show()
    }

    private fun runCaptureTest() {
        if (running) return
        setRunning(true)
        Thread {
            val nativeResult = runCatching {
                LowLevelAccessProbe().captureRaw { message -> showProgress(message) }
            }.getOrNull()
            var section = nativeResult?.report
                ?: "=== REAL FINGERPRINT CAPTURE TEST ===\nFAILED before native result was returned"
            var success = false
            nativeResult?.image?.takeIf { nativeResult.imageReceived }?.let { image ->
                showProgress("Saving image...")
                val stats = CaptureImageStatisticsCalculator.calculate(image)
                section += "\nminimum pixel: ${stats.minimum}\nmaximum pixel: ${stats.maximum}\n" +
                    "average pixel: ${"%.2f".format(Locale.US, stats.average)}\nunique values: ${stats.unique}\n"
                if (stats.usable) {
                    val saved = RawCaptureStorage(applicationContext).save(image, CAPTURE_WIDTH, CAPTURE_HEIGHT)
                    section += "raw file saved: ${saved.raw.absolutePath}\nBMP saved: ${saved.bmp.absolutePath}\nCAPTURE SUCCESS\n"
                    success = true
                } else {
                    section += "raw file saved: no\nBMP saved: no\nRAW CAPTURE BLOCKED: frame is all-zero, all-FF, or nearly constant\n"
                }
            }
            appendCaptureReport(section)
            runOnUiThread {
                statusText.text = if (success) "CAPTURE SUCCESS" else "Capture test finished; review report."
                reportText.text = report
                copyButton.isEnabled = true
                viewCaptureButton.isEnabled = success
                setRunning(false)
            }
        }.apply { name = "hfx05-real-raw-capture" }.start()
    }

    private fun confirmScannerLayerCapture() {
        AlertDialog.Builder(this)
            .setTitle(R.string.scanner_layer_capture_title)
            .setMessage(R.string.capture_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.run_capture_test) { _, _ -> runScannerLayerCapture() }
            .show()
    }

    private fun runScannerLayerCapture() {
        if (running) return
        setRunning(true)
        Thread {
            val result = fingerprintScanner.capture { progress ->
                showProgress(scannerProgressMessage(progress))
            }
            runCatching {
                when (result) {
                    is ScannerResult.Success -> handleScannerCaptureSuccess(result.value)
                    is ScannerResult.Error -> handleScannerCaptureError(result)
                }
            }.onFailure { error ->
                runOnUiThread {
                    statusText.text = "Scanner diagnostic processing failed: ${error.javaClass.simpleName}"
                    setRunning(false)
                }
            }
        }.apply { name = "hfx05-scanner-layer-capture" }.start()
    }

    private fun handleScannerCaptureSuccess(image: FingerprintImage) {
        val pixels = image.pixels()
        val stats = CaptureImageStatisticsCalculator.calculate(pixels)
        var saved = false
        var section = buildString {
            appendLine("=== SCANNER LAYER CAPTURE ===")
            appendLine("implementation: ${fingerprintScanner.implementationName}")
            appendLine("result: SUCCESS")
            appendLine("image: ${image.width} x ${image.height}")
            appendLine("bytes: ${image.byteCount}")
            appendLine("pixel format: ${image.pixelFormat}")
            appendLine("DPI: ${image.dpi ?: "unknown"}")
            appendLine("minimum pixel: ${stats.minimum}")
            appendLine("maximum pixel: ${stats.maximum}")
            appendLine("average pixel: ${"%.2f".format(Locale.US, stats.average)}")
            appendLine("unique values: ${stats.unique}")
        }
        if (stats.usable) {
            showProgress("Saving image...")
            val capture = RawCaptureStorage(applicationContext).save(pixels, image.width, image.height)
            section += "raw file saved: ${capture.raw.absolutePath}\nBMP saved: ${capture.bmp.absolutePath}\n"
            saved = true
        } else {
            section += "raw file saved: no\nBMP saved: no\nRAW CAPTURE BLOCKED: frame is all-zero, all-FF, or nearly constant\n"
        }
        section += formatScannerDiagnostics()
        appendCaptureReport(section)
        runOnUiThread {
            showCapturePreview(pixels, image.width, image.height)
            statusText.text = if (stats.usable) "SCANNER LAYER CAPTURE SUCCESS" else
                "Scanner returned an unusable image; review diagnostics."
            reportText.text = report
            copyButton.isEnabled = true
            viewCaptureButton.isEnabled = saved
            setRunning(false)
        }
    }

    private fun handleScannerCaptureError(error: ScannerResult.Error) {
        val section = buildString {
            appendLine("=== SCANNER LAYER CAPTURE ===")
            appendLine("result: FAILED")
            appendLine("error code: ${error.error.code}")
            appendLine("error: ${error.error.name}")
            appendLine("message: ${error.error.userMessage}")
            appendLine("technical details:")
            appendLine(error.diagnosticDetails ?: "none")
            append(formatScannerDiagnostics())
        }
        appendCaptureReport(section)
        runOnUiThread {
            statusText.text = "${error.error.userMessage}\nError code: ${error.error.code}"
            reportText.text = report
            copyButton.isEnabled = true
            setRunning(false)
        }
    }

    private fun formatScannerDiagnostics(): String {
        val snapshot = fingerprintScanner.diagnostics.snapshot()
        return buildString {
            appendLine("--- SCANNER DIAGNOSTICS ---")
            appendLine("device/model: ${snapshot.deviceModel}")
            appendLine("Android: ${snapshot.androidVersion}")
            appendLine("implementation: ${snapshot.scannerImplementation}")
            appendLine("available: ${snapshot.scannerAvailable}")
            appendLine("model recognized (diagnostic only): ${snapshot.modelRecognized}")
            appendLine("GPIO node exists: ${snapshot.gpioNodeExists}")
            appendLine("SPI node exists: ${snapshot.spiNodeExists}")
            snapshot.lastCapture?.let { capture ->
                appendLine("last capture successful: ${capture.successful}")
                appendLine("last capture image: ${capture.width} x ${capture.height}, ${capture.byteCount} bytes, ${capture.pixelFormat}, ${capture.dpi} DPI")
            }
            snapshot.lastErrorCode?.let { appendLine("last error: $it ${snapshot.lastErrorMessage}") }
            snapshot.lastTechnicalDetails?.let {
                appendLine("last native/technical report:")
                appendLine(it)
            }
        }
    }

    private fun scannerProgressMessage(progress: ScannerProgress): String = when (progress) {
        ScannerProgress.PREPARING -> "Preparing fingerprint scanner..."
        ScannerProgress.POWERING_ON -> "Powering fingerprint scanner..."
        ScannerProgress.INITIALIZING -> "Initializing fingerprint scanner..."
        ScannerProgress.WAITING_FOR_FINGER -> "Place finger on scanner..."
        ScannerProgress.CAPTURING -> "Capturing fingerprint..."
        ScannerProgress.CLEANING_UP -> "Cleaning up fingerprint scanner..."
        is ScannerProgress.DIAGNOSTIC -> "Fingerprint scanner is working..."
    }

    @Synchronized
    private fun appendCaptureReport(section: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        report = "$report\n\nTimestamp: $timestamp\n$section"
        File(filesDir, REPORT_FILE).writeText(report)
    }

    private fun showLastCapture() {
        val image = captureRawFile().takeIf { it.length() == CAPTURE_BYTES.toLong() }?.readBytes() ?: run {
            statusText.setText(R.string.no_saved_capture)
            return
        }
        showCapturePreview(image, CAPTURE_WIDTH, CAPTURE_HEIGHT)
    }

    private fun showCapturePreview(image: ByteArray, width: Int, height: Int) {
        val pixels = IntArray(image.size) { index ->
            val value = image[index].toInt() and 0xff
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        capturePreview.setImageBitmap(
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
        )
        capturePreview.visibility = ImageView.VISIBLE
    }

    private fun captureRawFile() = File(filesDir, "hfx05_capture/fingerprint.raw")

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("HF-X05 complete diagnostics", report))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun isX05(): Boolean = Build.MODEL.equals("X05", true) || Build.MODEL.equals("HF-X05", true)

    private fun errno(value: Int): String = when (value) {
        0 -> "none (0)"; 1 -> "EPERM (1)"; 2 -> "ENOENT (2)"; 5 -> "EIO (5)"
        13 -> "EACCES (13)"; 19 -> "ENODEV (19)"; 22 -> "EINVAL (22)"; 25 -> "ENOTTY (25)"
        else -> "errno=$value"
    }

    private fun hexByte(value: Int): String = value.toString(16).padStart(2, '0').uppercase(Locale.US)

    private companion object {
        const val REPORT_FILE = "hfx05_diagnostic_report.txt"
        const val CAPTURE_WIDTH = 256
        const val CAPTURE_HEIGHT = 360
        const val CAPTURE_BYTES = CAPTURE_WIDTH * CAPTURE_HEIGHT
    }
}
