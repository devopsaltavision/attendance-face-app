package com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05

import com.syntaxgenie.hfx05attendance.fingerprint.LowLevelAccessProbe

internal fun interface Hfx05CaptureBridge {
    fun capture(progress: (String) -> Unit): Hfx05NativeCaptureResult
}

internal class Hfx05NativeBridge : Hfx05CaptureBridge {
    override fun capture(progress: (String) -> Unit): Hfx05NativeCaptureResult {
        val result = LowLevelAccessProbe().captureRaw(progress)
        return Hfx05NativeCaptureResult(
            imageReceived = result.imageReceived,
            report = result.report,
            image = result.image,
        )
    }
}
