package com.syntaxgenie.hfx05attendance.attendance

enum class AttendanceHomeState { READY, SCANNING, SUCCESS, FAILURE, WARNING }

data class AttendanceHomeModel(
    val state: AttendanceHomeState = AttendanceHomeState.READY,
    val title: String,
    val instruction: String? = null,
    val employeeName: String? = null,
    val employeeId: String? = null,
    val attendanceActionLabel: String? = null,
    val attendanceTimeLabel: String? = null,
)
