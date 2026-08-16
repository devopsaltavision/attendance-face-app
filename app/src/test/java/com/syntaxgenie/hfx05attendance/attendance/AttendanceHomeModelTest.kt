package com.syntaxgenie.hfx05attendance.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceHomeModelTest {
    @Test fun readyStateCarriesPrompt() {
        val model = AttendanceHomeModel(AttendanceHomeState.READY, "READY TO SCAN", "Place your finger")
        assertEquals(AttendanceHomeState.READY, model.state)
        assertNull(model.employeeId)
    }

    @Test fun scanningStateCarriesGuidance() {
        val model = AttendanceHomeModel(AttendanceHomeState.SCANNING, "SCANNING…", "Keep your finger still")
        assertEquals("Keep your finger still", model.instruction)
    }

    @Test fun successAcceptsOptionalFutureAttendanceData() {
        val model = AttendanceHomeModel(AttendanceHomeState.SUCCESS, "Attendance Recorded",
            employeeName = "Employee", employeeId = "EMP001", attendanceActionLabel = "CHECK IN",
            attendanceTimeLabel = "08:03 AM")
        assertEquals("EMP001", model.employeeId)
        assertEquals("CHECK IN", model.attendanceActionLabel)
    }

    @Test fun failureAndWarningRemainPresentationOnly() {
        assertEquals(AttendanceHomeState.FAILURE,
            AttendanceHomeModel(AttendanceHomeState.FAILURE, "Not recognized", "Try again").state)
        assertEquals(AttendanceHomeState.WARNING,
            AttendanceHomeModel(AttendanceHomeState.WARNING, "Identified", "Could not record").state)
    }
}
