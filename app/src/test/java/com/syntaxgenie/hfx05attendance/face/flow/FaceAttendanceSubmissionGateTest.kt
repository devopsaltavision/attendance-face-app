package com.syntaxgenie.hfx05attendance.face.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAttendanceSubmissionGateTest {
    @Test fun onlyOneFaceAttendanceSubmissionCanBeInFlight() {
        val gate = FaceAttendanceSubmissionGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
