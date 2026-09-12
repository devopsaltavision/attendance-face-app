package com.syntaxgenie.hfx05attendance.attendance

enum class AttendanceSyncState { PENDING, SYNCED, REJECTED, DEBOUNCED }

data class AttendanceEvent(
    val eventId: String,
    val deviceId: String,
    val userId: String,
    val employeeId: String,
    val deviceTimestamp: String,
    val syncState: AttendanceSyncState = AttendanceSyncState.PENDING,
    val attendanceRecordId: String? = null,
    val attendanceAction: String? = null,
    val serverTimestamp: String? = null,
    val requestedAction: String? = null,
    val biometricType: String? = null,
    val source: String,
    val rejectionReason: String? = null,
) {
    init {
        require(eventId.isNotBlank() && userId.isNotBlank() && employeeId.isNotBlank() && deviceTimestamp.isNotBlank() && source.isNotBlank())
    }
}
