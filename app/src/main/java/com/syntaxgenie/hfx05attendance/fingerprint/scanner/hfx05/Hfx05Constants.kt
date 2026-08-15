package com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05

internal object Hfx05Constants {
    const val IMAGE_WIDTH = 256
    const val IMAGE_HEIGHT = 360
    const val IMAGE_BYTE_COUNT = IMAGE_WIDTH * IMAGE_HEIGHT
    const val IMAGE_DPI = 500
    const val GPIO_DEVICE_PATH = "/dev/mtgpio"
    const val SPI_DEVICE_PATH = "/dev/spidev3.0"

    val SUPPORTED_MODELS = setOf("X05", "HF-X05")
}
