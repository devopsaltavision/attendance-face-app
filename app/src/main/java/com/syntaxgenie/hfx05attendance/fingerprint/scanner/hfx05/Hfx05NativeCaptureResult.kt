package com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05

internal class Hfx05NativeCaptureResult(
    val imageReceived: Boolean,
    val report: String,
    image: ByteArray?,
) {
    private val imageData = image?.copyOf()

    fun image(): ByteArray? = imageData?.copyOf()
}
