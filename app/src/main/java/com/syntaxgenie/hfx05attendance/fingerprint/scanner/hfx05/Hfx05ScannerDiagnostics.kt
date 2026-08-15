package com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05

import com.syntaxgenie.hfx05attendance.fingerprint.scanner.FingerprintImage
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerCaptureDiagnostic
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerDiagnosticSnapshot
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerDiagnostics
import java.util.Locale

internal class Hfx05ScannerDiagnostics(
    private val environment: Hfx05Environment,
    private val implementationName: String,
) : ScannerDiagnostics {
    private var available: Boolean? = null
    private var lastError: ScannerResult.Error? = null
    private var lastTechnicalDetails: String? = null
    private var lastCapture: ScannerCaptureDiagnostic? = null

    @Synchronized
    fun recordAvailability(isAvailable: Boolean, error: ScannerResult.Error? = null) {
        available = isAvailable
        if (error != null) {
            lastError = error
            lastTechnicalDetails = error.diagnosticDetails
        }
    }

    @Synchronized
    fun recordSuccess(image: FingerprintImage, technicalDetails: String) {
        lastError = null
        lastTechnicalDetails = technicalDetails
        lastCapture = ScannerCaptureDiagnostic(
            successful = true,
            width = image.width,
            height = image.height,
            byteCount = image.byteCount,
            pixelFormat = image.pixelFormat,
            dpi = image.dpi,
        )
    }

    @Synchronized
    fun recordFailure(error: ScannerResult.Error) {
        lastError = error
        lastTechnicalDetails = error.diagnosticDetails
        lastCapture = ScannerCaptureDiagnostic(successful = false)
    }

    @Synchronized
    override fun snapshot(): ScannerDiagnosticSnapshot {
        val gpioExists = environment.deviceExists(Hfx05Constants.GPIO_DEVICE_PATH)
        val spiExists = environment.deviceExists(Hfx05Constants.SPI_DEVICE_PATH)
        return ScannerDiagnosticSnapshot(
            deviceModel = environment.model,
            androidVersion = environment.androidVersion,
            scannerImplementation = implementationName,
            scannerAvailable = available,
            gpioNodeExists = gpioExists,
            spiNodeExists = spiExists,
            modelRecognized = environment.model.uppercase(Locale.US) in Hfx05Constants.SUPPORTED_MODELS,
            lastErrorCode = lastError?.error?.code,
            lastErrorMessage = lastError?.error?.userMessage,
            lastTechnicalDetails = lastTechnicalDetails,
            lastCapture = lastCapture,
        )
    }
}
