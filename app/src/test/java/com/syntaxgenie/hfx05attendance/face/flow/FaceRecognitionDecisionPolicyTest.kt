package com.syntaxgenie.hfx05attendance.face.flow

import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionConfigMode
import com.syntaxgenie.hfx05attendance.face.calibration.FaceRecognitionThresholdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceRecognitionDecisionPolicyTest {
    private val config = FaceRecognitionThresholdConfig(FaceRecognitionConfigMode.PRODUCTION, .55, .20, false, null, null, null, 2)
    @Test fun belowThresholdIsUnknown() = assertDecision(.54, .10, FaceRecognitionDecision.UNKNOWN)
    @Test fun thresholdBoundaryMatches() = assertDecision(.55, .20, FaceRecognitionDecision.MATCHED)
    @Test fun marginBoundaryMatches() = assertDecision(.80, .60, FaceRecognitionDecision.MATCHED)
    @Test fun smallMarginIsAmbiguous() = assertDecision(.80, .61, FaceRecognitionDecision.AMBIGUOUS)
    @Test fun oneCandidateMatches() {
        val result = FaceRecognitionDecisionPolicy.decide(listOf(FaceCandidateSeed("E1", .55)), config)
        assertEquals(FaceRecognitionDecision.MATCHED, result.decision); assertEquals("E1", result.employeeId)
    }
    private fun assertDecision(top: Double, second: Double, expected: FaceRecognitionDecision) {
        val result = FaceRecognitionDecisionPolicy.decide(listOf(FaceCandidateSeed("E1", top), FaceCandidateSeed("E2", second)), config)
        assertEquals(expected, result.decision)
        if (expected != FaceRecognitionDecision.MATCHED) assertNull(result.employeeId)
    }
}
