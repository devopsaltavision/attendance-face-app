package com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05

import android.os.Build
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintScanner
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerError
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerDiagnostics
import java.io.File

internal interface Hfx05Environment {
    val model: String
    val androidVersion: String
    fun deviceExists(path: String): Boolean
}

private object AndroidHfx05Environment : Hfx05Environment {
    override val model: String
        get() = Build.MODEL.orEmpty()
    override val androidVersion: String
        get() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override fun deviceExists(path: String): Boolean = File(path).exists()
}

class Hfx05FingerprintScanner internal constructor(
    private val bridge: Hfx05CaptureBridge,
    private val environment: Hfx05Environment,
) : FingerprintScanner {
    constructor() : this(Hfx05NativeBridge(), AndroidHfx05Environment)

    override val implementationName = "HF-X05 native scanner"
    private val hfx05Diagnostics = Hfx05ScannerDiagnostics(environment, implementationName)
    override val diagnostics: ScannerDiagnostics = hfx05Diagnostics

    override fun isAvailable(): ScannerResult<Boolean> {
        val missingPaths = listOf(Hfx05Constants.GPIO_DEVICE_PATH, Hfx05Constants.SPI_DEVICE_PATH)
            .filterNot(environment::deviceExists)
        return if (missingPaths.isNotEmpty()) {
            val error = ScannerResult.Error(
                error = ScannerError.HARDWARE_UNAVAILABLE,
                diagnosticDetails = buildString {
                    append("HF-X05 device nodes unavailable; model=${environment.model}; ")
                    append("Android=${environment.androidVersion}; missing=${missingPaths.joinToString()}")
                },
            )
            hfx05Diagnostics.recordAvailability(isAvailable = false, error = error)
            error
        } else {
            hfx05Diagnostics.recordAvailability(isAvailable = true)
            ScannerResult.Success(true)
        }
    }

    override fun capture(progress: ((ScannerProgress) -> Unit)?): ScannerResult<FingerprintImage> {
        val availability = isAvailable()
        if (availability is ScannerResult.Error) return availability

        val nativeResult = try {
            bridge.capture { message -> progress?.invoke(message.toScannerProgress()) }
        } catch (error: Throwable) {
            return failure(
                error = ScannerError.UNKNOWN,
                diagnosticDetails = "HF-X05 capture bridge failed: ${error.javaClass.simpleName}: ${error.message}",
                cause = error,
            )
        }

        if (!nativeResult.imageReceived) {
            return failure(
                error = mapNativeFailure(nativeResult.report),
                diagnosticDetails = nativeResult.report,
            )
        }

        val image = nativeResult.image()
            ?: return failure(
                error = ScannerError.INVALID_IMAGE,
                diagnosticDetails = "Native capture reported success without image data.\n${nativeResult.report}",
            )
        if (image.size != Hfx05Constants.IMAGE_BYTE_COUNT) {
            return failure(
                error = ScannerError.INVALID_IMAGE,
                diagnosticDetails = "Expected ${Hfx05Constants.IMAGE_BYTE_COUNT} image bytes but received ${image.size}.\n${nativeResult.report}",
            )
        }

        val fingerprintImage = FingerprintImage(
            pixels = image,
            width = Hfx05Constants.IMAGE_WIDTH,
            height = Hfx05Constants.IMAGE_HEIGHT,
            pixelFormat = PixelFormat.GRAYSCALE_8_BIT,
            dpi = Hfx05Constants.IMAGE_DPI,
        )
        hfx05Diagnostics.recordSuccess(fingerprintImage, nativeResult.report)
        return ScannerResult.Success(fingerprintImage)
    }

    private fun failure(
        error: ScannerError,
        diagnosticDetails: String,
        cause: Throwable? = null,
    ): ScannerResult.Error = ScannerResult.Error(error, diagnosticDetails, cause).also {
        hfx05Diagnostics.recordFailure(it)
    }

    private fun mapNativeFailure(report: String): ScannerError = when {
        report.contains("readiness timeout", ignoreCase = true) -> ScannerError.CAPTURE_TIMEOUT
        report.contains("sensor not recognized", ignoreCase = true) -> ScannerError.SENSOR_NOT_DETECTED
        report.contains("sensor table initialization: FAILED", ignoreCase = true) ->
            ScannerError.SENSOR_INITIALIZATION_FAILED
        else -> ScannerError.CAPTURE_FAILED
    }

    private fun String.toScannerProgress(): ScannerProgress = when (this) {
        "Opening GPIO..." -> ScannerProgress.PREPARING
        "Powering fingerprint sensor..." -> ScannerProgress.POWERING_ON
        "Opening SPI...", "Resetting sensor...", "Checking sensor ID...", "Initializing sensor..." ->
            ScannerProgress.INITIALIZING
        "Place finger on scanner..." -> ScannerProgress.WAITING_FOR_FINGER
        "Capturing fingerprint..." -> ScannerProgress.CAPTURING
        "Cleaning up..." -> ScannerProgress.CLEANING_UP
        else -> ScannerProgress.DIAGNOSTIC(this)
    }
}
