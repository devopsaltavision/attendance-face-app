package com.syntaxgenie.hfx05attendance.face.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceRecognitionThresholdConfigParserTest {
    @Test
    fun calibrationConfigAllowsAbsentThresholds() {
        val config = FaceRecognitionThresholdConfigParser.parse(
            mapOf("mode" to "CALIBRATION", "configVersion" to 1L),
        )

        assertEquals(FaceRecognitionConfigMode.CALIBRATION, config?.mode)
        assertNull(config?.matchThreshold)
    }

    @Test
    fun productionConfigRequiresEveryThreshold() {
        assertNull(
            FaceRecognitionThresholdConfigParser.parse(
                mapOf("mode" to "PRODUCTION", "configVersion" to 1L, "matchThreshold" to 0.7),
            ),
        )
    }

    @Test
    fun validProductionConfigRequiresAndAcceptsDecisionConfig() {
        val config = FaceRecognitionThresholdConfigParser.parse(
            mapOf(
                "mode" to "PRODUCTION",
                "configVersion" to 2L,
                "matchThreshold" to 0.7,
                "minMatchMargin" to 0.2,
                "accuracyLoggingEnabled" to true,
            ),
        )

        assertEquals(FaceRecognitionConfigMode.PRODUCTION, config?.mode)
        assertEquals(0.7, config?.matchThreshold)
        assertEquals(0.2, config?.minMatchMargin)
    }

    @Test
    fun rejectsOutOfRangeAndNonFiniteThresholds() {
        assertNull(
            FaceRecognitionThresholdConfigParser.parse(
                mapOf("mode" to "CALIBRATION", "configVersion" to 1L, "matchThreshold" to -0.01),
            ),
        )
        assertNull(
            FaceRecognitionThresholdConfigParser.parse(
                mapOf("mode" to "CALIBRATION", "configVersion" to 1L, "matchThreshold" to 1.01),
            ),
        )
        assertNull(
            FaceRecognitionThresholdConfigParser.parse(
                mapOf("mode" to "CALIBRATION", "configVersion" to 1L, "matchThreshold" to Double.NaN),
            ),
        )
        assertNull(
            FaceRecognitionThresholdConfigParser.parse(
                mapOf("mode" to "CALIBRATION", "configVersion" to 1L, "matchThreshold" to Double.POSITIVE_INFINITY),
            ),
        )
    }

    @Test
    fun rejectsNonPositiveVersionAndInvalidMode() {
        assertNull(FaceRecognitionThresholdConfigParser.parse(mapOf("mode" to "CALIBRATION", "configVersion" to 0L)))
        assertNull(FaceRecognitionThresholdConfigParser.parse(mapOf("mode" to "CALIBRATION", "configVersion" to -1L)))
        assertNull(FaceRecognitionThresholdConfigParser.parse(mapOf("mode" to "OTHER", "configVersion" to 1L)))
    }
}
