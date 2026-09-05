package com.syntaxgenie.hfx05attendance.face.repository.local

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRepository
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateProtector

/** Room implementation. It deliberately has no default protector or application wiring yet. */
class LocalFaceEnrollmentRepository(
    private val dao: FaceEnrollmentDao,
    templateProtector: FaceTemplateProtector,
) : FaceEnrollmentRepository {
    private val mapper = FaceEnrollmentMapper(templateProtector)

    override fun getById(id: FaceEnrollmentId): FaceEnrollmentRecord? = dao.getById(id.value)?.let(mapper::toRecord)

    override fun listByEmployee(employeeId: String): List<FaceEnrollmentRecord> =
        dao.listByEmployee(employeeId).map(mapper::toRecord)

    override fun listCompatible(compatibility: FaceTemplateCompatibility): List<FaceEnrollmentRecord> =
        dao.listCompatible(
            compatibility.engineId, compatibility.modelId, compatibility.modelVersion,
            compatibility.templateFormatVersion,
        ).map(mapper::toRecord)

    override fun add(record: FaceEnrollmentRecord) = dao.insert(mapper.toEntity(record))

    override fun update(record: FaceEnrollmentRecord): Boolean = dao.update(mapper.toEntity(record)) == 1

    override fun delete(id: FaceEnrollmentId): Boolean = dao.deleteById(id.value) == 1

    override fun deleteAllForEmployee(employeeId: String): Int = dao.deleteAllForEmployee(employeeId)

    override fun replaceAllForEmployee(employeeId: String, records: List<FaceEnrollmentRecord>) =
        dao.replaceAllForEmployee(employeeId, records.map(mapper::toEntity))
}
