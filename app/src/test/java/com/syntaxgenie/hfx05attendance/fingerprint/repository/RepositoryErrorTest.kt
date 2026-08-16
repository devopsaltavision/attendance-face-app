package com.syntaxgenie.hfx05attendance.fingerprint.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryErrorTest {
    @Test
    fun codesAreStableUniqueAndMessagesSafe() {
        val expected = mapOf(
            RepositoryError.REPOSITORY_UNAVAILABLE to "BIO-001",
            RepositoryError.TEMPLATE_SAVE_FAILED to "BIO-002",
            RepositoryError.TEMPLATE_READ_FAILED to "BIO-003",
            RepositoryError.INVALID_RECORD to "BIO-004",
            RepositoryError.TEMPLATE_DELETE_FAILED to "BIO-005",
            RepositoryError.INCOMPATIBLE_TEMPLATE to "BIO-006",
            RepositoryError.DUPLICATE_TEMPLATE_SLOT to "BIO-007",
            RepositoryError.UNKNOWN to "BIO-999",
        )
        assertEquals(expected, RepositoryError.entries.associateWith { it.code })
        assertEquals(RepositoryError.entries.size, RepositoryError.entries.map { it.code }.toSet().size)
        RepositoryError.entries.forEach {
            assertTrue(it.code.matches(Regex("BIO-\\d{3}")))
            assertTrue(it.userMessage.isNotBlank())
        }
    }
}
