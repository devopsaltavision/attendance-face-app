package com.syntaxgenie.hfx05attendance.employee

interface EmployeeDirectory {
    fun getAll(): List<EmployeeRecord>
    fun search(query: String): List<EmployeeRecord>
    fun syncState(): EmployeeSyncState
    fun replaceAll(records: List<EmployeeRecord>, state: EmployeeSyncState)
    fun upsertAll(records: List<EmployeeRecord>, state: EmployeeSyncState)
}
