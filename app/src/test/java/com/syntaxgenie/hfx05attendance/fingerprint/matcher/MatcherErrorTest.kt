package com.syntaxgenie.hfx05attendance.fingerprint.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherErrorTest {
    @Test
    fun codesAndMessagesArePresentAndCodesAreUnique() {
        val errors = MatcherError.entries

        errors.forEach { error ->
            assertTrue(error.code.matches(Regex("MCH-\\d{3}")))
            assertTrue(error.userMessage.isNotBlank())
        }
        assertEquals(errors.size, errors.map(MatcherError::code).distinct().size)
    }

    @Test
    fun stableErrorCodeMappingIsUnchanged() {
        val expected = mapOf(
            MatcherError.MATCHER_UNAVAILABLE to "MCH-001",
            MatcherError.INVALID_IMAGE to "MCH-002",
            MatcherError.TEMPLATE_EXTRACTION_FAILED to "MCH-003",
            MatcherError.INVALID_TEMPLATE to "MCH-004",
            MatcherError.TEMPLATE_SERIALIZATION_FAILED to "MCH-005",
            MatcherError.TEMPLATE_DESERIALIZATION_FAILED to "MCH-006",
            MatcherError.COMPARISON_FAILED to "MCH-101",
            MatcherError.INCOMPATIBLE_TEMPLATES to "MCH-102",
            MatcherError.UNKNOWN to "MCH-999",
        )

        assertEquals(expected, MatcherError.entries.associateWith(MatcherError::code))
    }
}
