package com.syntaxgenie.hfx05attendance.attendance

enum class AttendanceSyncState { PENDING, SYNCED }

data class AttendanceEvent(
    val eventId: String,
    val userId: String,
    val employeeId: String,
    val deviceTimestamp: String,
    val syncState: AttendanceSyncState = AttendanceSyncState.PENDING,
    val attendanceRecordId: String? = null,
    val attendanceAction: String? = null,
    val serverTimestamp: String? = null,
) {
    init {
        require(eventId.isNotBlank() && userId.isNotBlank() && employeeId.isNotBlank() && deviceTimestamp.isNotBlank())
    }
}
