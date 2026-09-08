package com.syntaxgenie.hfx05attendance.face.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Encrypted, app-private review thumbnails. They are never used for recognition or backup. */
class FaceRegistrationPhotoStore(context: Context) {
    private val directory = File(context.filesDir, "face-registration-review-v1")
    private val protector = AndroidKeystoreFaceTemplateProtector()

    fun save(employeeId: String, thumbnails: List<Bitmap>) {
        require(thumbnails.size == SLOT_NAMES.size)
        directory.mkdirs()
        thumbnails.forEachIndexed { index, bitmap ->
            val target = file(employeeId, index)
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeBytes(protector.protect(encodeThumbnail(bitmap)))
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw IllegalStateException("Unable to save face registration photo.")
            }
        }
    }

    fun load(employeeId: String): List<Bitmap>? = runCatching {
        SLOT_NAMES.indices.map { index ->
            val bytes = protector.unprotect(file(employeeId, index).readBytes())
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Invalid face registration photo.")
        }
    }.getOrNull()

    fun hasPhotos(employeeId: String): Boolean = SLOT_NAMES.indices.all { file(employeeId, it).isFile }

    fun delete(employeeId: String) { SLOT_NAMES.indices.forEach { file(employeeId, it).delete() } }

    private fun encodeThumbnail(bitmap: Bitmap): ByteArray {
        val width = minOf(bitmap.width, MAX_WIDTH)
        val height = (bitmap.height * width.toFloat() / bitmap.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            if (scaled !== bitmap) scaled.recycle()
            output.toByteArray()
        }
    }

    private fun file(employeeId: String, slot: Int): File = File(directory, "${employeeDigest(employeeId)}-${SLOT_NAMES[slot]}.bin")
    private fun employeeDigest(employeeId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(employeeId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_WIDTH = 320
        private const val JPEG_QUALITY = 70
        private val SLOT_NAMES = listOf("straight", "left", "right")
    }
}
