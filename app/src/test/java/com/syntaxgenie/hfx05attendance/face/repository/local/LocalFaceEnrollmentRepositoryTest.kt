package com.syntaxgenie.hfx05attendance.face.repository.local

import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentId
import com.syntaxgenie.hfx05attendance.face.repository.FaceEnrollmentRecord
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateCompatibility
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateMetadata
import com.syntaxgenie.hfx05attendance.face.repository.FaceTemplateProtector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFaceEnrollmentRepositoryTest {
    @Test fun addReadListDeleteAndCompatibilityKeepPayloadOpaque() {
        val dao = InMemoryFaceEnrollmentDao()
        val repository = LocalFaceEnrollmentRepository(dao, ReversingProtector)
        val first = record("face-1", "employee-1", byteArrayOf(1, 2, 3))
        val second = record("face-2", "employee-1", byteArrayOf(4, 5, 6))
        val incompatible = record("face-3", "employee-2", byteArrayOf(7), modelVersion = "2")

        repository.add(first)
        repository.add(second)
        repository.add(incompatible)

        val restored = repository.getById(FaceEnrollmentId("face-1"))!!
        assertArrayEquals(byteArrayOf(1, 2, 3), restored.templatePayload())
        assertFalse(restored.toString().contains("1, 2, 3"))
        assertTrue(restored.toString().contains("<redacted>"))
        assertEquals(2, repository.listByEmployee("employee-1").size)
        assertEquals(2, repository.listCompatible(compatibility("1")).size)
        assertEquals(1, repository.listCompatible(compatibility("2")).size)

        assertTrue(repository.delete(FaceEnrollmentId("face-1")))
        assertNull(repository.getById(FaceEnrollmentId("face-1")))
        assertEquals(1, repository.deleteAllForEmployee("employee-1"))
        assertTrue(repository.listByEmployee("employee-1").isEmpty())
    }

    @Test fun updateIsIntentionalAndDoesNotInsertMissingRecord() {
        val repository = LocalFaceEnrollmentRepository(InMemoryFaceEnrollmentDao(), ReversingProtector)
        val record = record("face-1", "employee-1", byteArrayOf(9))
        assertFalse(repository.update(record))
        repository.add(record)
        assertTrue(repository.update(record))
    }

    private fun record(id: String, employeeId: String, payload: ByteArray, modelVersion: String = "1") =
        FaceEnrollmentRecord(
            FaceEnrollmentId(id), employeeId, payload,
            FaceTemplateMetadata("production-engine", "model-a", modelVersion, "template-v1", 1),
            1_700_000_000_000L, 1_700_000_000_000L,
        )

    private fun compatibility(modelVersion: String) =
        FaceTemplateCompatibility("production-engine", "model-a", modelVersion, "template-v1")

    private object ReversingProtector : FaceTemplateProtector {
        override fun protect(plaintextTemplate: ByteArray): ByteArray = plaintextTemplate.reversedArray()
        override fun unprotect(protectedTemplate: ByteArray): ByteArray = protectedTemplate.reversedArray()
    }

    private class InMemoryFaceEnrollmentDao : FaceEnrollmentDao {
        private val entries = linkedMapOf<String, FaceEnrollmentEntity>()

        override fun getById(id: String): FaceEnrollmentEntity? = entries[id]
        override fun listByEmployee(employeeId: String): MutableList<FaceEnrollmentEntity> =
            entries.values.filter { it.employeeId == employeeId }.sortedBy { it.createdAt }.toMutableList()

        override fun listCompatible(engineId: String, modelId: String, modelVersion: String,
            templateFormatVersion: String): MutableList<FaceEnrollmentEntity> =
            entries.values.filter {
                it.engineId == engineId && it.modelId == modelId && it.modelVersion == modelVersion &&
                    it.templateFormatVersion == templateFormatVersion
            }.sortedBy { it.createdAt }.toMutableList()

        override fun insert(entity: FaceEnrollmentEntity) {
            check(entries.putIfAbsent(entity.enrollmentId, entity) == null) { "Duplicate enrollment ID" }
        }

        override fun update(entity: FaceEnrollmentEntity): Int {
            if (entity.enrollmentId !in entries) return 0
            entries[entity.enrollmentId] = entity
            return 1
        }

        override fun deleteById(id: String): Int = if (entries.remove(id) != null) 1 else 0

        override fun deleteAllForEmployee(employeeId: String): Int {
            val ids = entries.values.filter { it.employeeId == employeeId }.map { it.enrollmentId }
            ids.forEach(entries::remove)
            return ids.size
        }
    }
}
