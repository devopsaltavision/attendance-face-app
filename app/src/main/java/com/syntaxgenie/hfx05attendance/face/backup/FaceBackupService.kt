package com.syntaxgenie.hfx05attendance.face.backup

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRepository
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility

class FaceBackupValidator {
    fun validate(manifest: FaceBackupManifest) {
        if (manifest.backupSchemaVersion != FACE_BACKUP_SCHEMA_VERSION ||
            manifest.applicationSchemaId != FACE_BACKUP_APPLICATION_SCHEMA_ID ||
            manifest.recordCount != manifest.records().size ||
            manifest.records().any { it.backupSchemaVersion != FACE_BACKUP_SCHEMA_VERSION }) {
            throw InvalidFaceBackupException("Unsupported face backup schema.")
        }
    }
}

sealed class FaceBackupExportResult {
    data class Exported(val manifest: FaceBackupManifest) : FaceBackupExportResult()
    data object PortableKeyProvisioningRequired : FaceBackupExportResult()
}

class FaceBackupExporter(private val protector: FaceBackupProtector) {
    fun export(records: List<FaceEnrollmentRecord>, createdAt: Long): FaceBackupExportResult = try {
        FaceBackupExportResult.Exported(FaceBackupManifest(FACE_BACKUP_SCHEMA_VERSION, createdAt,
            FACE_BACKUP_APPLICATION_SCHEMA_ID, records.map(::toBackupRecord)))
    } catch (_: PortableBackupKeyProvisioningRequiredException) {
        FaceBackupExportResult.PortableKeyProvisioningRequired
    }

    private fun toBackupRecord(record: FaceEnrollmentRecord) = FaceBackupRecord(record.id, record.employeeId,
        protector.protect(record.templatePayload()), record.metadata.engineId, record.metadata.modelId,
        record.metadata.modelVersion, record.metadata.templateFormatVersion, record.status, record.createdAt,
        record.updatedAt, FACE_BACKUP_SCHEMA_VERSION, record.metadata.qualityScore, record.metadata.enrollmentSampleCount)
}

enum class FaceRestoreOutcome { RESTORED, SKIPPED_DUPLICATE, INCOMPATIBLE_ENGINE, INCOMPATIBLE_MODEL, UNSUPPORTED_TEMPLATE_FORMAT, INVALID_BACKUP, AUTHENTICATION_FAILED }
data class FaceRestoreRecordResult(val enrollmentId: String, val outcome: FaceRestoreOutcome)
data class FaceBackupImportResult(val records: List<FaceRestoreRecordResult>)

/** Validate and decrypt the complete input before any repository insert; existing records are never overwritten. */
class FaceBackupImporter(
    private val repository: FaceEnrollmentRepository,
    private val protector: FaceBackupProtector,
    private val supported: FaceTemplateCompatibility,
    private val validator: FaceBackupValidator = FaceBackupValidator(),
) {
    fun restore(manifest: FaceBackupManifest): FaceBackupImportResult {
        try { validator.validate(manifest) } catch (_: InvalidFaceBackupException) {
            return FaceBackupImportResult(manifest.records().map { FaceRestoreRecordResult(it.enrollmentId.value, FaceRestoreOutcome.INVALID_BACKUP) })
        }
        val planned = manifest.records().map { record -> plan(record) }
        planned.filterIsInstance<PlannedRestore.Ready>().forEach { ready -> repository.add(ready.record) }
        return FaceBackupImportResult(planned.map { it.result })
    }

    private fun plan(record: FaceBackupRecord): PlannedRestore {
        if (repository.getById(record.enrollmentId) != null) return PlannedRestore.Result(record, FaceRestoreOutcome.SKIPPED_DUPLICATE)
        val incompatibility = compatibilityOutcome(record)
        if (incompatibility != null) return PlannedRestore.Result(record, incompatibility)
        val template = try { protector.unprotect(record.protectedBackupTemplatePayload()) } catch (_: PortableBackupKeyProvisioningRequiredException) {
            return PlannedRestore.Result(record, FaceRestoreOutcome.AUTHENTICATION_FAILED)
        } catch (_: Exception) { return PlannedRestore.Result(record, FaceRestoreOutcome.AUTHENTICATION_FAILED) }
        return PlannedRestore.Ready(record, FaceEnrollmentRecord(record.enrollmentId, record.employeeId, template,
            record.metadata(), record.createdAt, record.updatedAt, record.status))
    }

    private fun compatibilityOutcome(record: FaceBackupRecord): FaceRestoreOutcome? = when {
        record.engineId != supported.engineId -> FaceRestoreOutcome.INCOMPATIBLE_ENGINE
        record.modelId != supported.modelId || record.modelVersion != supported.modelVersion -> FaceRestoreOutcome.INCOMPATIBLE_MODEL
        record.templateFormatVersion != supported.templateFormatVersion -> FaceRestoreOutcome.UNSUPPORTED_TEMPLATE_FORMAT
        else -> null
    }
    private sealed class PlannedRestore(open val result: FaceRestoreRecordResult) {
        class Ready(val backup: FaceBackupRecord, val record: FaceEnrollmentRecord) : PlannedRestore(FaceRestoreRecordResult(backup.enrollmentId.value, FaceRestoreOutcome.RESTORED))
        class Result(backup: FaceBackupRecord, outcome: FaceRestoreOutcome) : PlannedRestore(FaceRestoreRecordResult(backup.enrollmentId.value, outcome))
    }
}
