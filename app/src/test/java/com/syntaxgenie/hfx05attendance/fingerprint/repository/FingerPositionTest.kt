package com.syntaxgenie.hfx05attendance.fingerprint.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerPositionTest {
    @Test
    fun allExpectedValuesRoundTripThroughStableStrings() {
        assertEquals(10, FingerPosition.entries.size)
        assertEquals(10, FingerPosition.entries.map { it.persistedValue }.toSet().size)
        FingerPosition.entries.forEach { position ->
            assertEquals(position, FingerPosition.fromPersistedValue(position.persistedValue))
            assertTrue(position.persistedValue.matches(Regex("[a-z_]+")))
            assertNotEquals(position.ordinal.toString(), position.persistedValue)
        }
    }

    @Test
    fun unknownPersistedValueIsRejected() {
        assertEquals(null, FingerPosition.fromPersistedValue("11"))
    }
}
