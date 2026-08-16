package com.syntaxgenie.hfx05attendance.fingerprint.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class FingerprintTemplateTest {
    private val metadata = MatcherMetadata("test-engine", "1.2.3", "test-format", 1)

    @Test
    fun metadataAndBytesAreRetainedDefensively() {
        val source = byteArrayOf(1, 2, 3)
        val template = FingerprintTemplate(metadata, source)

        source[0] = 99
        val returned = template.bytes()
        returned[1] = 99

        assertEquals(metadata, template.metadata)
        assertEquals(3, template.byteCount)
        assertFalse(template.bytes()[0] == 99.toByte())
        assertFalse(template.bytes()[1] == 99.toByte())
    }

    @Test
    fun emptyTemplateIsRejected() {
        try {
            FingerprintTemplate(metadata, ByteArray(0))
            fail("Expected empty template to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
