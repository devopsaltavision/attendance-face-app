package com.syntaxgenie.hfx05attendance.fingerprint

data class RawCaptureResult(
    val imageReceived: Boolean,
    val report: String,
    val image: ByteArray?,
)

fun interface CaptureProgressListener {
    fun onProgress(message: String)
}
