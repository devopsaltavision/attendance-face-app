package com.syntaxgenie.hfx05attendance.face.repository

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.KeyGenerator

/** JVM tests exercise the exact envelope used by the Android Keystore boundary. */
class AndroidKeystoreFaceTemplateProtectorTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun encryptDecryptRoundTrip() {
        val plaintext = "opaque-template".toByteArray()
        assertArrayEquals(plaintext, AesGcmFaceTemplateEnvelope.decrypt(key, AesGcmFaceTemplateEnvelope.encrypt(key, plaintext)))
    }

    @Test fun randomIvMakesEquivalentPlaintextDifferent() {
        val plaintext = byteArrayOf(1, 2, 3)
        assertFalse(AesGcmFaceTemplateEnvelope.encrypt(key, plaintext).contentEquals(AesGcmFaceTemplateEnvelope.encrypt(key, plaintext)))
    }

    @Test(expected = FaceTemplateProtectionException::class) fun tamperedCiphertextFailsAuthentication() {
        val protected = AesGcmFaceTemplateEnvelope.encrypt(key, byteArrayOf(1, 2, 3))
        protected[protected.lastIndex] = (protected.last().toInt() xor 1).toByte()
        AesGcmFaceTemplateEnvelope.decrypt(key, protected)
    }

    @Test(expected = FaceTemplateProtectionException::class) fun malformedEnvelopeIsRejected() {
        AesGcmFaceTemplateEnvelope.decrypt(key, byteArrayOf(1, 2, 3))
    }

    @Test(expected = FaceTemplateProtectionException::class) fun unsupportedEnvelopeVersionIsRejected() {
        val protected = AesGcmFaceTemplateEnvelope.encrypt(key, byteArrayOf(1))
        protected[4] = 2
        AesGcmFaceTemplateEnvelope.decrypt(key, protected)
    }
}
