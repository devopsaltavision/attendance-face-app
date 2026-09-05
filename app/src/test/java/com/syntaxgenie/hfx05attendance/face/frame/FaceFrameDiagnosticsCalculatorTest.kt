package com.syntaxgenie.hfx05attendance.face.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceFrameDiagnosticsCalculatorTest {
    @Test
    fun constantDarkFrameHasNoVariation() {
        val data = ByteArray(4 * 4 * 3 / 2) { index -> if (index < 16) 0 else 128.toByte() }

        val result = FaceFrameDiagnosticsCalculator.calculateNv21(data, 4, 4)

        assertEquals(0, result.minimumY)
        assertEquals(0, result.maximumY)
        assertEquals(0.0, result.averageY, 0.001)
        assertFalse(result.hasLumaVariation)
        assertEquals(100.0, result.nearZeroPercent, 0.001)
    }

    @Test
    fun variedFrameReportsLumaAndNv21ChromaAggregates() {
        val data = ByteArray(24)
        repeat(16) { data[it] = (it * 16).toByte() }
        repeat(4) { pair ->
            data[16 + pair * 2] = 140.toByte()
            data[17 + pair * 2] = 120.toByte()
        }

        val result = FaceFrameDiagnosticsCalculator.calculateNv21(data, 4, 4)

        assertEquals(0, result.minimumY)
        assertEquals(240, result.maximumY)
        assertTrue(result.hasLumaVariation)
        assertEquals(120.0, result.averageU!!, 0.001)
        assertEquals(140.0, result.averageV!!, 0.001)
    }
}

