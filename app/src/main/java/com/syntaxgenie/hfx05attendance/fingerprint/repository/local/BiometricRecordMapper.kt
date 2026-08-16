package com.syntaxgenie.hfx05attendance.fingerprint.repository.local

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition

internal object BiometricRecordMapper {
    fun toEntity(record: BiometricRecord): BiometricTemplateEntity {
        val metadata = record.template.metadata
        return BiometricTemplateEntity(
            record.recordId,
            record.enrollmentId,
            record.employeeId,
            record.fingerPosition.persistedValue,
            record.templateSlot,
            metadata.engine,
            metadata.implementationVersion,
            metadata.templateFormat,
            metadata.templateFormatVersion,
            record.template.bytes(),
            record.createdAtEpochMillis,
            record.updatedAtEpochMillis,
        )
    }

    fun metadata(entity: BiometricTemplateEntity): MatcherMetadata = MatcherMetadata(
        engine = entity.matcherEngine,
        implementationVersion = entity.matcherImplementationVersion,
        templateFormat = entity.templateFormat,
        templateFormatVersion = entity.templateFormatVersion,
    )

    fun toDomain(entity: BiometricTemplateEntity): BiometricRecord {
        val fingerPosition = requireNotNull(FingerPosition.fromPersistedValue(entity.fingerPosition)) {
            "Unrecognized persisted finger position"
        }
        return BiometricRecord(
            recordId = entity.id,
            enrollmentId = entity.enrollmentId,
            employeeId = entity.employeeId,
            fingerPosition = fingerPosition,
            templateSlot = entity.templateSlot,
            template = FingerprintTemplate(metadata(entity), entity.templateBytes),
            createdAtEpochMillis = entity.createdAtEpochMillis,
            updatedAtEpochMillis = entity.updatedAtEpochMillis,
        )
    }
}
