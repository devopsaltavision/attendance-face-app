package com.syntaxgenie.hfx05attendance.fingerprint.repository

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BiometricRecordTest {
    private val metadata = MatcherMetadata("engine", "1.2.3", "format", 1)

    @Test
    fun validRecordPreservesOwnershipAndMatcherMetadata() {
        val source = byteArrayOf(1, 2, 3)
        val record = BiometricRecord(
            "record-1", "enrollment-1", "EMP001", FingerPosition.RIGHT_INDEX, 5,
            FingerprintTemplate(metadata, source), 100, 101,
        )
        source[0] = 99

        assertEquals("EMP001", record.employeeId)
        assertEquals("enrollment-1", record.enrollmentId)
        assertEquals(FingerPosition.RIGHT_INDEX, record.fingerPosition)
        assertEquals(5, record.templateSlot)
        assertEquals(metadata, record.template.metadata)
        assertArrayEquals(byteArrayOf(1, 2, 3), record.template.bytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankEmployeeIdIsInvalid() {
        record(employeeId = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankEnrollmentIdIsInvalid() {
        record(enrollmentId = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun templateSlotZeroIsInvalid() {
        record(slot = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun templateSlotAboveFiveIsInvalid() {
        record(slot = 6)
    }

    private fun record(
        enrollmentId: String = "enrollment-1",
        employeeId: String = "EMP001",
        slot: Int = 1,
    ) = BiometricRecord(
        "record-1", enrollmentId, employeeId, FingerPosition.RIGHT_INDEX, slot,
        FingerprintTemplate(metadata, byteArrayOf(1)), 100,
    )
}
