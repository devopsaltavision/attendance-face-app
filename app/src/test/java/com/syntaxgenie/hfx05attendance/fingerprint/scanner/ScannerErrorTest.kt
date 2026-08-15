package com.syntaxgenie.hfx05attendance.fingerprint.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerErrorTest {
    @Test
    fun codesAndMessagesArePresentAndCodesAreUnique() {
        val errors = ScannerError.entries

        errors.forEach { error ->
            assertTrue(error.code.matches(Regex("SCN-\\d{3}")))
            assertTrue(error.userMessage.isNotBlank())
        }
        assertEquals(errors.size, errors.map(ScannerError::code).distinct().size)
    }

    @Test
    fun stableErrorCodeMappingIsUnchanged() {
        val expected = mapOf(
            ScannerError.HARDWARE_UNAVAILABLE to "SCN-001",
            ScannerError.DEVICE_ACCESS_FAILED to "SCN-002",
            ScannerError.POWER_CONTROL_FAILED to "SCN-101",
            ScannerError.RESET_FAILED to "SCN-102",
            ScannerError.SENSOR_NOT_DETECTED to "SCN-103",
            ScannerError.SENSOR_INITIALIZATION_FAILED to "SCN-104",
            ScannerError.SPI_COMMUNICATION_FAILED to "SCN-105",
            ScannerError.CAPTURE_FAILED to "SCN-201",
            ScannerError.CAPTURE_TIMEOUT to "SCN-202",
            ScannerError.INVALID_IMAGE to "SCN-204",
            ScannerError.CLEANUP_FAILED to "SCN-205",
            ScannerError.UNKNOWN to "SCN-999",
        )

        assertEquals(expected, ScannerError.entries.associateWith(ScannerError::code))
    }
}
