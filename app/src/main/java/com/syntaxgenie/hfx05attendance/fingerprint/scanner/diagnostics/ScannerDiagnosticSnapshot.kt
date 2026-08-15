package com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics

import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat

interface ScannerDiagnostics {
    fun snapshot(): ScannerDiagnosticSnapshot
}

data class ScannerDiagnosticSnapshot(
    val deviceModel: String,
    val androidVersion: String,
    val scannerImplementation: String,
    val scannerAvailable: Boolean?,
    val gpioNodeExists: Boolean,
    val spiNodeExists: Boolean,
    val modelRecognized: Boolean,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastTechnicalDetails: String? = null,
    val lastCapture: ScannerCaptureDiagnostic? = null,
)

data class ScannerCaptureDiagnostic(
    val successful: Boolean,
    val width: Int? = null,
    val height: Int? = null,
    val byteCount: Int? = null,
    val pixelFormat: PixelFormat? = null,
    val dpi: Int? = null,
)
