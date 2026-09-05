package com.syntaxgenie.hfx05attendance.face.repository

/** Persistence boundary for opaque biometric templates only; it owns no employee business data. */
interface FaceEnrollmentRepository {
    fun getById(id: FaceEnrollmentId): FaceEnrollmentRecord?
    fun listByEmployee(employeeId: String): List<FaceEnrollmentRecord>
    fun listCompatible(compatibility: FaceTemplateCompatibility): List<FaceEnrollmentRecord>
    fun add(record: FaceEnrollmentRecord)
    fun update(record: FaceEnrollmentRecord): Boolean
    fun delete(id: FaceEnrollmentId): Boolean
    fun deleteAllForEmployee(employeeId: String): Int
    fun replaceAllForEmployee(employeeId: String, records: List<FaceEnrollmentRecord>) {
        deleteAllForEmployee(employeeId)
        records.forEach(::add)
    }
}
