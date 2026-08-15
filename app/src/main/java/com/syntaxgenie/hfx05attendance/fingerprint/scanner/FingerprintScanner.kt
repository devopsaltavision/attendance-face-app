package com.syntaxgenie.hfx05attendance.fingerprint.scanner

import com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics.ScannerDiagnostics

interface FingerprintScanner {
    val implementationName: String
    val diagnostics: ScannerDiagnostics

    fun isAvailable(): ScannerResult<Boolean>

    fun capture(progress: ((ScannerProgress) -> Unit)? = null): ScannerResult<FingerprintImage>
}
