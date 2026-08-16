package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress

data class EnrollmentProgress(
    val state: EnrollmentState,
    val captureNumber: Int,
    val completedCaptures: Int,
    val requiredCaptures: Int,
    val scannerProgress: ScannerProgress? = null,
)
