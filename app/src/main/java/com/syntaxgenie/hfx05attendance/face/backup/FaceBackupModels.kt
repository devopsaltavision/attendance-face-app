package com.syntaxgenie.hfx05attendance.face.backup

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateMetadata

const val FACE_BACKUP_SCHEMA_VERSION = 1
const val FACE_BACKUP_APPLICATION_SCHEMA_ID = "com.syntaxgenie.hfx05attendance.face.backup"

/** Portable record with an opaque *portable-protected* template, never a local-Keystore payload. */
class FaceBackupRecord(
    val enrollmentId: FaceEnrollmentId,
    val employeeId: String,
    protectedBackupTemplatePayload: ByteArray,
    val engineId: String,
    val modelId: String,
    val modelVersion: String,
    val templateFormatVersion: String,
    val status: FaceEnrollmentStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val backupSchemaVersion: Int,
    val qualityScore: Double? = null,
    val enrollmentSampleCount: Int? = null,
) {
    private val protectedBackupTemplatePayload = protectedBackupTemplatePayload.copyOf()
    init {
        require(employeeId.isNotBlank() && engineId.isNotBlank() && modelId.isNotBlank() && modelVersion.isNotBlank() && templateFormatVersion.isNotBlank())
        require(this.protectedBackupTemplatePayload.isNotEmpty())
        require(createdAt > 0 && updatedAt >= createdAt && backupSchemaVersion > 0)
        require(qualityScore == null || qualityScore.isFinite())
        require(enrollmentSampleCount == null || enrollmentSampleCount > 0)
    }
    fun protectedBackupTemplatePayload(): ByteArray = protectedBackupTemplatePayload.copyOf()
    fun metadata() = FaceTemplateMetadata(engineId, modelId, modelVersion, templateFormatVersion,
        backupSchemaVersion, qualityScore, enrollmentSampleCount)
    override fun toString() = "FaceBackupRecord(enrollmentId=$enrollmentId, employeeId=$employeeId, " +
        "engineId=$engineId, modelId=$modelId, modelVersion=$modelVersion, templateFormatVersion=$templateFormatVersion, " +
        "status=$status, createdAt=$createdAt, updatedAt=$updatedAt, backupSchemaVersion=$backupSchemaVersion, protectedBackupTemplatePayload=<redacted>)"
}

class FaceBackupManifest(
    val backupSchemaVersion: Int,
    val createdAt: Long,
    val applicationSchemaId: String,
    records: List<FaceBackupRecord>,
) {
    private val records = records.toList()
    init {
        require(backupSchemaVersion > 0 && createdAt > 0 && applicationSchemaId.isNotBlank())
        require(this.records.all { it.backupSchemaVersion == backupSchemaVersion })
    }
    val recordCount: Int get() = records.size
    fun records(): List<FaceBackupRecord> = records.toList()
    override fun toString() = "FaceBackupManifest(backupSchemaVersion=$backupSchemaVersion, createdAt=$createdAt, " +
        "applicationSchemaId=$applicationSchemaId, recordCount=$recordCount, records=<redacted>)"
}

class PortableBackupKeyProvisioningRequiredException : IllegalStateException(
    "PORTABLE BACKUP KEY PROVISIONING REQUIRED",
)

/** Boundary for authorized, portable protection. It must never reuse a device-local Keystore key. */
interface FaceBackupProtector {
    fun protect(plaintextTemplate: ByteArray): ByteArray
    fun unprotect(protectedBackupTemplate: ByteArray): ByteArray
}

object UnprovisionedFaceBackupProtector : FaceBackupProtector {
    override fun protect(plaintextTemplate: ByteArray): ByteArray = throw PortableBackupKeyProvisioningRequiredException()
    override fun unprotect(protectedBackupTemplate: ByteArray): ByteArray = throw PortableBackupKeyProvisioningRequiredException()
}
