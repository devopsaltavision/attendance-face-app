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
    override fun markRejected(eventId: String, rejectionReason: String?) = dao.markRejected(eventId, rejectionReason)
    override fun markDebounced(eventId: String, reason: String?) = dao.markDebounced(eventId, reason)

    private fun AttendanceEvent.toEntity() = AttendanceEventEntity(eventId, deviceId, userId, employeeId,
        deviceTimestamp, syncState.name, attendanceRecordId, attendanceAction, serverTimestamp, requestedAction,
        biometricType, source, rejectionReason)
    private fun AttendanceEventEntity.toRecord() = AttendanceEvent(eventId, deviceId, userId, employeeId,
        deviceTimestamp, AttendanceSyncState.valueOf(syncState), attendanceRecordId, attendanceAction, serverTimestamp,
        requestedAction, biometricType, source, rejectionReason)
}
