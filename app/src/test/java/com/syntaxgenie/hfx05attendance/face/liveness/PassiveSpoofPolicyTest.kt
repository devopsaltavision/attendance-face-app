package com.syntaxgenie.hfx05attendance.face.liveness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveSpoofPolicyTest {
    @Test fun weakRectangleOnlyIsNotSuspicious() = assertEquals(PassiveSpoofResult.PASS,
        PassiveSpoofPolicy.decide(PassiveSpoofSignals(6, rectangleScore = 0.69)).result)

    @Test fun glareOnlyIsNotSuspicious() = assertEquals(PassiveSpoofResult.PASS,
        PassiveSpoofPolicy.decide(PassiveSpoofSignals(6, glareScore = 0.04)).result)

    @Test fun rectangleWithDisplayTextureIsSuspicious() = assertEquals(PassiveSpoofResult.SUSPICIOUS,
        PassiveSpoofPolicy.decide(PassiveSpoofSignals(6, rectangleScore = 0.8, textureScore = 700.0)).result)

    @Test fun shortWindowIsInsufficient() = assertEquals(PassiveSpoofResult.INSUFFICIENT_DATA,
        PassiveSpoofPolicy.decide(PassiveSpoofSignals(4, rectangleScore = 0.8, glareScore = 0.04)).result)

    @Test fun observeOnlyAlwaysAllowsRecognition() = assertTrue(
        PassiveSpoofPolicy.allowsRecognition(PassiveSpoofMode.OBSERVE_ONLY, PassiveSpoofResult.SUSPICIOUS))
}
