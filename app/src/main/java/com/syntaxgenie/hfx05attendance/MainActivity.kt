package com.syntaxgenie.hfx05attendance

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.syntaxgenie.hfx05attendance.fingerprint.LowLevelAccessProbe
import com.syntaxgenie.hfx05attendance.fingerprint.RawCaptureStorage
import com.syntaxgenie.hfx05attendance.fingerprint.X05HardwareProbe
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
    private lateinit var viewCaptureButton: Button
    private lateinit var capturePreview: ImageView
    private var report = "No diagnostic report has been collected yet."
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        reportText = findViewById(R.id.diagnosticText)
        runButton = findViewById(R.id.runFullDiagnosticsButton)
        activeButton = findViewById(R.id.activeSensorProbeButton)
        copyButton = findViewById(R.id.copyDiagnosticsButton)
        captureButton = findViewById(R.id.realCaptureButton)
        viewCaptureButton = findViewById(R.id.viewLastCaptureButton)
        capturePreview = findViewById(R.id.capturePreview)

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
        viewCaptureButton.setOnClickListener { showLastCapture() }
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
                val stats = pixelStats(image)
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

    private data class PixelStats(
        val minimum: Int, val maximum: Int, val average: Double, val unique: Int, val usable: Boolean,
    )

    private fun pixelStats(image: ByteArray): PixelStats {
        val counts = IntArray(256)
        var minimum = 255
        var maximum = 0
        var sum = 0L
        image.forEach { byte ->
            val value = byte.toInt() and 0xff
            counts[value]++
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
        }
        val unique = counts.count { it > 0 }
        val dominant = counts.maxOrNull() ?: image.size
        val usable = unique >= 8 && maximum - minimum >= 16 && dominant < image.size * 995 / 1000
        return PixelStats(minimum, maximum, sum.toDouble() / image.size, unique, usable)
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
        val pixels = IntArray(image.size) { index ->
            val value = image[index].toInt() and 0xff
            0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        capturePreview.setImageBitmap(
            Bitmap.createBitmap(pixels, CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888),
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
