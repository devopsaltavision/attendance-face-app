package com.syntaxgenie.hfx05attendance.fingerprint.repository.cache

import com.syntaxgenie.hfx05attendance.fingerprint.matcher.FingerprintTemplate
import com.syntaxgenie.hfx05attendance.fingerprint.matcher.MatcherMetadata
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRecord
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepository
import com.syntaxgenie.hfx05attendance.fingerprint.repository.BiometricRepositorySnapshot
import com.syntaxgenie.hfx05attendance.fingerprint.repository.FingerPosition
import com.syntaxgenie.hfx05attendance.fingerprint.repository.RepositoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricTemplateCacheTest {
    private val metadata = MatcherMetadata("engine", "1", "format", 1)

    @Test
    fun emptyRepositoryLoadsOnce() {
        val repository = FakeRepository(emptyList())
        val result = BiometricTemplateCache(repository).reload() as RepositoryResult.Success
        assertEquals(0, result.value.recordCount)
        assertEquals(1, repository.getAllCalls)
    }

    @Test
    fun reloadClearAndUpdateMaintainSnapshot() {
        val repository = FakeRepository(listOf(record(1, 1)))
        val cache = BiometricTemplateCache(repository)
        assertEquals(1, (cache.reload() as RepositoryResult.Success).value.recordCount)
        assertNotNull(cache.snapshot().lastReloadDurationNanos)
        assertEquals(2, cache.update(record(2, 1)).recordCount)
        assertEquals(2, cache.update(record(2, 1)).recordCount)
        cache.clear()
        assertEquals(0, cache.snapshot().recordCount)
    }

    @Test
    fun cacheLoadsExactly750RecordsWithOneRepositoryQuery() {
        val records = (1..150).flatMap { employee ->
            (1..5).map { slot -> record(employee, slot) }
        }
        val repository = FakeRepository(records)
        var clock = 1_000L
        val cache = BiometricTemplateCache(repository) { clock.also { clock += 250 } }

        val snapshot = (cache.reload() as RepositoryResult.Success).value

        assertEquals(750, snapshot.recordCount)
        assertEquals(1, repository.getAllCalls)
        assertEquals(750, snapshot.records.map { it.recordId }.toSet().size)
        assertEquals(150, snapshot.records.map { it.enrollmentId }.toSet().size)
        assertEquals(150, snapshot.records.map { it.employeeId }.toSet().size)
        assertTrue(snapshot.records.all { it.fingerPosition == FingerPosition.RIGHT_INDEX })
        assertEquals(250L, snapshot.lastReloadDurationNanos)
        println("Engineering cache rebuild timing (deterministic test clock): ${snapshot.lastReloadDurationNanos} ns")
    }

    private fun record(employee: Int, slot: Int) = BiometricRecord(
        recordId = "record-$employee-$slot",
        enrollmentId = "enrollment-$employee",
        employeeId = "EMP%03d".format(employee),
        fingerPosition = FingerPosition.RIGHT_INDEX,
        templateSlot = slot,
        template = FingerprintTemplate(metadata, byteArrayOf(employee.toByte(), slot.toByte())),
        createdAtEpochMillis = 1,
    )

    private class FakeRepository(private val records: List<BiometricRecord>) : BiometricRepository {
        var getAllCalls = 0
        override fun getAll(): RepositoryResult<List<BiometricRecord>> {
            getAllCalls++
            return RepositoryResult.Success(records)
        }
        override fun save(record: BiometricRecord) = RepositoryResult.Success(record)
        override fun saveEnrollment(records: List<BiometricRecord>) = RepositoryResult.Success(records)
        override fun getByEmployee(employeeId: String) = RepositoryResult.Success(emptyList<BiometricRecord>())
        override fun getByEmployeeAndFinger(employeeId: String, fingerPosition: FingerPosition) =
            RepositoryResult.Success(emptyList<BiometricRecord>())
        override fun getByEnrollmentId(enrollmentId: String) =
            RepositoryResult.Success(emptyList<BiometricRecord>())
        override fun deleteByEmployeeAndFinger(employeeId: String, fingerPosition: FingerPosition) =
            RepositoryResult.Success(0)
        override fun deleteByEmployee(employeeId: String) = RepositoryResult.Success(0)
        override fun diagnostics() = BiometricRepositorySnapshot()
    }
}
