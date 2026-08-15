package com.syntaxgenie.hfx05attendance.fingerprint.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class FingerprintImageTest {
    @Test
    fun validHfx05ImageRetainsExpectedMetadata() {
        val image = FingerprintImage(
            pixels = ByteArray(256 * 360),
            width = 256,
            height = 360,
            pixelFormat = PixelFormat.GRAYSCALE_8_BIT,
            dpi = 500,
        )

        assertEquals(256, image.width)
        assertEquals(360, image.height)
        assertEquals(256 * 360, image.byteCount)
        assertEquals(PixelFormat.GRAYSCALE_8_BIT, image.pixelFormat)
        assertEquals(500, image.dpi)
    }

    @Test
    fun invalidByteLengthIsRejected() {
        try {
            FingerprintImage(
                pixels = ByteArray(10),
                width = 256,
                height = 360,
                pixelFormat = PixelFormat.GRAYSCALE_8_BIT,
            )
            fail("Expected malformed image to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun inputAndReturnedPixelArraysAreDefensiveCopies() {
        val source = ByteArray(4) { it.toByte() }
        val image = FingerprintImage(source, 2, 2, PixelFormat.GRAYSCALE_8_BIT)

        source[0] = 99
        val returned = image.pixels()
        returned[1] = 99

        assertFalse(image.pixels()[0] == 99.toByte())
        assertFalse(image.pixels()[1] == 99.toByte())
    }
}
