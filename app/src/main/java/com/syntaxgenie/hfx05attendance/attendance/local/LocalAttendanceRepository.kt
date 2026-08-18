package com.syntaxgenie.hfx05attendance.attendance.local

import com.syntaxgenie.hfx05attendance.attendance.AttendanceEvent
import com.syntaxgenie.hfx05attendance.attendance.AttendanceRepository
import com.syntaxgenie.hfx05attendance.attendance.AttendanceSyncState
import com.syntaxgenie.hfx05attendance.employee.local.AttendanceDao
import com.syntaxgenie.hfx05attendance.employee.local.AttendanceEventEntity

class LocalAttendanceRepository(private val dao: AttendanceDao) : AttendanceRepository {
    override fun insertPending(event: AttendanceEvent): Boolean = dao.insert(event.toEntity()) != -1L
    override fun get(eventId: String): AttendanceEvent? = dao.get(eventId)?.toRecord()
    override fun pending(limit: Int): List<AttendanceEvent> = dao.pending(limit).map { it.toRecord() }
    override fun markSynced(
        eventId: String,
        attendanceRecordId: String?,
        attendanceAction: String?,
        serverTimestamp: String,
    ) = dao.markSynced(eventId, attendanceRecordId, attendanceAction, serverTimestamp)

    private fun AttendanceEvent.toEntity() = AttendanceEventEntity(eventId, userId, employeeId,
        deviceTimestamp, syncState.name, attendanceRecordId, attendanceAction, serverTimestamp)
    private fun AttendanceEventEntity.toRecord() = AttendanceEvent(eventId, userId, employeeId,
        deviceTimestamp, AttendanceSyncState.valueOf(syncState), attendanceRecordId, attendanceAction, serverTimestamp)
}
