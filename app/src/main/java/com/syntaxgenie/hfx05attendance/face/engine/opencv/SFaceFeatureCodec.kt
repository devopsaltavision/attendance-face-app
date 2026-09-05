package com.syntaxgenie.hfx05attendance.face.engine.opencv

import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stable, platform-independent SFace template encoding. */
object SFaceFeatureCodec {
    const val FORMAT_ID = "sface-f32le-v1"

    fun encode(values: FloatArray): ByteArray {
        require(values.isNotEmpty())
        require(values.all { it.isFinite() }) { "SFace feature contains non-finite values" }
        return ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()
    }

    fun encode(mat: Mat): ByteArray {
        require(mat.type() == CvType.CV_32FC1) { "SFace feature must be single-channel float32" }
        val values = FloatArray((mat.total() * mat.channels()).toInt())
        mat.get(0, 0, values)
        return encode(values)
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.isNotEmpty() && bytes.size % 4 == 0) { "Malformed SFace feature payload" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }.also { values -> require(values.all { it.isFinite() }) { "SFace feature contains non-finite values" } }
    }
}
