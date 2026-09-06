package com.syntaxgenie.hfx05attendance.backup

import android.util.Base64
import com.syntaxgenie.hfx05attendance.BuildConfig
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Portable AES-GCM protection shared by device-recovery files; never uses Android Keystore. */
object PortableBackupCrypto {
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun configuredKey(): ByteArray = try {
        Base64.decode(BuildConfig.FINGERPRINT_BACKUP_KEY_BASE64.trim(), Base64.DEFAULT).also {
            require(it.size == 32) { "Portable backup encryption is not configured." }
        }
    } catch (error: Exception) { throw IllegalStateException("Portable backup encryption is not configured.", error) }

    fun encrypt(plaintext: ByteArray, key: ByteArray, magic: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return ByteBuffer.allocate(magic.size + 2 + iv.size + cipher.getOutputSize(plaintext.size))
            .put(magic).put(VERSION).put(iv.size.toByte()).put(iv).put(cipher.doFinal(plaintext)).array()
    }

    fun decrypt(envelope: ByteArray, key: ByteArray, magic: ByteArray): ByteArray = try {
        require(envelope.size >= magic.size + 2 + IV_BYTES + 16)
        val input = ByteBuffer.wrap(envelope)
        require(ByteArray(magic.size).also(input::get).contentEquals(magic) && input.get() == VERSION)
        val ivSize = input.get().toInt() and 0xff
        require(ivSize == IV_BYTES && input.remaining() > ivSize + 16)
        val iv = ByteArray(ivSize).also(input::get)
        val ciphertext = ByteArray(input.remaining()).also(input::get)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(ciphertext)
        }
    } catch (error: Exception) { throw IllegalArgumentException("Portable backup authentication failed.", error) }
}
