package com.syntaxgenie.hfx05attendance.attendance

interface AttendanceRepository {
    fun insertPending(event: AttendanceEvent): Boolean
    fun get(eventId: String): AttendanceEvent?
    fun pending(limit: Int): List<AttendanceEvent>
    fun markSynced(eventId: String, attendanceRecordId: String?, attendanceAction: String?, serverTimestamp: String)
}
