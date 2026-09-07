package com.syntaxgenie.hfx05attendance.face.calibration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceRecognitionTelemetryPolicyTest {
    @Test fun disabledCreatesNoTelemetryRequest() = assertFalse(FaceRecognitionTelemetryPolicy.shouldWrite(false))
    @Test fun enabledCreatesTelemetryRequest() = assertTrue(FaceRecognitionTelemetryPolicy.shouldWrite(true))
}
