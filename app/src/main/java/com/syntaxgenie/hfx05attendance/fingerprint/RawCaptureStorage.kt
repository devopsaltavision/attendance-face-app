package com.syntaxgenie.hfx05attendance.fingerprint

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawCaptureStorage(private val context: Context) {
    data class SavedCapture(val raw: File, val bmp: File)

    fun save(image: ByteArray, width: Int, height: Int): SavedCapture {
        require(image.size == width * height)
        val directory = File(context.filesDir, "hfx05_capture").apply { mkdirs() }
        val raw = File(directory, "fingerprint.raw")
        val bmp = File(directory, "fingerprint.bmp")
        raw.writeBytes(image)
        bmp.writeBytes(toBmp(image, width, height))
        return SavedCapture(raw, bmp)
    }

    private fun toBmp(image: ByteArray, width: Int, height: Int): ByteArray {
        val rowSize = ((width * 3 + 3) / 4) * 4
        val pixelSize = rowSize * height
        val output = ByteBuffer.allocate(54 + pixelSize).order(ByteOrder.LITTLE_ENDIAN)
        output.put('B'.code.toByte()).put('M'.code.toByte())
        output.putInt(54 + pixelSize).putInt(0).putInt(54)
        output.putInt(40).putInt(width).putInt(height)
        output.putShort(1).putShort(24).putInt(0).putInt(pixelSize)
        output.putInt(500 * 10000 / 254).putInt(500 * 10000 / 254).putInt(0).putInt(0)
        val padding = ByteArray(rowSize - width * 3)
        for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                val value = image[y * width + x]
                output.put(value).put(value).put(value)
            }
            output.put(padding)
        }
        return output.array()
    }
}
