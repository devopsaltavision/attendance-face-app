package com.syntaxgenie.hfx05attendance.fingerprint.enrollment

import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentDomainTest {
    @Test
    fun validRequestRetainsEmployeeAndFinger() {
        val request = EnrollmentRequest(" EMP001 ", FingerPosition.LEFT_INDEX)
        assertEquals("EMP001", request.employeeId)
        assertEquals(FingerPosition.LEFT_INDEX, request.fingerPosition)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankEmployeeIsRejected() {
        EnrollmentRequest(" ", FingerPosition.RIGHT_INDEX)
    }

    @Test
    fun errorCodesAreStableUniqueAndSafe() {
        val expected = listOf(
            "ENR-001", "ENR-002", "ENR-003", "ENR-004", "ENR-005", "ENR-006",
            "ENR-007", "ENR-008", "ENR-009", "ENR-010", "ENR-011", "ENR-012", "ENR-999",
        )
        assertEquals(expected, EnrollmentError.entries.map { it.code })
        assertEquals(expected.size, expected.toSet().size)
        EnrollmentError.entries.forEach {
            assertTrue(it.code.matches(Regex("ENR-\\d{3}")))
            assertTrue(it.userMessage.isNotBlank())
        }
    }
}
