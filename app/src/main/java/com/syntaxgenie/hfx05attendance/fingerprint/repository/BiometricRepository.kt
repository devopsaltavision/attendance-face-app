package com.syntaxgenie.hfx05attendance.fingerprint.repository

interface BiometricRepository {
    fun save(record: BiometricRecord): RepositoryResult<BiometricRecord>

    fun saveEnrollment(records: List<BiometricRecord>): RepositoryResult<List<BiometricRecord>>

    fun getByEmployee(employeeId: String): RepositoryResult<List<BiometricRecord>>

    fun getByEmployeeAndFinger(
        employeeId: String,
        fingerPosition: FingerPosition,
    ): RepositoryResult<List<BiometricRecord>>

    fun getAll(): RepositoryResult<List<BiometricRecord>>

    fun deleteByEmployeeAndFinger(
        employeeId: String,
        fingerPosition: FingerPosition,
    ): RepositoryResult<Int>

    fun deleteByEmployee(employeeId: String): RepositoryResult<Int>

    fun diagnostics(): BiometricRepositorySnapshot
}
