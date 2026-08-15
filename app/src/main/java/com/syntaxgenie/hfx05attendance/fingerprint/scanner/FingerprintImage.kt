package com.syntaxgenie.hfx05attendance.fingerprint.scanner

class FingerprintImage(
    pixels: ByteArray,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val dpi: Int? = null,
) {
    private val pixelData = pixels.copyOf()

    init {
        require(width > 0) { "Image width must be positive" }
        require(height > 0) { "Image height must be positive" }
        require(dpi == null || dpi > 0) { "Image DPI must be positive when provided" }
        val expectedByteCount = width.toLong() * height * pixelFormat.bytesPerPixel
        require(expectedByteCount <= Int.MAX_VALUE) { "Image dimensions are too large" }
        require(pixelData.size.toLong() == expectedByteCount) {
            "Pixel data length ${pixelData.size} does not match ${width}x$height ${pixelFormat.name} image"
        }
    }

    val byteCount: Int
        get() = pixelData.size

    fun pixels(): ByteArray = pixelData.copyOf()
}

enum class PixelFormat(val bytesPerPixel: Int) {
    GRAYSCALE_8_BIT(1),
}
