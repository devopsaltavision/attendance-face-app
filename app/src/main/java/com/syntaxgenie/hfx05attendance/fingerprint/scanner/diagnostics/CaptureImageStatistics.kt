package com.syntaxgenie.hfx05attendance.fingerprint.scanner.diagnostics

data class CaptureImageStatistics(
    val minimum: Int,
    val maximum: Int,
    val average: Double,
    val unique: Int,
    val usable: Boolean,
)

object CaptureImageStatisticsCalculator {
    fun calculate(image: ByteArray): CaptureImageStatistics {
        require(image.isNotEmpty()) { "Image must not be empty" }
        val counts = IntArray(256)
        var minimum = 255
        var maximum = 0
        var sum = 0L
        image.forEach { byte ->
            val value = byte.toInt() and 0xff
            counts[value]++
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
        }
        val unique = counts.count { it > 0 }
        val dominant = counts.maxOrNull() ?: image.size
        val usable = unique >= 8 && maximum - minimum >= 16 && dominant < image.size * 995 / 1000
        return CaptureImageStatistics(
            minimum = minimum,
            maximum = maximum,
            average = sum.toDouble() / image.size,
            unique = unique,
            usable = usable,
        )
    }
}
