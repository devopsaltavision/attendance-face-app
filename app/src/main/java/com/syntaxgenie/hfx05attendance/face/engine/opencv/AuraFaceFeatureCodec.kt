package com.syntaxgenie.hfx05attendance.face.engine.opencv

import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AuraFaceFeatureCodec {
    const val FORMAT_ID = AuraFaceModelConfiguration.TEMPLATE_FORMAT_VERSION
    fun encode(values: FloatArray): ByteArray {
        require(values.size == AuraFaceModelConfiguration.EMBEDDING_SIZE)
        require(values.all { it.isFinite() })
        return ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()
    }
    fun encode(mat: Mat): ByteArray {
        require(mat.type() == CvType.CV_32FC1)
        val values = FloatArray((mat.total() * mat.channels()).toInt())
        mat.get(0, 0, values)
        return encode(values)
    }
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size == AuraFaceModelConfiguration.EMBEDDING_SIZE * 4)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(AuraFaceModelConfiguration.EMBEDDING_SIZE) { b.float }.also { require(it.all(Float::isFinite)) }
    }
}
