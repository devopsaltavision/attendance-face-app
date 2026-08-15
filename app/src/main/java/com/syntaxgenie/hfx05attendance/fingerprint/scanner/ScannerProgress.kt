package com.syntaxgenie.hfx05attendance.fingerprint.scanner

sealed class ScannerProgress {
    object PREPARING : ScannerProgress()
    object POWERING_ON : ScannerProgress()
    object INITIALIZING : ScannerProgress()
    object WAITING_FOR_FINGER : ScannerProgress()
    object CAPTURING : ScannerProgress()
    object CLEANING_UP : ScannerProgress()
    data class DIAGNOSTIC(val message: String) : ScannerProgress()
}
