package com.syntaxgenie.hfx05attendance.face.repository.local

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateMetadata
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateProtector

internal class FaceEnrollmentMapper(private val templateProtector: FaceTemplateProtector) {
    fun toEntity(record: FaceEnrollmentRecord): FaceEnrollmentEntity {
        val metadata = record.metadata
        return FaceEnrollmentEntity(
            record.id.value, record.employeeId, templateProtector.protect(record.payloadForProtectedStorage()),
            metadata.engineId, metadata.modelId, metadata.modelVersion, metadata.templateFormatVersion,
            metadata.backupSchemaVersion, metadata.qualityScore, metadata.enrollmentSampleCount,
            record.createdAt, record.updatedAt, record.status.name,
        )
    }

    fun toRecord(entity: FaceEnrollmentEntity): FaceEnrollmentRecord = FaceEnrollmentRecord(
        id = FaceEnrollmentId(entity.enrollmentId),
        employeeId = entity.employeeId,
        templatePayload = templateProtector.unprotect(entity.protectedTemplatePayload),
        metadata = FaceTemplateMetadata(
            entity.engineId, entity.modelId, entity.modelVersion, entity.templateFormatVersion,
            entity.backupSchemaVersion, entity.qualityScore, entity.enrollmentSampleCount,
        ),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        status = FaceEnrollmentStatus.valueOf(entity.status),
    )
}
