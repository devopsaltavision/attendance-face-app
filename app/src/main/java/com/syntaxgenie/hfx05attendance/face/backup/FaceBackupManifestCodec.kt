package com.syntaxgenie.hfx05attendance.face.backup

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class InvalidFaceBackupException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Strict binary transport representation; no logging or textual rendering of payloads. */
object FaceBackupManifestCodec {
    private const val maxRecords = 100_000
    private const val maxPayloadBytes = 16 * 1024 * 1024
    private val magic = byteArrayOf('F'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())

    fun encode(manifest: FaceBackupManifest): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.write(magic); out.writeInt(manifest.backupSchemaVersion); out.writeLong(manifest.createdAt)
            out.writeUTF(manifest.applicationSchemaId); out.writeInt(manifest.recordCount)
            manifest.records().forEach { record ->
                out.writeUTF(record.enrollmentId.value); out.writeUTF(record.employeeId)
                out.writeUTF(record.engineId); out.writeUTF(record.modelId); out.writeUTF(record.modelVersion); out.writeUTF(record.templateFormatVersion)
                out.writeUTF(record.status.name); out.writeLong(record.createdAt); out.writeLong(record.updatedAt); out.writeInt(record.backupSchemaVersion)
                out.writeBoolean(record.qualityScore != null); record.qualityScore?.let(out::writeDouble)
                out.writeBoolean(record.enrollmentSampleCount != null); record.enrollmentSampleCount?.let(out::writeInt)
                val payload = record.protectedBackupTemplatePayload(); out.writeInt(payload.size); out.write(payload)
            }
        }; bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): FaceBackupManifest = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (ByteArray(4).also(input::readFully).contentEquals(magic).not()) invalid("magic")
            val schema = input.readInt(); val createdAt = input.readLong(); val applicationId = input.readUTF()
            val count = input.readInt(); if (count !in 0..maxRecords) invalid("record count")
            val records = List(count) {
                val enrollmentId = FaceEnrollmentId(input.readUTF()); val employeeId = input.readUTF()
                val engineId = input.readUTF(); val modelId = input.readUTF(); val modelVersion = input.readUTF(); val format = input.readUTF()
                val status = FaceEnrollmentStatus.valueOf(input.readUTF()); val recordCreated = input.readLong(); val updated = input.readLong(); val recordSchema = input.readInt()
                val quality = if (input.readBoolean()) input.readDouble() else null
                val samples = if (input.readBoolean()) input.readInt() else null
                val size = input.readInt(); if (size !in 1..maxPayloadBytes) invalid("payload length")
                val payload = ByteArray(size).also(input::readFully)
                FaceBackupRecord(enrollmentId, employeeId, payload, engineId, modelId, modelVersion, format,
                    status, recordCreated, updated, recordSchema, quality, samples)
            }
            if (input.available() != 0) invalid("trailing data")
            FaceBackupManifest(schema, createdAt, applicationId, records)
        }
    } catch (error: InvalidFaceBackupException) { throw error
    } catch (error: Exception) { throw InvalidFaceBackupException("Invalid face backup.", error) }

    private fun invalid(field: String): Nothing = throw InvalidFaceBackupException("Invalid face backup $field.")
}
