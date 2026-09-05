package com.syntaxgenie.hfx05attendance.face.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Raised without sensitive payload details when local template protection cannot be used safely. */
class FaceTemplateProtectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Device-local face template protection backed by Android Keystore.  Its key deliberately cannot
 * be used as a portable backup key.  A missing key is only created while protecting new data;
 * attempting to read existing data without its original key fails closed.
 */
class AndroidKeystoreFaceTemplateProtector : FaceTemplateProtector {
    override fun protect(plaintextTemplate: ByteArray): ByteArray = try {
        AesGcmFaceTemplateEnvelope.encrypt(getOrCreateKey(), plaintextTemplate)
    } catch (error: FaceTemplateProtectionException) {
        throw error
    } catch (error: Exception) {
        throw FaceTemplateProtectionException("Unable to protect face template.", error)
    }

    override fun unprotect(protectedTemplate: ByteArray): ByteArray = try {
        AesGcmFaceTemplateEnvelope.decrypt(existingKey(), protectedTemplate)
    } catch (error: FaceTemplateProtectionException) {
        throw error
    } catch (error: Exception) {
        throw FaceTemplateProtectionException("Unable to unprotect face template.", error)
    }

    private fun getOrCreateKey(): SecretKey {
        keyStore().getKey(KEY_ALIAS, null)?.let { return it as? SecretKey
            ?: throw FaceTemplateProtectionException("Face template key has an unsupported type.") }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // The envelope generates and stores a fresh random IV per record.  Android
                // Keystore otherwise rejects the caller-supplied IV on some API 30 devices.
                .setRandomizedEncryptionRequired(false)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun existingKey(): SecretKey = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        ?: throw FaceTemplateProtectionException(
            "Face template protection key is unavailable; existing encrypted templates cannot be read.",
        )

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.syntaxgenie.hfx05attendance.face.template.local.v1"
    }
}

/** Internal, strict versioned envelope shared by the Android implementation and focused JVM tests. */
internal object AesGcmFaceTemplateEnvelope {
    private val magic = byteArrayOf('F'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte())
    private const val version: Byte = 1
    private const val ivLength = 12
    private const val tagLength = 16
    private const val headerLength = 4 + 1 + 1 + 4
    private const val maxPayloadLength = 16 * 1024 * 1024
    private val aad = "hfx05-face-template-envelope-v1".toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "Face template must not be empty." }
        val iv = ByteArray(ivLength).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Some API 30 Keystore providers reject a caller-supplied IV for keys generated
        // with randomized-encryption-required (the original key may predate our policy).
        // Let that provider generate the IV and retain cipher.iv in the envelope.
        val actualIv = try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(tagLength * 8, iv)); iv
        } catch (_: java.security.InvalidAlgorithmParameterException) {
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.iv
        }
        val ciphertext = cipher.run {
            updateAAD(aad)
            doFinal(plaintext)
        }
        return ByteBuffer.allocate(headerLength + actualIv.size + ciphertext.size)
            .put(magic).put(version).put(actualIv.size.toByte()).putInt(ciphertext.size).put(actualIv).put(ciphertext).array()
    }

    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(envelope)
        if (envelope.size < headerLength + ivLength + tagLength || ByteArray(4).also(buffer::get).contentEquals(magic).not()) {
            throw FaceTemplateProtectionException("Malformed protected face template envelope.")
        }
        if (buffer.get() != version) throw FaceTemplateProtectionException("Unsupported face template envelope version.")
        val encodedIvLength = buffer.get().toInt() and 0xff
        val ciphertextLength = buffer.int
        if (encodedIvLength != ivLength || ciphertextLength < tagLength || ciphertextLength > maxPayloadLength ||
            buffer.remaining() != encodedIvLength + ciphertextLength) {
            throw FaceTemplateProtectionException("Malformed protected face template envelope.")
        }
        val iv = ByteArray(encodedIvLength).also(buffer::get)
        val ciphertext = ByteArray(ciphertextLength).also(buffer::get)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(tagLength * 8, iv))
                updateAAD(aad)
                doFinal(ciphertext)
            }
        } catch (error: Exception) {
            throw FaceTemplateProtectionException("Protected face template authentication failed.", error)
        }
    }
}
