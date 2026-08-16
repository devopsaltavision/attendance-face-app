package com.syntaxgenie.hfx05attendance.employee.local

import com.syntaxgenie.hfx05attendance.employee.EmployeeDirectory
import com.syntaxgenie.hfx05attendance.employee.EmployeeRecord
import com.syntaxgenie.hfx05attendance.employee.EmployeeSyncState

class LocalEmployeeDirectory(private val dao: EmployeeDao) : EmployeeDirectory {
    override fun getAll() = dao.all.map(::toRecord)
    override fun search(query: String) = if (query.isBlank()) getAll() else dao.search(query.trim()).map(::toRecord)
    override fun syncState(): EmployeeSyncState = dao.syncState?.let {
        EmployeeSyncState(it.nextUpdatedAfter, it.lastSuccessfulSyncAt)
    } ?: EmployeeSyncState(null, null)
    override fun replaceAll(records: List<EmployeeRecord>, state: EmployeeSyncState) =
        dao.replaceDirectory(records.map(::toEntity), state.toEntity())
    override fun upsertAll(records: List<EmployeeRecord>, state: EmployeeSyncState) =
        dao.updateDirectory(records.map(::toEntity), state.toEntity())

    private fun toRecord(entity: EmployeeEntity) = EmployeeRecord(entity.userId, entity.employeeId,
        entity.displayName, entity.active, entity.fingerprintEnrolled, entity.fingerprintEnrollmentId, entity.updatedAt)
    private fun toEntity(record: EmployeeRecord) = EmployeeEntity(record.userId, record.employeeId,
        record.displayName, record.active, record.fingerprintEnrolled, record.fingerprintEnrollmentId, record.updatedAt)
    private fun EmployeeSyncState.toEntity() = EmployeeSyncStateEntity("sync", nextUpdatedAfter, lastSuccessfulSyncAt)
}
