package com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureImageStatisticsTest {
    @Test
    fun allZeroImageIsRejected() {
        assertFalse(CaptureImageStatisticsCalculator.calculate(ByteArray(256 * 360)).usable)
    }

    @Test
    fun allFfImageIsRejected() {
        assertFalse(CaptureImageStatisticsCalculator.calculate(ByteArray(256 * 360) { 0xff.toByte() }).usable)
    }

    @Test
    fun nearlyConstantImageIsRejected() {
        val image = ByteArray(256 * 360)
        repeat(460) { index -> image[index] = ((index % 7) + 1).toByte() }

        assertFalse(CaptureImageStatisticsCalculator.calculate(image).usable)
    }

    @Test
    fun imageWithTooFewUniqueValuesIsRejected() {
        val image = ByteArray(256 * 360) { index -> if (index % 2 == 0) 0 else 0xff.toByte() }

        assertFalse(CaptureImageStatisticsCalculator.calculate(image).usable)
    }

    @Test
    fun representativeGrayscaleImageIsAccepted() {
        val image = ByteArray(256 * 360) { index -> (index % 256).toByte() }

        assertTrue(CaptureImageStatisticsCalculator.calculate(image).usable)
    }
}
