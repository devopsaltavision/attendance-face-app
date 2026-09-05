package com.syntaxgenie.hfx05attendance.face.frame

import kotlin.math.sqrt

data class FaceFrameDiagnostics(
    val sampledLumaPixels: Int,
    val minimumY: Int,
    val maximumY: Int,
    val averageY: Double,
    val standardDeviationY: Double,
    val nearZeroPercent: Double,
    val nearWhitePercent: Double,
    val averageU: Double?,
    val averageV: Double?,
    val chromaStandardDeviation: Double?,
) {
    val hasLumaVariation: Boolean
        get() = maximumY - minimumY >= MIN_VARIATION_RANGE && standardDeviationY >= MIN_VARIATION_STD_DEV

    companion object {
        private const val MIN_VARIATION_RANGE = 8
        private const val MIN_VARIATION_STD_DEV = 2.0
    }
}

object FaceFrameDiagnosticsCalculator {
    private const val TARGET_LUMA_SAMPLES = 16_384
    private const val TARGET_CHROMA_PAIRS = 4_096
    private const val NEAR_ZERO = 4
    private const val NEAR_WHITE = 251

    fun calculateNv21(data: ByteArray, width: Int, height: Int): FaceFrameDiagnostics {
        val lumaSize = (width.toLong() * height).coerceAtMost(data.size.toLong()).toInt()
        if (lumaSize <= 0) return FaceFrameDiagnostics(0, 0, 0, 0.0, 0.0, 0.0, 0.0, null, null, null)

        val lumaStep = (lumaSize / TARGET_LUMA_SAMPLES).coerceAtLeast(1)
        var count = 0
        var minimum = 255
        var maximum = 0
        var sum = 0.0
        var sumSquares = 0.0
        var nearZero = 0
        var nearWhite = 0
        var index = 0
        while (index < lumaSize) {
            val value = data[index].toInt() and 0xff
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
            sumSquares += value.toDouble() * value
            if (value <= NEAR_ZERO) nearZero++
            if (value >= NEAR_WHITE) nearWhite++
            count++
            index += lumaStep
        }
        val average = sum / count
        val variance = (sumSquares / count - average * average).coerceAtLeast(0.0)

        var chromaCount = 0
        var uSum = 0.0
        var vSum = 0.0
        var chromaSumSquares = 0.0
        val availableChromaBytes = data.size - lumaSize
        val chromaPairStep = ((availableChromaBytes / 2) / TARGET_CHROMA_PAIRS).coerceAtLeast(1) * 2
        index = lumaSize
        while (index + 1 < data.size) {
            val v = data[index].toInt() and 0xff
            val u = data[index + 1].toInt() and 0xff
            vSum += v
            uSum += u
            val centeredV = v - 128.0
            val centeredU = u - 128.0
            chromaSumSquares += centeredV * centeredV + centeredU * centeredU
            chromaCount++
            index += chromaPairStep
        }

        return FaceFrameDiagnostics(
            sampledLumaPixels = count,
            minimumY = minimum,
            maximumY = maximum,
            averageY = average,
            standardDeviationY = sqrt(variance),
            nearZeroPercent = nearZero * 100.0 / count,
            nearWhitePercent = nearWhite * 100.0 / count,
            averageU = if (chromaCount > 0) uSum / chromaCount else null,
            averageV = if (chromaCount > 0) vSum / chromaCount else null,
            chromaStandardDeviation = if (chromaCount > 0) sqrt(chromaSumSquares / (chromaCount * 2.0)) else null,
        )
    }
}

