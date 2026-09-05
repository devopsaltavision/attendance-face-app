package com.syntaxgenie.hfx05attendance.face.backup

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRepository
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentStatus
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceBackupServiceTest {
    private val compatible = FaceTemplateCompatibility("engine", "model", "1", "format-1")

    @Test fun manifestRoundTripPreservesRequiredMetadataAndRedactsPayload() {
        val manifest = manifest(record())
        val decoded = FaceBackupManifestCodec.decode(FaceBackupManifestCodec.encode(manifest))
        val restored = decoded.records().single()
        assertEquals("employee-1", restored.employeeId)
        assertEquals("engine", restored.engineId)
        assertEquals("model", restored.modelId)
        assertEquals("1", restored.modelVersion)
        assertEquals("format-1", restored.templateFormatVersion)
        assertEquals(FaceEnrollmentStatus.ACTIVE, restored.status)
        assertFalse(restored.toString().contains("9, 8, 7"))
        assertFalse(decoded.toString().contains("9, 8, 7"))
    }

    @Test fun duplicateRestoreDoesNotOverwrite() {
        val repository = InMemoryRepository().also { it.add(faceRecord("id-1", byteArrayOf(1))) }
        val result = importer(repository).restore(manifest(record("id-1")))
        assertEquals(FaceRestoreOutcome.SKIPPED_DUPLICATE, result.records.single().outcome)
        assertTrue(repository.getById(FaceEnrollmentId("id-1"))!!.templatePayload().contentEquals(byteArrayOf(1)))
    }

    @Test fun incompatibleEngineAndModelAreSkipped() {
        val result = importer(InMemoryRepository()).restore(manifest(record(engine = "other"), record(model = "other")))
        assertEquals(FaceRestoreOutcome.INCOMPATIBLE_ENGINE, result.records[0].outcome)
        assertEquals(FaceRestoreOutcome.INCOMPATIBLE_MODEL, result.records[1].outcome)
    }

    @Test fun invalidBackupSchemaIsRejectedWithoutInsert() {
        val repository = InMemoryRepository()
        val invalid = FaceBackupManifest(2, NOW, FACE_BACKUP_APPLICATION_SCHEMA_ID, listOf(record(schema = 2)))
        val result = importer(repository).restore(invalid)
        assertEquals(FaceRestoreOutcome.INVALID_BACKUP, result.records.single().outcome)
        assertTrue(repository.all.isEmpty())
    }

    @Test fun unprovisionedExporterFailsClosed() {
        val result = FaceBackupExporter(UnprovisionedFaceBackupProtector).export(listOf(faceRecord("id-1", byteArrayOf(1))), NOW)
        assertTrue(result is FaceBackupExportResult.PortableKeyProvisioningRequired)
    }

    private fun importer(repository: InMemoryRepository) = FaceBackupImporter(repository, ReversingBackupProtector, compatible)
    private fun manifest(vararg records: FaceBackupRecord) = FaceBackupManifest(FACE_BACKUP_SCHEMA_VERSION, NOW, FACE_BACKUP_APPLICATION_SCHEMA_ID, records.toList())
    private fun record(id: String = "id-1", engine: String = "engine", model: String = "model", schema: Int = 1) =
        FaceBackupRecord(FaceEnrollmentId(id), "employee-1", byteArrayOf(9, 8, 7), engine, model, "1", "format-1", FaceEnrollmentStatus.ACTIVE, NOW, NOW, schema)
    private fun faceRecord(id: String, payload: ByteArray) = FaceEnrollmentRecord(FaceEnrollmentId(id), "employee-1", payload,
        FaceTemplateMetadata("engine", "model", "1", "format-1", 1), NOW, NOW)

    private object ReversingBackupProtector : FaceBackupProtector {
        override fun protect(plaintextTemplate: ByteArray) = plaintextTemplate.reversedArray()
        override fun unprotect(protectedBackupTemplate: ByteArray) = protectedBackupTemplate.reversedArray()
    }
    private class InMemoryRepository : FaceEnrollmentRepository {
        val all = linkedMapOf<FaceEnrollmentId, FaceEnrollmentRecord>()
        override fun getById(id: FaceEnrollmentId) = all[id]
        override fun listByEmployee(employeeId: String) = all.values.filter { it.employeeId == employeeId }
        override fun listCompatible(compatibility: FaceTemplateCompatibility) = all.values.filter { it.metadata.engineId == compatibility.engineId && it.metadata.modelId == compatibility.modelId && it.metadata.modelVersion == compatibility.modelVersion && it.metadata.templateFormatVersion == compatibility.templateFormatVersion }
        override fun add(record: FaceEnrollmentRecord) { check(all.putIfAbsent(record.id, record) == null) }
        override fun update(record: FaceEnrollmentRecord) = if (record.id in all) { all[record.id] = record; true } else false
        override fun delete(id: FaceEnrollmentId) = all.remove(id) != null
        override fun deleteAllForEmployee(employeeId: String): Int { val ids = listByEmployee(employeeId).map { it.id }; ids.forEach(all::remove); return ids.size }
    }
    private companion object { const val NOW = 1_700_000_000_000L }
}
